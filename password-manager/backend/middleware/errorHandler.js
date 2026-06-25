'use strict';

const logger = require('../utils/logger');

/**
 * Custom application error for operational errors.
 * These are expected errors with a known status code and error code.
 */
class AppError extends Error {
  /**
   * @param {string} message    - Human-readable error message
   * @param {number} statusCode - HTTP status code
   * @param {string} errorCode  - Machine-readable error code (SCREAMING_SNAKE_CASE)
   */
  constructor(message, statusCode, errorCode) {
    super(message);
    this.name = 'AppError';
    this.statusCode = statusCode;
    this.errorCode = errorCode;
    this.isOperational = true;

    // Capture stack trace, excluding constructor call from it
    Error.captureStackTrace(this, this.constructor);
  }
}

/**
 * Centralized Express error handler.
 * Must be registered LAST in the middleware chain.
 * This is a 4-argument Express middleware (err, req, res, next).
 *
 * Behavior:
 *   - AppError (operational): respond with the error's status code and error code
 *   - Unexpected errors: log full stack trace via Pino, respond with 500
 *   - Never expose stack traces in production
 *   - Always returns the standard error envelope: { error, message }
 *
 * @param {Error} err
 * @param {import('express').Request} req
 * @param {import('express').Response} res
 * @param {import('express').NextFunction} _next
 */
function errorHandler(err, req, res, _next) {
  // Handle operational errors (expected, known status codes)
  if (err.isOperational) {
    logger.warn(
      { err: { message: err.message, code: err.errorCode, statusCode: err.statusCode } },
      'Operational error'
    );

    return res.status(err.statusCode).json({
      error: err.errorCode,
      message: err.message,
    });
  }

  // Handle Joi validation errors forwarded without wrapping
  if (err.isJoi) {
    logger.warn({ err: { message: err.message } }, 'Joi validation error');
    return res.status(400).json({
      error: 'VALIDATION_ERROR',
      message: err.details
        ? err.details.map((d) => d.message).join('; ')
        : err.message,
    });
  }

  // Handle JSON parse errors from express.json()
  if (err.type === 'entity.parse.failed') {
    return res.status(400).json({
      error: 'VALIDATION_ERROR',
      message: 'Invalid JSON in request body',
    });
  }

  // Handle payload too large
  if (err.type === 'entity.too.large') {
    return res.status(400).json({
      error: 'VALIDATION_ERROR',
      message: 'Request body too large (max 512kb)',
    });
  }

  // Unexpected / programmer errors — log full details, never expose to client
  logger.error(
    {
      err: {
        message: err.message,
        stack: err.stack,
        name: err.name,
      },
      req: {
        method: req.method,
        url: req.originalUrl,
        ip: req.ip,
      },
    },
    'Unexpected internal error'
  );

  const isProduction = process.env.NODE_ENV === 'production';

  return res.status(500).json({
    error: 'INTERNAL_ERROR',
    message: isProduction
      ? 'An unexpected error occurred'
      : err.message,
  });
}

module.exports = {
  AppError,
  errorHandler,
};
