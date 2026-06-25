'use strict';

const { pool } = require('../configs/db');

/**
 * Create a new user with the given email, auth_verifier, and kdf_salt.
 *
 * @param {string} email         - User's email address (unique)
 * @param {string} authVerifier  - Argon2id hash of the client's auth_key
 * @param {string} kdfSalt       - Base64-encoded 16-byte salt for client KDF
 * @returns {Promise<{id: string, email: string, created_at: string}>}
 */
async function createUser(email, authVerifier, kdfSalt) {
  const result = await pool.query(
    `INSERT INTO users (email, auth_verifier, kdf_salt)
     VALUES ($1, $2, $3)
     RETURNING id, email, created_at`,
    [email, authVerifier, kdfSalt]
  );
  return result.rows[0];
}

/**
 * Find a user by email address.
 *
 * @param {string} email
 * @returns {Promise<{id: string, email: string, auth_verifier: string, kdf_salt: string, created_at: string} | null>}
 */
async function findByEmail(email) {
  const result = await pool.query(
    `SELECT id, email, auth_verifier, kdf_salt, created_at
     FROM users
     WHERE email = $1`,
    [email]
  );
  return result.rows[0] || null;
}

/**
 * Find a user by UUID.
 *
 * @param {string} id - User UUID
 * @returns {Promise<{id: string, email: string, auth_verifier: string, kdf_salt: string, created_at: string} | null>}
 */
async function findById(id) {
  const result = await pool.query(
    `SELECT id, email, auth_verifier, kdf_salt, created_at
     FROM users
     WHERE id = $1`,
    [id]
  );
  return result.rows[0] || null;
}

/**
 * Delete a user by UUID. ON DELETE CASCADE in the schema handles
 * cleanup of vaults and refresh_tokens.
 *
 * @param {string} id - User UUID
 * @returns {Promise<boolean>} true if a row was deleted
 */
async function deleteUser(id) {
  const result = await pool.query(
    `DELETE FROM users WHERE id = $1`,
    [id]
  );
  return result.rowCount > 0;
}

module.exports = {
  createUser,
  findByEmail,
  findById,
  deleteUser,
};
