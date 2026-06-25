'use strict';

const fs = require('fs');
const path = require('path');

// Load .env file if present (development)
require('dotenv').config({ path: path.resolve(__dirname, '..', '.env') });

/**
 * Required environment variables. The server will refuse to start if any are missing.
 */
const REQUIRED_VARS = [
  'PORT',
  'NODE_ENV',
  'DATABASE_URL',
  'JWT_PRIVATE_KEY_PATH',
  'JWT_PUBLIC_KEY_PATH',
  'JWT_ACCESS_EXPIRY',
  'REFRESH_TOKEN_EXPIRY_DAYS',
  'ALLOWED_ORIGINS',
  'ARGON2_MEMORY_COST',
  'ARGON2_TIME_COST',
  'ARGON2_PARALLELISM',
];

const missing = REQUIRED_VARS.filter((v) => !process.env[v]);
if (missing.length > 0) {
  throw new Error(
    `Missing required environment variables: ${missing.join(', ')}.\n` +
    'Copy .env.example to .env and fill in all values.'
  );
}

/**
 * Read RS256 PEM key files from disk.
 * Paths are resolved relative to the backend directory.
 */
function readKeyFile(envVar) {
  const keyPath = path.resolve(__dirname, '..', process.env[envVar]);
  if (!fs.existsSync(keyPath)) {
    throw new Error(
      `Key file not found at "${keyPath}" (from ${envVar}). ` +
      'Generate with: openssl genrsa -out private.pem 2048 && ' +
      'openssl rsa -pubout -in private.pem -out public.pem'
    );
  }
  return fs.readFileSync(keyPath, 'utf8');
}

const jwtPrivateKey = readKeyFile('JWT_PRIVATE_KEY_PATH');
const jwtPublicKey = readKeyFile('JWT_PUBLIC_KEY_PATH');

/**
 * Frozen configuration object — cannot be modified after creation.
 */
const config = Object.freeze({
  port: parseInt(process.env.PORT, 10),
  nodeEnv: process.env.NODE_ENV,
  databaseUrl: process.env.DATABASE_URL,

  jwt: Object.freeze({
    privateKey: jwtPrivateKey,
    publicKey: jwtPublicKey,
    accessExpiry: parseInt(process.env.JWT_ACCESS_EXPIRY, 10), // seconds
    algorithm: 'RS256',
  }),

  refreshTokenExpiryDays: parseInt(process.env.REFRESH_TOKEN_EXPIRY_DAYS, 10),

  allowedOrigins: process.env.ALLOWED_ORIGINS.split(',').map((s) => s.trim()),

  argon2: Object.freeze({
    memoryCost: parseInt(process.env.ARGON2_MEMORY_COST, 10),
    timeCost: parseInt(process.env.ARGON2_TIME_COST, 10),
    parallelism: parseInt(process.env.ARGON2_PARALLELISM, 10),
  }),
});

module.exports = config;
