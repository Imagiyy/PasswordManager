"""
HTTP sync client for the Linux desktop app.

Handles all communication with the backend API:
  - prelogin, register, login (authentication)
  - push, pull (vault synchronization)
  - refresh_access_token, logout (session management)

Security:
  - HTTPS enforcement: BASE_URL must start with "https://"
    (relaxed to "http://localhost" only for local development)
  - requests.Session with verify=True (default, never set False)
  - Auto-refresh on 401: retry request once with new access_token
  - Refresh token persisted to ~/.config/vaultmanager/refresh_token (chmod 600)
"""

import json
import os
import stat

import requests

# ── Configuration ────────────────────────────────────────────────────────

BASE_URL = os.environ.get("VAULT_SERVER_URL", "https://localhost:3000")

# HTTPS enforcement — relaxed for localhost development only
_is_localhost = BASE_URL.startswith("http://localhost") or BASE_URL.startswith("http://127.0.0.1")
if not BASE_URL.startswith("https://") and not _is_localhost:
    raise RuntimeError(
        f"VAULT_SERVER_URL must use HTTPS (got: {BASE_URL}). "
        "HTTP is only allowed for localhost development."
    )

CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".config", "vaultmanager")
REFRESH_TOKEN_PATH = os.path.join(CONFIG_DIR, "refresh_token")

# Request timeout (connect, read) in seconds
TIMEOUT = (10, 30)


def _ensure_config_dir() -> None:
    """Create config directory if it doesn't exist (chmod 700)."""
    if not os.path.isdir(CONFIG_DIR):
        os.makedirs(CONFIG_DIR, mode=0o700, exist_ok=True)


def _save_refresh_token(token: str) -> None:
    """
    Persist refresh token to disk (chmod 600).
    The token is an opaque hex string — not a secret key,
    but still sensitive as it grants session renewal.
    """
    _ensure_config_dir()
    with open(REFRESH_TOKEN_PATH, "w", encoding="utf-8") as f:
        f.write(token)
    os.chmod(REFRESH_TOKEN_PATH, stat.S_IRUSR | stat.S_IWUSR)


def _load_refresh_token() -> str:
    """
    Load persisted refresh token from disk.

    Returns:
        The refresh token string, or empty string if not found.
    """
    if os.path.isfile(REFRESH_TOKEN_PATH):
        with open(REFRESH_TOKEN_PATH, "r", encoding="utf-8") as f:
            return f.read().strip()
    return ""


def _clear_refresh_token() -> None:
    """Remove the persisted refresh token file."""
    if os.path.isfile(REFRESH_TOKEN_PATH):
        os.remove(REFRESH_TOKEN_PATH)


def _api_url(path: str) -> str:
    """Build full API URL from a relative path."""
    return f"{BASE_URL}{path}"


def _headers(access_token: str = None) -> dict:
    """Build request headers with optional Bearer token."""
    h = {"Content-Type": "application/json"}
    if access_token:
        h["Authorization"] = f"Bearer {access_token}"
    return h


class SyncError(Exception):
    """Raised when a sync API call fails."""

    def __init__(self, message: str, status_code: int = 0, error_code: str = ""):
        super().__init__(message)
        self.status_code = status_code
        self.error_code = error_code


def _handle_error(resp: requests.Response) -> None:
    """
    Parse error response and raise SyncError.
    Expects the standard error envelope: { error, message }.
    """
    try:
        body = resp.json()
        error_code = body.get("error", "UNKNOWN")
        message = body.get("message", resp.text)
    except (json.JSONDecodeError, ValueError):
        error_code = "UNKNOWN"
        message = resp.text or f"HTTP {resp.status_code}"

    raise SyncError(message, resp.status_code, error_code)


# ── API Methods ──────────────────────────────────────────────────────────

def prelogin(email: str) -> str:
    """
    POST /api/auth/prelogin

    Retrieve the kdf_salt for the given email so the client can
    derive auth_key before login.

    Args:
        email: User's email address

    Returns:
        Base64-encoded kdf_salt string

    Raises:
        SyncError: 404 USER_NOT_FOUND or other API error
    """
    resp = requests.post(
        _api_url("/api/auth/prelogin"),
        headers=_headers(),
        json={"email": email},
        timeout=TIMEOUT,
    )

    if resp.status_code == 200:
        return resp.json()["kdf_salt"]

    _handle_error(resp)


