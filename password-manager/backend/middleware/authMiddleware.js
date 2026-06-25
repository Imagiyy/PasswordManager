'use strict';

const jwt = require('jsonwebtoken');
const config = require('../configs/env');
const { AppError } = require('./errorHandler');

/**
 * JWT RS256 authentication middleware.
 *
 * Extracts the Bearer token from the Authorization header,
 * verifies it using the RS256 public key, and attaches the
 * decoded payload to req.user.
 *
 * On failure: responds with 401 UNAUTHORIZED.
 *
 * @param {import('express').Request} req
 * @param {import('express').Response} res
 * @param {import('express').NextFunction} next
 */
function authMiddleware(req, res, next) {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return next(
      new AppError('Missing or malformed Authorization header', 401, 'UNAUTHORIZED')
    );
  }

  const token = authHeader.slice(7); // Remove 'Bearer ' prefix

  try {
    const decoded = jwt.verify(token, config.jwt.publicKey, {
      algorithms: [config.jwt.algorithm],
    });

    // Attach user info to the request object
    req.user = {
      id: decoded.sub,
      email: decoded.email,
    };

    return next();
  } catch (err) {
    if (err.name === 'TokenExpiredError') {
      return next(
        new AppError('Access token has expired', 401, 'UNAUTHORIZED')
      );
    }
    if (err.name === 'JsonWebTokenError') {
      return next(
        new AppError('Invalid access token', 401, 'UNAUTHORIZED')
      );
    }
    return next(
      new AppError('Authentication failed', 401, 'UNAUTHORIZED')
    );
  }
}

module.exports = authMiddleware;
