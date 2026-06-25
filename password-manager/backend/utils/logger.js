'use strict';

const pino = require('pino');

/**
 * Structured JSON logger via Pino.
 *
 * Log level is determined by NODE_ENV:
 *   - production  → 'info'
 *   - development → 'debug'
 *   - test        → 'silent'
 */
const level = (() => {
  switch (process.env.NODE_ENV) {
    case 'production':
      return 'info';
    case 'test':
      return 'silent';
    default:
      return 'debug';
  }
})();

const logger = pino({
  level,
  timestamp: pino.stdTimeFunctions.isoTime,
  formatters: {
    level(label) {
      return { level: label };
    },
  },
  // Redact sensitive fields from logs
  redact: {
    paths: [
      'req.headers.authorization',
      'req.body.auth_key',
      'req.body.refresh_token',
      'req.body.password',
    ],
    censor: '[REDACTED]',
  },
});

module.exports = logger;