def register(email: str, auth_key_b64: str, kdf_salt_b64: str) -> str:
    """
    POST /api/auth/register

    Create a new account. The client generates kdf_salt and auth_key locally.

    Args:
        email:        User's email address
        auth_key_b64: Base64-encoded auth_key (32 bytes from HKDF)
        kdf_salt_b64: Base64-encoded KDF salt (16 random bytes)

    Returns:
        The new user's UUID

    Raises:
        SyncError: 409 EMAIL_EXISTS, 400 VALIDATION_ERROR, etc.
    """
    resp = requests.post(
        _api_url("/api/auth/register"),
        headers=_headers(),
        json={
            "email": email,
            "auth_key": auth_key_b64,
            "kdf_salt": kdf_salt_b64,
        },
        timeout=TIMEOUT,
    )

    if resp.status_code == 201:
        return resp.json()["user_id"]

    _handle_error(resp)


def login(email: str, auth_key_b64: str) -> tuple:
    """
    POST /api/auth/login

    Authenticate using a client-derived auth_key.

    Args:
        email:        User's email address
        auth_key_b64: Base64-encoded auth_key

    Returns:
        Tuple of (access_token: str, refresh_token: str)

    Raises:
        SyncError: 401 INVALID_CREDENTIALS, etc.
    """
    resp = requests.post(
        _api_url("/api/auth/login"),
        headers=_headers(),
        json={
            "email": email,
            "auth_key": auth_key_b64,
        },
        timeout=TIMEOUT,
    )

    if resp.status_code == 200:
        data = resp.json()
        access_token = data["access_token"]
        refresh_token = data["refresh_token"]

        # Persist refresh token for session resumption
        _save_refresh_token(refresh_token)

        return (access_token, refresh_token)

    _handle_error(resp)


def pull(access_token: str) -> tuple:
    """
    GET /api/sync/pull

    Fetch the server's current encrypted vault.

    Args:
        access_token: JWT access token

    Returns:
        Tuple of (encrypted_blob: str | None, vector_clock: dict)

    Raises:
        SyncError: 401 UNAUTHORIZED, etc.
    """
    resp = requests.get(
        _api_url("/api/sync/pull"),
        headers=_headers(access_token),
        timeout=TIMEOUT,
    )

    if resp.status_code == 200:
        data = resp.json()
        return (data.get("encrypted_blob"), data.get("vector_clock", {}))

    # Auto-refresh on 401
    if resp.status_code == 401:
        stored_rt = _load_refresh_token()
        if stored_rt:
            try:
                new_at = refresh_access_token(stored_rt)
                return pull(new_at)
            except SyncError:
                pass  # Refresh failed — fall through to error

    _handle_error(resp)


def push(access_token: str, blob: str, clock: dict):
    """
    POST /api/sync/push

    Push the client's encrypted vault after local changes.

    Args:
        access_token: JWT access token
        blob:         Base64-encoded encrypted vault blob
        clock:        Vector clock dict { device_id: counter }

    Returns:
        True if push succeeded (client dominant).
        Tuple of (server_blob: str, server_clock: dict) on 409 conflict.

    Raises:
        SyncError: 400 VALIDATION_ERROR, 401 UNAUTHORIZED, etc.
    """
    resp = requests.post(
        _api_url("/api/sync/push"),
        headers=_headers(access_token),
        json={
            "encrypted_blob": blob,
            "vector_clock": clock,
        },
        timeout=TIMEOUT,
    )

    if resp.status_code == 200:
        return True

    if resp.status_code == 409:
        data = resp.json()
        return (data["encrypted_blob"], data["vector_clock"])

    # Auto-refresh on 401
    if resp.status_code == 401:
        stored_rt = _load_refresh_token()
        if stored_rt:
            try:
                new_at = refresh_access_token(stored_rt)
                return push(new_at, blob, clock)
            except SyncError:
                pass

    _handle_error(resp)


def refresh_access_token(refresh_token: str) -> str:
    """
    POST /api/auth/refresh

    Get a new access_token using a valid refresh_token.

    Args:
        refresh_token: 64-character hex string

    Returns:
        New JWT access token

    Raises:
        SyncError: 401 INVALID_REFRESH_TOKEN, etc.
    """
    resp = requests.post(
        _api_url("/api/auth/refresh"),
        headers=_headers(),
        json={"refresh_token": refresh_token},
        timeout=TIMEOUT,
    )

    if resp.status_code == 200:
        return resp.json()["access_token"]

    # If refresh fails, clear the stored token
    if resp.status_code == 401:
        _clear_refresh_token()

    _handle_error(resp)


def logout(access_token: str, refresh_token: str) -> None:
    """
    POST /api/auth/logout

    Revoke the refresh token on the server.

    Args:
        access_token:  JWT access token
        refresh_token: 64-character hex string to revoke

    Raises:
        SyncError: On API failure (non-critical — token expires anyway)
    """
    try:
        resp = requests.post(
            _api_url("/api/auth/logout"),
            headers=_headers(access_token),
            json={"refresh_token": refresh_token},
            timeout=TIMEOUT,
        )

        if resp.status_code != 200:
            _handle_error(resp)
    finally:
        # Always clear local refresh token regardless of API result
        _clear_refresh_token()
