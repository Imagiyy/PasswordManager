'use strict';

const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const pinoHttp = require('pino-http');

const config = require('./configs/env');
const { checkHealth, shutdown } = require('./configs/db');
const logger = require('./utils/logger');
const { errorHandler } = require('./middleware/errorHandler');

// Route modules
const authRoutes = require('./routes/auth');
const syncRoutes = require('./routes/sync');
const userRoutes = require('./routes/user');

const app = express();

// ── Middleware (exact order from spec) ───────────────────────────────────

// 1. Pino HTTP request logger
app.use(
  pinoHttp({
    logger,
    // Redact sensitive headers from logs
    serializers: {
      req(req) {
        return {
          id: req.id,
          method: req.method,
          url: req.url,
          remoteAddress: req.remoteAddress,
        };
      },
    },
  })
);

// 2. Helmet — secure HTTP headers
app.use(
  helmet({
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],
        scriptSrc: ["'self'"],
        styleSrc: ["'self'"],
        imgSrc: ["'self'"],
        connectSrc: ["'self'"],
        fontSrc: ["'self'"],
        objectSrc: ["'none'"],
        frameSrc: ["'none'"],
      },
    },
    hsts: {
      maxAge: 31536000, // 1 year
      includeSubDomains: true,
      preload: true,
    },
  })
);

// 3. CORS
app.use(
  cors({
    origin: config.allowedOrigins,
    credentials: true,
    methods: ['GET', 'POST', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Content-Type', 'Authorization'],
  })
);

// 4. JSON body parser
app.use(express.json({ limit: '512kb' }));

// 5. Route mounting
app.use('/api/auth', authRoutes);
app.use('/api/sync', syncRoutes);
app.use('/api/user', userRoutes);

// Health check endpoint (no auth required)
app.get('/health', (_req, res) => {
  res.status(200).json({ status: 'ok', timestamp: new Date().toISOString() });
});

// 6. Error handler (MUST be last — 4-argument Express middleware)
app.use(errorHandler);

// ── Server Startup ──────────────────────────────────────────────────────

async function start() {
  try {
    // Verify database connectivity before accepting requests
    await checkHealth();

    const server = app.listen(config.port, () => {
      logger.info(
        { port: config.port, env: config.nodeEnv },
        'Server started'
      );
    });

    // Graceful shutdown handlers
    const gracefulShutdown = async (signal) => {
      logger.info({ signal }, 'Received shutdown signal');

      server.close(async () => {
        logger.info('HTTP server closed');
        await shutdown(); // Drain DB pool
        process.exit(0);
      });

      // Force shutdown after 10 seconds
      setTimeout(() => {
        logger.error('Forced shutdown after timeout');
        process.exit(1);
      }, 10000);
    };

    process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
    process.on('SIGINT', () => gracefulShutdown('SIGINT'));

    // Handle uncaught exceptions and unhandled rejections
    process.on('uncaughtException', (err) => {
      logger.fatal({ err }, 'Uncaught exception — shutting down');
      process.exit(1);
    });

    process.on('unhandledRejection', (reason) => {
      logger.fatal({ err: reason }, 'Unhandled rejection — shutting down');
      process.exit(1);
    });
  } catch (err) {
    logger.fatal({ err }, 'Failed to start server');
    process.exit(1);
  }
}

start();

module.exports = app;
