'use strict';

const { AppError } = require('./errorHandler');

/**
 * Joi schema validation middleware factory.
 *
 * Returns an Express middleware that validates req.body against the
 * provided Joi schema. On validation failure, passes an AppError
 * with status 400 and error code VALIDATION_ERROR to the error handler.
 *
 * Usage:
 *   router.post('/register', validate(registerSchema), controller.register);
 *
 * @param {import('joi').ObjectSchema} schema - Joi schema to validate against
 * @returns {import('express').RequestHandler} Express middleware
 */
function validate(schema) {
  return (req, _res, next) => {
    const { error, value } = schema.validate(req.body, {
      abortEarly: false,       // Report all errors, not just the first
      stripUnknown: true,      // Remove unknown fields from the validated object
      convert: true,           // Allow type coercion (e.g., string → number)
    });

    if (error) {
      const message = error.details
        .map((detail) => detail.message)
        .join('; ');

      return next(new AppError(message, 400, 'VALIDATION_ERROR'));
    }

    // Replace req.body with the validated (and stripped) value
    req.body = value;
    return next();
  };
}

module.exports = validate;
