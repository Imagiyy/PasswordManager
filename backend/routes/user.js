'use strict';

const { Router } = require('express');
const Joi = require('joi');
const validate = require('../middleware/validate');
const authMiddleware = require('../middleware/authMiddleware');
const userController = require('../controllers/userController');

const router = Router();

// ── Validation Schemas ──────────────────────────────────────────────────

const deleteAccountSchema = Joi.object({
  auth_key: Joi.string().base64().required().messages({
    'string.base64': 'auth_key must be a valid base64 string',
    'any.required': 'auth_key is required for account deletion (re-authentication)',
  }),
});

// ── Routes ──────────────────────────────────────────────────────────────

/**
 * GET /api/user/profile
 * Returns the authenticated user's profile.
 */
router.get(
  '/profile',
  authMiddleware,
  userController.getProfile
);

/**
 * DELETE /api/user/account
 * Permanently deletes the user account. Requires re-authentication.
 */
router.delete(
  '/account',
  authMiddleware,
  validate(deleteAccountSchema),
  userController.deleteAccount
);

module.exports = router;
