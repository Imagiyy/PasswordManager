'use strict';

const crypto = require('crypto');
const argon2 = require('argon2');
const config = require('../configs/env');

/**
 * Hash a client-provided auth_key (base64-encoded, 32 bytes) using Argon2id.
 * The resulting hash string embeds its own random salt — this is separate
 * from the client's kdf_salt and is managed entirely by the argon2 library.
 *
 * @param {string} authKeyB64 - Base64-encoded auth_key (32 bytes)
 * @returns {Promise<string>} Argon2id hash string (includes embedded salt)
 */
async function hashAuthKey(authKeyB64) {
  const authKeyBuffer = Buffer.from(authKeyB64, 'base64');
  if (authKeyBuffer.length !== 32) {
    throw new Error('auth_key must be exactly 32 bytes');
  }

  const hash = await argon2.hash(authKeyBuffer, {
    type: argon2.argon2id,
    memoryCost: config.argon2.memoryCost,
    timeCost: config.argon2.timeCost,
    parallelism: config.argon2.parallelism,
  });

  return hash;
}

/**
 * Verify a client-provided auth_key against a stored Argon2id hash.
 *
 * @param {string} authKeyB64 - Base64-encoded auth_key from the client
 * @param {string} verifier   - Stored Argon2id hash string
 * @returns {Promise<boolean>} true if auth_key matches the verifier
 */
async function verifyAuthKey(authKeyB64, verifier) {
  const authKeyBuffer = Buffer.from(authKeyB64, 'base64');
  try {
    return await argon2.verify(verifier, authKeyBuffer);
  } catch (err) {
    // argon2.verify throws on malformed hashes — treat as non-match
    return false;
  }
}

/**
 * Compute SHA-256 hash of a token string. Used to store refresh tokens
 * without keeping the raw token in the database.
 *
 * @param {string} token - Raw token string (e.g., 64-char hex)
 * @returns {string} Hex-encoded SHA-256 hash
 */
function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

/**
 * Generate a cryptographically secure random refresh token.
 * 32 random bytes encoded as a 64-character hex string.
 *
 * @returns {string} 64-character hex string
 */
function generateRefreshToken() {
  return crypto.randomBytes(32).toString('hex');
}

module.exports = {
  hashAuthKey,
  verifyAuthKey,
  hashToken,
  generateRefreshToken,
};
