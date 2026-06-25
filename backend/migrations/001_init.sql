-- Run: psql -U $PGUSER -d $PGDATABASE -f migrations/001_init.sql

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
  id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  email         TEXT        UNIQUE NOT NULL,
  auth_verifier TEXT        NOT NULL,   -- argon2id hash of client-derived auth_key
                                        -- (argon2 embeds its own salt in this string)
  kdf_salt      TEXT        NOT NULL,   -- base64(16 random bytes); non-secret KDF input
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE vaults (
  id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  encrypted_blob TEXT        NOT NULL,       -- base64(iv[12] + ciphertext + tag[16])
  vector_clock   JSONB       NOT NULL DEFAULT '{}',
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Enforce exactly one vault row per user
CREATE UNIQUE INDEX idx_vaults_user_id ON vaults(user_id);

CREATE TABLE refresh_tokens (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  TEXT        NOT NULL UNIQUE,  -- SHA-256(opaque token) — raw token only on wire
  expires_at  TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

-- Auto-clean expired refresh tokens (run as a periodic job or cron)
-- DELETE FROM refresh_tokens WHERE expires_at < now();
