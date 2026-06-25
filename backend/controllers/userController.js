'use strict';

const userModel = require('../models/userModel');
const { verifyAuthKey } = require('../utils/crypto');
const { AppError } = require('../middleware/errorHandler');
const logger = require('../utils/logger');

/**
 * GET /api/user/profile
 *
 * Returns the authenticated user's profile information.
 *
 * Headers: Authorization: Bearer <access_token>
 * Returns: 200 { user_id, email, created_at }
 * Errors: 401 UNAUTHORIZED (handled by authMiddleware)
 */
async function getProfile(req, res, next) {
  try {
    const user = await userModel.findById(req.user.id);

    if (!user) {
      return next(
        new AppError('User not found', 404, 'USER_NOT_FOUND')
      );
    }

    return res.status(200).json({
      user_id: user.id,
      email: user.email,
      created_at: user.created_at,
    });
  } catch (err) {
    return next(err);
  }
}

/**
 * DELETE /api/user/account
 *
 * Permanently deletes the user, their vault, and all refresh tokens.
 * Requires re-authentication via auth_key in the request body.
 *
 * ON DELETE CASCADE in the schema handles vault + token cleanup.
 *
 * Headers: Authorization: Bearer <access_token>
 * Body: { auth_key: base64 string }
 * Returns: 200 { success: true }
 * Errors: 401 INVALID_CREDENTIALS (auth_key mismatch)
 */
async function deleteAccount(req, res, next) {
  try {
    const { auth_key } = req.body;

    // Look up the user to get auth_verifier
    const user = await userModel.findById(req.user.id);
    if (!user) {
      return next(
        new AppError('User not found', 404, 'USER_NOT_FOUND')
      );
    }

    // Re-verify identity with auth_key before deletion
    const isValid = await verifyAuthKey(auth_key, user.auth_verifier);
    if (!isValid) {
      return next(
        new AppError('Invalid auth key — re-authentication failed', 401, 'INVALID_CREDENTIALS')
      );
    }

    // Delete user — CASCADE handles vault and refresh_tokens
    await userModel.deleteUser(user.id);

    logger.info({ userId: user.id, email: user.email }, 'Account deleted');

    return res.status(200).json({
      success: true,
    });
  } catch (err) {
    return next(err);
  }
}

module.exports = {
  getProfile,
  deleteAccount,
};
