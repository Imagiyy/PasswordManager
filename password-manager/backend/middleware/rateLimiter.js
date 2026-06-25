'use strict';

const rateLimit = require('express-rate-limit');

/**
 * Standard JSON error envelope for rate limit responses.
 *
 * @param {import('express').Request} _req
 * @param {import('express').Response} res
 */
function rateLimitHandler(_req, res) {
  res.status(429).json({
    error: 'RATE_LIMITED',
    message: 'Too many requests. Please try again later.',
  });
}

/**
 * Rate limiter for authentication endpoints (login, register).
 * 10 requests per 15 minutes per IP.
 */
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  handler: rateLimitHandler,
  keyGenerator: (req) => req.ip,
});

/**
 * Rate limiter for prelogin endpoint.
 * 20 requests per 15 minutes per IP.
 */
const preloginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 20,
  standardHeaders: true,
  legacyHeaders: false,
  handler: rateLimitHandler,
  keyGenerator: (req) => req.ip,
});

/**
 * Rate limiter for sync endpoints (push/pull).
 * 60 requests per minute per user (identified by JWT sub claim via req.user.id).
 * Falls back to IP if user is not authenticated (should not happen with authMiddleware).
 */
const syncLimiter = rateLimit({
  windowMs: 60 * 1000, // 1 minute
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  handler: rateLimitHandler,
  keyGenerator: (req) => (req.user && req.user.id) || req.ip,
});

/**
 * Rate limiter for refresh token endpoint.
 * 30 requests per hour per IP.
 */
const refreshLimiter = rateLimit({
  windowMs: 60 * 60 * 1000, // 1 hour
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  handler: rateLimitHandler,
  keyGenerator: (req) => req.ip,
});

module.exports = {
  authLimiter,
  preloginLimiter,
  syncLimiter,
  refreshLimiter,
};
