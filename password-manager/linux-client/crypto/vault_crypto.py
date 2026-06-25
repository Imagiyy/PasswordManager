"""
Zero-knowledge vault cryptography for the Linux client.

Key derivation: Argon2id → HKDF-SHA256 (two-key design)
Encryption:     AES-256-GCM with 12-byte IV and 128-bit auth tag

ALL key material is stored as bytearray (mutable, zeroable).
NEVER store keys as bytes (immutable, cannot be zeroed).

Argon2id parameters (MUST match across all clients):
  m = 65536 KiB (64 MiB)
  t = 3 iterations
  p = 4 parallelism
  hash_len = 32 bytes
"""

import base64
import secrets

import argon2.low_level
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# ── Constants (must match across all clients) ────────────────────────────

ARGON2_MEMORY_COST = 65536   # KiB (64 MiB)
ARGON2_TIME_COST = 3
ARGON2_PARALLELISM = 4
ARGON2_HASH_LEN = 32         # bytes
ARGON2_TYPE = argon2.low_level.Type.ID  # Argon2id

HKDF_ENC_KEY_INFO = b"enc_key"
HKDF_AUTH_KEY_INFO = b"auth_key"
HKDF_KEY_LEN = 32  # bytes

AES_IV_LEN = 12    # bytes — GCM standard nonce size
AES_TAG_LEN = 16   # bytes — 128-bit auth tag
KDF_SALT_LEN = 16  # bytes


def zero_bytes(b: bytearray) -> None:
    """
    Zero every byte of a bytearray in-place.

    This is a best-effort memory sanitization. Python's garbage collector
    may have already copied data, but zeroing reduces the window of exposure.

    Args:
        b: The bytearray to zero. Must be a bytearray, not bytes.
    """
    for i in range(len(b)):
        b[i] = 0


def derive_keys(password: str, kdf_salt_b64: str) -> tuple:
    """
    Derive enc_key and auth_key from a master password and KDF salt.

    Steps:
      1. Argon2id(password, kdf_salt) → master_key (32 bytes)
      2. HKDF-SHA256(master_key, info=b"enc_key") → enc_key (32 bytes)
      3. HKDF-SHA256(master_key, info=b"auth_key") → auth_key (32 bytes)
      4. Zero master_key immediately — it is never needed after HKDF

    Args:
        password:     The user's master password (UTF-8 string)
        kdf_salt_b64: Base64-encoded 16-byte KDF salt from the server

    Returns:
        Tuple of (enc_key: bytearray, auth_key: bytearray).
        CALLER MUST call zero_bytes() on both when done.
    """
    kdf_salt = base64.b64decode(kdf_salt_b64)
    master_key = bytearray(32)

    try:
        # Step 1: Argon2id — derive master_key from password + salt
        raw_hash = argon2.low_level.hash_secret_raw(
            secret=password.encode('utf-8'),
            salt=kdf_salt,
            time_cost=ARGON2_TIME_COST,
            memory_cost=ARGON2_MEMORY_COST,
            parallelism=ARGON2_PARALLELISM,
            hash_len=ARGON2_HASH_LEN,
            type=ARGON2_TYPE,
        )
        master_key = bytearray(raw_hash)

        # Step 2: HKDF-SHA256 — derive enc_key
        hkdf_enc = HKDF(
            algorithm=hashes.SHA256(),
            length=HKDF_KEY_LEN,
            salt=b"",  # empty salt per spec
            info=HKDF_ENC_KEY_INFO,
        )
        enc_key = bytearray(hkdf_enc.derive(bytes(master_key)))

        # Step 3: HKDF-SHA256 — derive auth_key
        hkdf_auth = HKDF(
            algorithm=hashes.SHA256(),
            length=HKDF_KEY_LEN,
            salt=b"",  # empty salt per spec
            info=HKDF_AUTH_KEY_INFO,
        )
        auth_key = bytearray(hkdf_auth.derive(bytes(master_key)))

        return (enc_key, auth_key)

    finally:
        # Always zero master_key — it must never persist in memory
        zero_bytes(master_key)


def encrypt(enc_key: bytearray, plaintext: bytes) -> str:
    """
    Encrypt plaintext using AES-256-GCM.

    Generates a fresh 12-byte IV for every call (NEVER reuse an IV).
    Returns base64(iv[12] + ciphertext[N] + tag[16]).

    Args:
        enc_key:   32-byte encryption key (bytearray)
        plaintext: Data to encrypt (bytes)

    Returns:
        Base64-encoded string: iv[12] || ciphertext[N] || tag[16]
    """
    # Generate a fresh random IV — NEVER reuse with the same key
    iv = secrets.token_bytes(AES_IV_LEN)

    aesgcm = AESGCM(bytes(enc_key))

    # AESGCM.encrypt returns ciphertext || tag (tag is always 16 bytes)
    ciphertext_and_tag = aesgcm.encrypt(iv, plaintext, None)

    # Wire format: iv[12] + ciphertext[N] + tag[16]
    blob = iv + ciphertext_and_tag

    return base64.b64encode(blob).decode('ascii')


def decrypt(enc_key: bytearray, blob_b64: str) -> bytes:
    """
    Decrypt an AES-256-GCM encrypted blob.

    Parses base64(iv[12] + ciphertext[N] + tag[16]).
    ALWAYS verifies the auth tag before returning any plaintext.
    Raises InvalidTag on auth tag failure — data is corrupted or tampered.

    Args:
        enc_key:  32-byte encryption key (bytearray)
        blob_b64: Base64-encoded blob from encrypt()

    Returns:
        Decrypted plaintext bytes.

    Raises:
        cryptography.exceptions.InvalidTag: Auth tag verification failed.
        ValueError: Blob is too short to contain IV + tag.
    """
    blob = base64.b64decode(blob_b64)

    if len(blob) < AES_IV_LEN + AES_TAG_LEN:
        raise ValueError(
            f"Encrypted blob too short: {len(blob)} bytes "
            f"(minimum {AES_IV_LEN + AES_TAG_LEN} for IV + tag)"
        )

    # Split: iv[12] + ciphertext_and_tag[N+16]
    iv = blob[:AES_IV_LEN]
    ciphertext_and_tag = blob[AES_IV_LEN:]

    aesgcm = AESGCM(bytes(enc_key))

    # decrypt() verifies the auth tag and raises InvalidTag on failure
    plaintext = aesgcm.decrypt(iv, ciphertext_and_tag, None)

    return plaintext


def generate_kdf_salt() -> str:
    """
    Generate a 16-byte KDF salt using CSPRNG and return it as base64.

    This salt is generated once at registration time and sent to the server.
    It is non-secret (like a bcrypt salt) but must be unique per user.

    Returns:
        Base64-encoded string (24 characters for 16 bytes)
    """
    salt_bytes = secrets.token_bytes(KDF_SALT_LEN)
    return base64.b64encode(salt_bytes).decode('ascii')
