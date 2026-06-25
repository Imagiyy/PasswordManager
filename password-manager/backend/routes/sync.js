'use strict';

const { Router } = require('express');
const Joi = require('joi');
const validate = require('../middleware/validate');
const authMiddleware = require('../middleware/authMiddleware');
const { syncLimiter } = require('../middleware/rateLimiter');
const syncController = require('../controllers/syncController');

const router = Router();

// ── Validation Schemas ──────────────────────────────────────────────────

const pushSchema = Joi.object({
  encrypted_blob: Joi.string().required().messages({
    'any.required': 'encrypted_blob is required',
    'string.empty': 'encrypted_blob cannot be empty',
  }),
  vector_clock: Joi.object().pattern(
    Joi.string(),       // keys: device_id strings
    Joi.number().integer().min(0)  // values: non-negative integer counters
  ).required().messages({
    'any.required': 'vector_clock is required',
    'object.base': 'vector_clock must be an object',
  }),
});

// ── Routes ──────────────────────────────────────────────────────────────

/**
 * GET /api/sync/pull
 * Fetch the server's current encrypted vault.
 */
router.get(
  '/pull',
  authMiddleware,
  syncLimiter,
  syncController.pull
);

/**
 * POST /api/sync/push
 * Push the client's encrypted vault after local changes.
 */
router.post(
  '/push',
  authMiddleware,
  syncLimiter,
  validate(pushSchema),
  syncController.push
);

module.exports = router;
