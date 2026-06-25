"""
Local SQLite storage for the Linux client.

Stores ONLY:
  - device_id: UUID v4, generated on first launch, persisted forever
  - encrypted_blob: base64(iv + ciphertext + tag), opaque cached copy
  - vector_clock: JSON string of { device_id: counter }

NEVER stores keys, master_password, or plaintext.

SQLite is configured with WAL mode for concurrent read access
and foreign keys enforcement.

Config directory: ~/.config/vaultmanager/ (chmod 700)
Files:
  device_id      — plain text UUID (chmod 600)
  vault.db       — SQLite database (chmod 600)
"""

import json
import os
import sqlite3
import stat
import uuid

# ── Config directory ─────────────────────────────────────────────────────

CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".config", "vaultmanager")
DB_PATH = os.path.join(CONFIG_DIR, "vault.db")
DEVICE_ID_PATH = os.path.join(CONFIG_DIR, "device_id")


def _ensure_config_dir() -> None:
    """
    Create the config directory if it doesn't exist, with chmod 700.
    """
    if not os.path.isdir(CONFIG_DIR):
        os.makedirs(CONFIG_DIR, mode=0o700, exist_ok=True)
    else:
        # Ensure permissions are correct even if directory already exists
        os.chmod(CONFIG_DIR, stat.S_IRWXU)


def _set_file_permissions(path: str) -> None:
    """
    Set file permissions to chmod 600 (owner read/write only).
    """
    os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)


def _get_connection() -> sqlite3.Connection:
    """
    Open a SQLite connection with WAL mode and foreign keys enabled.

    Returns:
        sqlite3.Connection configured for WAL mode.
    """
    _ensure_config_dir()

    db_exists = os.path.isfile(DB_PATH)
    conn = sqlite3.connect(DB_PATH)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")

    # Create table if this is a new database
    conn.execute("""
        CREATE TABLE IF NOT EXISTS vault_cache (
            id              INTEGER PRIMARY KEY CHECK (id = 1),
            encrypted_blob  TEXT    NOT NULL DEFAULT '',
            vector_clock    TEXT    NOT NULL DEFAULT '{}'
        )
    """)
    conn.commit()

    # Set file permissions on new database
    if not db_exists:
        _set_file_permissions(DB_PATH)

    return conn


def get_device_id() -> str:
    """
    Get or generate the device's unique identifier (UUID v4).

    On first call, generates a new UUID v4 and persists it to
    ~/.config/vaultmanager/device_id (chmod 600).
    Subsequent calls read the stored value.

    Returns:
        Device UUID string (e.g., "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    """
    _ensure_config_dir()

    if os.path.isfile(DEVICE_ID_PATH):
        with open(DEVICE_ID_PATH, "r", encoding="utf-8") as f:
            device_id = f.read().strip()
            if device_id:
                return device_id

    # Generate new device ID
    device_id = str(uuid.uuid4())

    with open(DEVICE_ID_PATH, "w", encoding="utf-8") as f:
        f.write(device_id)

    _set_file_permissions(DEVICE_ID_PATH)

    return device_id


def get_cached_vault() -> tuple:
    """
    Get the cached encrypted vault blob and vector clock from SQLite.

    Returns:
        Tuple of (encrypted_blob: str | None, vector_clock: dict).
        Returns (None, {}) if no vault is cached.
    """
    conn = _get_connection()
    try:
        cursor = conn.execute(
            "SELECT encrypted_blob, vector_clock FROM vault_cache WHERE id = 1"
        )
        row = cursor.fetchone()

        if row is None or not row[0]:
            return (None, {})

        encrypted_blob = row[0]
        vector_clock = json.loads(row[1]) if row[1] else {}

        return (encrypted_blob, vector_clock)
    finally:
        conn.close()


def save_cached_vault(blob: str, clock: dict) -> None:
    """
    Save the encrypted vault blob and vector clock to SQLite.

    Uses UPSERT (INSERT OR REPLACE) to ensure only one row exists.

    Args:
        blob:  Base64-encoded encrypted vault blob
        clock: Vector clock dict { device_id: counter }
    """
    conn = _get_connection()
    try:
        conn.execute(
            """
            INSERT INTO vault_cache (id, encrypted_blob, vector_clock)
            VALUES (1, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                encrypted_blob = excluded.encrypted_blob,
                vector_clock = excluded.vector_clock
            """,
            (blob, json.dumps(clock))
        )
        conn.commit()
    finally:
        conn.close()


def clear_cached_vault() -> None:
    """
    Clear the cached vault from SQLite.
    Removes all data from the vault_cache table.
    """
    conn = _get_connection()
    try:
        conn.execute("DELETE FROM vault_cache")
        conn.commit()
    finally:
        conn.close()
