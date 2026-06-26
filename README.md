# Zero-Knowledge Password Manager

A production-ready, zero-knowledge password manager with end-to-end encryption.
Syncs between a **Linux desktop** (Python + GTK4) and an **Android** app
(Kotlin + Jetpack Compose), backed by a **Node.js/Express** API with PostgreSQL.

The server **never** sees your passwords. All encryption and decryption happens
exclusively on your devices.

---

## 1. Architecture Overview

```mermaid
graph TB
    subgraph Client ["Client (Linux / Android)"]
        MP[Master Password]
        MP -->|Argon2id| MK[master_key]
        MK -->|HKDF: info=enc_key| EK[enc_key]
        MK -->|HKDF: info=auth_key| AK[auth_key]
        EK -->|AES-256-GCM| EB[encrypted_blob]
    end

    subgraph SERVER ["Server (Node.js + PostgreSQL)"]
        AK2[auth_key received] -->|argon2id.hash| AV[auth_verifier stored]
        EB2[encrypted_blob stored] --- OPAQUE[Opaque to server]
        VC[vector_clock JSONB] --- OPAQUE
        KS[kdf_salt stored] --- NONSECRET[Non-secret]
    end

    AK -->|Sent once per login| AK2
    EB -->|HTTPS Push/Pull| EB2
```

### HKDF Key Separation

One master password produces **two cryptographically independent keys** via HKDF:

| Key | Purpose | Leaves Client? | Stored on Server? |
|-----|---------|----------------|-------------------|
| `master_key` | Intermediate — input to HKDF | ❌ Never | ❌ Never |
| `enc_key` | Encrypts/decrypts the vault blob | ❌ Never | ❌ Never |
| `auth_key` | Proves identity to the server | ✅ Once per login | ❌ Only as argon2id hash |

Even if `auth_key` leaks from the server, `enc_key` **cannot** be derived from it.
HKDF outputs with different `info` values are cryptographically independent.

---

## 2. Security Model

### What the server stores

| Field | Content | Secret? |
|-------|---------|---------|
| `auth_verifier` | argon2id hash of `auth_key` | Server-side hash |
| `kdf_salt` | 16 random bytes (base64) | Non-secret (like a bcrypt salt) |
| `encrypted_blob` | `base64(iv + ciphertext + tag)` | Opaque — server cannot decrypt |
| `vector_clock` | `{ "device-id": counter }` | Opaque metadata |

### What the server CANNOT see

- Master password
- `master_key`, `enc_key`, `auth_key` in plaintext
- Any vault item (names, usernames, passwords, URLs, notes)

### Threat Model

**Protects against:**

- **Server breach**: Attacker gets `auth_verifier` (argon2id hash) and
  `encrypted_blob`. Cannot derive `enc_key` from `auth_key` hash.
  Cannot decrypt vault without `enc_key`.
- **Network interception (MITM)**: All traffic over HTTPS. `auth_key` is
  a derived value, not the master password. Even if intercepted, it cannot
  produce `enc_key`.
- **Stolen device without biometric** (Android): `enc_key` is wrapped by
  Android Keystore. Without biometric authentication, the wrapped key
  cannot be unwrapped.
- **Rogue server operator**: Cannot read vault contents. Cannot derive
  encryption keys from stored data.

**Does NOT protect against:**

- **Compromised client device with memory access**: If an attacker has root
  access to a device while the vault is unlocked, they can read `enc_key`
  from process memory.
- **Weak master password**: Argon2id slows brute-force but cannot prevent
  it if the password has low entropy. Use a strong, unique master password.
- **Keylogger on the client**: Master password captured at input time.
- **Supply-chain attacks**: Compromised dependencies could exfiltrate keys.

---

## 3. Backend Setup

### Prerequisites

- Docker & Docker Compose (recommended), OR
- Node.js 20+, PostgreSQL 16+

### Option A: Docker (Recommended)

To run the backend server and PostgreSQL database in Docker containers:

1. **Generate RS256 key pair** (used for signing session tokens):
   ```bash
   openssl genrsa -out backend/private.pem 2048
   openssl rsa -pubout -in backend/private.pem -out backend/public.pem
   ```

2. **Configure environment variables:**
   ```bash
   cp backend/.env.example backend/.env
   # Edit backend/.env if you want to customize ports/passwords
   ```

3. **Start the services:**
   * **Fedora/Linux (SELinux):** If you are running on Fedora or CentOS with SELinux active, Docker requires volume mounts to have the `,z` flag. This is already configured in `docker-compose.yml` for the database schema migrations.
   * Run the compose stack:
     ```bash
     docker-compose up -d
     ```
     *(Or `sudo docker-compose up -d` if your user is not yet added to the `docker` group).*

4. **Verify backend is running:**
   Visit `http://localhost:3000` (which will return a standard Helmet-protected 404 response indicating it is up and listening).

