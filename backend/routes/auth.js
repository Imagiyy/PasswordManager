'use strict';

const { Router } = require('express');
const Joi = require('joi');
const validate = require('../middleware/validate');
const authMiddleware = require('../middleware/authMiddleware');
const {
  authLimiter,
  preloginLimiter,
  refreshLimiter,
} = require('../middleware/rateLimiter');
const authController = require('../controllers/authController');

const router = Router();

// ── Validation Schemas ──────────────────────────────────────────────────

const preloginSchema = Joi.object({
  email: Joi.string().email().required().messages({
    'string.email': 'A valid email address is required',
    'any.required': 'Email is required',
  }),
});

const registerSchema = Joi.object({
  email: Joi.string().email().required().messages({
    'string.email': 'A valid email address is required',
    'any.required': 'Email is required',
  }),
  auth_key: Joi.string().base64().required().messages({
    'string.base64': 'auth_key must be a valid base64 string',
    'any.required': 'auth_key is required',
  }),
  kdf_salt: Joi.string().base64().required().messages({
    'string.base64': 'kdf_salt must be a valid base64 string',
    'any.required': 'kdf_salt is required',
  }),
});

const loginSchema = Joi.object({
  email: Joi.string().email().required().messages({
    'string.email': 'A valid email address is required',
    'any.required': 'Email is required',
  }),
  auth_key: Joi.string().base64().required().messages({
    'string.base64': 'auth_key must be a valid base64 string',
    'any.required': 'auth_key is required',
  }),
});

const refreshSchema = Joi.object({
  refresh_token: Joi.string().hex().length(64).required().messages({
    'string.hex': 'refresh_token must be a 64-character hex string',
    'string.length': 'refresh_token must be exactly 64 characters',
    'any.required': 'refresh_token is required',
  }),
});

const logoutSchema = Joi.object({
  refresh_token: Joi.string().hex().length(64).required().messages({
    'string.hex': 'refresh_token must be a 64-character hex string',
    'string.length': 'refresh_token must be exactly 64 characters',
    'any.required': 'refresh_token is required',
  }),
});

// ── Routes ──────────────────────────────────────────────────────────────

router.post(
  '/prelogin',
  preloginLimiter,
  validate(preloginSchema),
  authController.prelogin
);

router.post(
  '/register',
  authLimiter,
  validate(registerSchema),
  authController.register
);

router.post(
  '/login',
  authLimiter,
  validate(loginSchema),
  authController.login
);

router.post(
  '/refresh',
  refreshLimiter,
  validate(refreshSchema),
  authController.refresh
);

router.post(
  '/logout',
  authMiddleware,
  validate(logoutSchema),
  authController.logout
);

module.exports = router;
