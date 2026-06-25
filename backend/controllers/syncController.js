'use strict';

const vaultModel = require('../models/vaultModel');
const { AppError } = require('../middleware/errorHandler');
const logger = require('../utils/logger');

/**
 * Determine if the client's vector clock dominates the server's vector clock.
 *
 * A client clock dominates if, for every device in the server's clock,
 * the client's clock has an equal or higher counter. If the client is
 * missing any device or has a lower counter, it does NOT dominate
 * (meaning the client is missing updates from that device).
 *
 * @param {object} clientClock - Client's vector clock { device_id: counter }
 * @param {object} serverClock - Server's vector clock { device_id: counter }
 * @returns {boolean} true if client dominates (can safely overwrite server)
 */
function isClientDominant(clientClock, serverClock) {
  for (const [device, serverCount] of Object.entries(serverClock)) {
    const clientCount = clientClock[device];
    if (clientCount === undefined || clientCount < serverCount) {
      return false; // Client is missing updates from this device
    }
  }
  return true;
}

/**
 * GET /api/sync/pull
 *
 * Fetches the server's current encrypted vault for the authenticated user.
 *
 * Returns: 200 { encrypted_blob: string|null, vector_clock: object }
 * Errors: 401 UNAUTHORIZED (handled by authMiddleware)
 */
async function pull(req, res, next) {
  try {
    const userId = req.user.id;

    const vault = await vaultModel.getVaultByUserId(userId);

    if (!vault || !vault.encrypted_blob) {
      // No vault data pushed yet
      return res.status(200).json({
        encrypted_blob: null,
        vector_clock: {},
      });
    }

    return res.status(200).json({
      encrypted_blob: vault.encrypted_blob,
      vector_clock: vault.vector_clock || {},
    });
  } catch (err) {
    return next(err);
  }
}

/**
 * POST /api/sync/push
 *
 * Pushes the client's encrypted vault after local changes.
 * Compares vector clocks to determine if the client can safely overwrite.
 *
 * Body: { encrypted_blob: string, vector_clock: object }
 * Returns: 200 { success: true } — client dominated, data stored
 * Returns: 409 { error: SYNC_CONFLICT, encrypted_blob, vector_clock } — must merge
 * Errors: 400 VALIDATION_ERROR
 */
async function push(req, res, next) {
  try {
    const userId = req.user.id;
    const { encrypted_blob, vector_clock: clientClock } = req.body;

    // Get current server vault state
    const vault = await vaultModel.getVaultByUserId(userId);

    if (!vault) {
      // No vault row exists (shouldn't happen after registration, but handle it)
      await vaultModel.createVault(userId, encrypted_blob, clientClock);
      return res.status(200).json({ success: true });
    }

    const serverClock = vault.vector_clock || {};

    // Check if client's clock dominates server's clock
    if (isClientDominant(clientClock, serverClock)) {
      // Client has all updates — safe to overwrite
      await vaultModel.updateVault(userId, encrypted_blob, clientClock);

      logger.debug(
        { userId, clientClock, serverClock },
        'Vault push accepted (client dominant)'
      );

      return res.status(200).json({ success: true });
    }

    // Conflict — client is missing updates from at least one device
    logger.info(
      { userId, clientClock, serverClock },
      'Sync conflict detected'
    );

    return res.status(409).json({
      error: 'SYNC_CONFLICT',
      message: 'Server has updates your client has not seen. Please merge.',
      encrypted_blob: vault.encrypted_blob,
      vector_clock: serverClock,
    });
  } catch (err) {
    return next(err);
  }
}

module.exports = {
  pull,
  push,
};
