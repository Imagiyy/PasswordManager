'use strict';

const { Pool } = require('pg');
const config = require('./env');
const logger = require('../utils/logger');

/**
 * PostgreSQL connection pool.
 * Uses DATABASE_URL from environment configuration.
 */
const pool = new Pool({
  connectionString: config.databaseUrl,
  max: 20,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 5000,
});

/**
 * Log pool errors (e.g. unexpected disconnections).
 */
pool.on('error', (err) => {
  logger.error({ err }, 'Unexpected PostgreSQL pool error');
});

/**
 * Health check — verifies the database is reachable.
 * Called once at server startup. Throws if the database is unreachable.
 *
 * @returns {Promise<void>}
 */
async function checkHealth() {
  const client = await pool.connect();
  try {
    await client.query('SELECT 1');
    logger.info('PostgreSQL connection verified');
  } finally {
    client.release();
  }
}

/**
 * Graceful shutdown — drain the pool.
 *
 * @returns {Promise<void>}
 */
async function shutdown() {
  logger.info('Draining PostgreSQL connection pool');
  await pool.end();
  logger.info('PostgreSQL pool drained');
}

module.exports = {
  pool,
  checkHealth,
  shutdown,
};
