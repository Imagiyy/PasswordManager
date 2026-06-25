'use strict';

const { pool } = require('../configs/db');

/**
 * Create an empty vault row for a newly registered user.
 * The encrypted_blob is initially empty (will be set on first push),
 * and vector_clock starts as an empty JSON object.
 *
 * @param {string} userId          - User UUID
 * @param {string} encryptedBlob   - Initial encrypted blob (empty vault)
 * @param {object} vectorClock     - Initial vector clock (default: {})
 * @returns {Promise<{id: string, user_id: string, updated_at: string}>}
 */
async function createVault(userId, encryptedBlob = '', vectorClock = {}) {
  const result = await pool.query(
    `INSERT INTO vaults (user_id, encrypted_blob, vector_clock)
     VALUES ($1, $2, $3::jsonb)
     RETURNING id, user_id, updated_at`,
    [userId, encryptedBlob, JSON.stringify(vectorClock)]
  );
  return result.rows[0];
}

/**
 * Get the vault row for a given user.
 *
 * @param {string} userId - User UUID
 * @returns {Promise<{id: string, user_id: string, encrypted_blob: string, vector_clock: object, updated_at: string} | null>}
 */
async function getVaultByUserId(userId) {
  const result = await pool.query(
    `SELECT id, user_id, encrypted_blob, vector_clock, updated_at
     FROM vaults
     WHERE user_id = $1`,
    [userId]
  );
  return result.rows[0] || null;
}

/**
 * Update the vault's encrypted blob and vector clock.
 * Also updates the updated_at timestamp.
 *
 * @param {string} userId         - User UUID
 * @param {string} encryptedBlob  - New base64(iv + ciphertext + tag) blob
 * @param {object} vectorClock    - New vector clock object
 * @returns {Promise<{id: string, user_id: string, updated_at: string} | null>}
 */
async function updateVault(userId, encryptedBlob, vectorClock) {
  const result = await pool.query(
    `UPDATE vaults
     SET encrypted_blob = $2,
         vector_clock = $3::jsonb,
         updated_at = now()
     WHERE user_id = $1
     RETURNING id, user_id, updated_at`,
    [userId, encryptedBlob, JSON.stringify(vectorClock)]
  );
  return result.rows[0] || null;
}

/**
 * Delete the vault for a given user.
 * Note: ON DELETE CASCADE on users also handles this, but this method
 * allows explicit vault-only deletion if needed.
 *
 * @param {string} userId - User UUID
 * @returns {Promise<boolean>} true if a row was deleted
 */
async function deleteVaultByUserId(userId) {
  const result = await pool.query(
    `DELETE FROM vaults WHERE user_id = $1`,
    [userId]
  );
  return result.rowCount > 0;
}

module.exports = {
  createVault,
  getVaultByUserId,
  updateVault,
  deleteVaultByUserId,
};
