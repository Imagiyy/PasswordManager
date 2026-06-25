'use strict';

const jwt = require('jsonwebtoken');
const config = require('../configs/env');
const userModel = require('../models/userModel');
const vaultModel = require('../models/vaultModel');
const { pool } = require('../configs/db');
const {
  hashAuthKey,
  verifyAuthKey,
  hashToken,
  generateRefreshToken,
} = require('../utils/crypto');
const { AppError } = require('../middleware/errorHandler');
const logger = require('../utils/logger');

/**
 * POST /api/auth/prelogin
 *
 * Returns the kdf_salt for a given email so the client can derive
 * auth_key before login. Required on every new session / device.
 *
 * Body: { email: string }
 * Returns: 200 { kdf_salt: string }
 * Errors: 404 USER_NOT_FOUND
 */
async function prelogin(req, res, next) {
  try {
    const { email } = req.body;

    const user = await userModel.findByEmail(email);
    if (!user) {
      return next(
        new AppError('User not found', 404, 'USER_NOT_FOUND')
      );
    }

    return res.status(200).json({
      kdf_salt: user.kdf_salt,
    });
  } catch (err) {
    return next(err);
  }
}

/**
 * POST /api/auth/register
 *
 * Creates a new user account. The client generates kdf_salt and auth_key
 * locally, then sends auth_key and kdf_salt to the server.
 *
 * Body: { email, auth_key (base64), kdf_salt (base64) }
 * Returns: 201 { user_id: uuid }
 * Errors: 409 EMAIL_EXISTS, 400 VALIDATION_ERROR
 */
async function register(req, res, next) {
  try {
    const { email, auth_key, kdf_salt } = req.body;

    // Check if email already exists
    const existingUser = await userModel.findByEmail(email);
    if (existingUser) {
      return next(
        new AppError('An account with this email already exists', 409, 'EMAIL_EXISTS')
      );
    }

    // Hash the auth_key with argon2id to create auth_verifier
    // The server NEVER stores auth_key itself — only the hash
    const authVerifier = await hashAuthKey(auth_key);

    // Create user
    const user = await userModel.createUser(email, authVerifier, kdf_salt);

    // Create an empty vault for the user with empty vector clock
    await vaultModel.createVault(user.id, '', {});

    logger.info({ userId: user.id, email }, 'New user registered');

    return res.status(201).json({
      user_id: user.id,
    });
  } catch (err) {
    // Handle unique constraint violation (race condition on email)
    if (err.code === '23505' && err.constraint === 'users_email_key') {
      return next(
        new AppError('An account with this email already exists', 409, 'EMAIL_EXISTS')
      );
    }
    return next(err);
  }
}

/**
 * POST /api/auth/login
 *
 * Authenticates a user using a client-derived auth_key.
 * Requires that the client called prelogin first to obtain kdf_salt.
 *
 * Body: { email, auth_key (base64) }
 * Returns: 200 { access_token: JWT, refresh_token: hex string }
 * Errors: 401 INVALID_CREDENTIALS
 */
async function login(req, res, next) {
  try {
    const { email, auth_key } = req.body;

    // Look up user
    const user = await userModel.findByEmail(email);
    if (!user) {
      return next(
        new AppError('Invalid email or auth key', 401, 'INVALID_CREDENTIALS')
      );
    }

    // Verify auth_key against stored auth_verifier
    const isValid = await verifyAuthKey(auth_key, user.auth_verifier);
    if (!isValid) {
      return next(
        new AppError('Invalid email or auth key', 401, 'INVALID_CREDENTIALS')
      );
    }

    // Generate JWT access token (RS256)
    const accessToken = jwt.sign(
      {
        sub: user.id,
        email: user.email,
      },
      config.jwt.privateKey,
      {
        algorithm: config.jwt.algorithm,
        expiresIn: config.jwt.accessExpiry,
      }
    );

    // Generate opaque refresh token
    const refreshToken = generateRefreshToken();
    const refreshTokenHash = hashToken(refreshToken);

    // Calculate expiry date for refresh token
    const expiresAt = new Date();
    expiresAt.setDate(expiresAt.getDate() + config.refreshTokenExpiryDays);

    // Store hashed refresh token in DB
    await pool.query(
      `INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
       VALUES ($1, $2, $3)`,
      [user.id, refreshTokenHash, expiresAt.toISOString()]
    );

    logger.info({ userId: user.id }, 'User logged in');

    return res.status(200).json({
      access_token: accessToken,
      refresh_token: refreshToken,
    });
  } catch (err) {
    return next(err);
  }
}

/**
 * POST /api/auth/refresh
 *
 * Issues a new access token using a valid refresh token.
 *
 * Body: { refresh_token: hex string }
 * Returns: 200 { access_token: JWT }
 * Errors: 401 INVALID_REFRESH_TOKEN
 */
async function refresh(req, res, next) {
  try {
    const { refresh_token } = req.body;

    const tokenHash = hashToken(refresh_token);

    // Look up the hashed refresh token
    const result = await pool.query(
      `SELECT rt.id, rt.user_id, rt.expires_at, u.email
       FROM refresh_tokens rt
       JOIN users u ON u.id = rt.user_id
       WHERE rt.token_hash = $1`,
      [tokenHash]
    );

    if (result.rows.length === 0) {
      return next(
        new AppError('Invalid or revoked refresh token', 401, 'INVALID_REFRESH_TOKEN')
      );
    }

    const tokenRow = result.rows[0];

    // Check if token has expired
    if (new Date(tokenRow.expires_at) < new Date()) {
      // Clean up expired token
      await pool.query(
        `DELETE FROM refresh_tokens WHERE id = $1`,
        [tokenRow.id]
      );
      return next(
        new AppError('Refresh token has expired', 401, 'INVALID_REFRESH_TOKEN')
      );
    }

    // Issue new access token
    const accessToken = jwt.sign(
      {
        sub: tokenRow.user_id,
        email: tokenRow.email,
      },
      config.jwt.privateKey,
      {
        algorithm: config.jwt.algorithm,
        expiresIn: config.jwt.accessExpiry,
      }
    );

    return res.status(200).json({
      access_token: accessToken,
    });
  } catch (err) {
    return next(err);
  }
}

/**
 * POST /api/auth/logout
 *
 * Revokes a refresh token. The access token expires naturally (15 min max).
 *
 * Headers: Authorization: Bearer <access_token>
 * Body: { refresh_token: hex string }
 * Returns: 200 { success: true }
 */
async function logout(req, res, next) {
  try {
    const { refresh_token } = req.body;

    const tokenHash = hashToken(refresh_token);

    // Delete the refresh token row — subsequent use will return 401
    await pool.query(
      `DELETE FROM refresh_tokens WHERE token_hash = $1 AND user_id = $2`,
      [tokenHash, req.user.id]
    );

    return res.status(200).json({
      success: true,
    });
  } catch (err) {
    return next(err);
  }
}

module.exports = {
  prelogin,
  register,
  login,
  refresh,
  logout,
};