### Option B: Manual Setup (No Docker)

```bash
cd backend

# Install dependencies
npm install

# Set up PostgreSQL database
createdb vaultdb

# Run migration
psql -U $PGUSER -d vaultdb -f migrations/001_init.sql

# Generate RS256 key pair
openssl genrsa -out private.pem 2048
openssl rsa -pubout -in private.pem -out public.pem

# Configure environment
cp .env.example .env
# Edit .env with your database URL and settings

# Start server
node server.js
```

---

## 4. Linux Client Setup

The Linux client is a native desktop application built with Python and GTK4.

### System Dependencies

First, install the GTK4 system dependencies for your distribution:

* **Ubuntu / Debian:**
  ```bash
  sudo apt update
  sudo apt install python3-gi python3-gi-cairo gir1.2-gtk-4.0 libgtk-4-dev
  ```
* **Fedora / RHEL:**
  ```bash
  sudo dnf install python3-gobject gtk4-devel
  ```

### Python Environment & Installation

Set up a local Python virtual environment and install the requirements:

```bash
cd linux-client
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### Launching & Installing the Application

You can launch the application in two ways:

#### Option 1: Run and Install as a Desktop Application (Recommended)
We have provided helper scripts in the root directory to make launching the app convenient:

1. **Run-Only Script:** Run `./run-client.sh` from the project root. It handles virtual environment activation, environment variable setting, and app execution automatically.
2. **Desktop Launcher (Icon) Installation:** Run the desktop installer script:
   ```bash
   ./install-desktop.sh
   ```
   This creates a standard desktop entry (`~/.local/share/applications/vaultmanager.desktop`) with a secure lock icon. You can now press your keyboard's **Super/Windows** key, search for **VaultManager**, and click on the app icon to open it!

#### Option 2: Command-Line Launch
Run directly from the terminal (inside your active python environment):
```bash
export VAULT_SERVER_URL="http://localhost:3000"
python3 main.py
```

### Configuration Directory

The app creates `~/.config/vaultmanager/` on first launch (chmod 700) with:
- `device_id` — UUID v4, unique to this device (chmod 600)
- `refresh_token` — opaque token for session persistence (chmod 600)
- `vault.db` — SQLite database with cached encrypted vault (chmod 600)

---

## 5. Android Client Setup

The Android client is built using Kotlin and Jetpack Compose. You can build the installation file (`.apk`) and load it onto your device.

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34+
- NDK (for native argon2kt compilation)

### Building the Installation Package (APK)

To compile the application:

1. Navigate to the android client directory:
   ```bash
   cd android-client
   ```
2. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   This will generate a ready-to-install package at:
   `android-client/app/build/outputs/apk/debug/app-debug.apk`

### Installing on your Device

To get the app working on your phone with its own clickable app icon:

#### Method A: Via USB Debugging (Developer Mode)
If you have your phone connected to your PC with USB debugging enabled, run:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Method B: Manual File Transfer (Standard)
1. Transfer the compiled `app-debug.apk` to your phone (via USB transfer, Google Drive, email, or a local network share).
2. Open a file manager app on your Android device and tap `app-debug.apk`.
3. Allow installing apps from unknown/external sources if prompted by your system.
4. Tap **Install**. Once finished, the VaultManager app will be available in your home screen and app drawer!

### Configuration

1. **Set server URL**: Update the `BASE_URL` constant inside `SyncApiClient.kt` to point to your backend API.
2. **Enable Autofill**: Go to Settings → System → Languages & Input → Autofill Service, and select "VaultManager".
3. **Biometric Setup**: Ensure biometric unlock is enabled in your device settings.

### Security Features

- `FLAG_SECURE` on all windows (no screenshots, no app-switcher preview)
- 5-minute idle auto-lock
- Passwords handled as `CharArray` (never `String`)
- `enc_key` wrapped by Android Keystore AES key
- Biometric unlock via Keystore biometric-bound key
- Process death = re-authentication required

---

## 6. API Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/auth/prelogin` | ❌ | Get `kdf_salt` for key derivation |
| POST | `/api/auth/register` | ❌ | Create account |
| POST | `/api/auth/login` | ❌ | Authenticate, get tokens |
| POST | `/api/auth/refresh` | ❌ | Refresh access token |
| POST | `/api/auth/logout` | ✅ | Revoke refresh token |
| GET | `/api/sync/pull` | ✅ | Fetch encrypted vault |
| POST | `/api/sync/push` | ✅ | Push encrypted vault |
| GET | `/api/user/profile` | ✅ | Get user profile |
| DELETE | `/api/user/account` | ✅ | Delete account (re-auth required) |

All errors use the standard envelope: `{ "error": "CODE", "message": "..." }`

---

## License

This project is provided as-is for educational and personal use.
