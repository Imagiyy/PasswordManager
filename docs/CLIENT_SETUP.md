# Manual Setup Guide — Zero-Knowledge Password Manager

Step-by-step non-Docker backend, Linux GTK4 desktop, and Android client configuration guide complementing [README.md](../README.md).

---

## 1. Manual Backend Setup (Without Docker)

Prerequisites: **Node.js 20+** and **PostgreSQL 16+**.

```bash
cd backend
npm install

# Initialize database & schema
createdb vaultdb
psql -U "$PGUSER" -d vaultdb -f migrations/001_init.sql

# Generate RS256 signing key pair
openssl genrsa -out private.pem 2048
openssl rsa -pubout -in private.pem -out public.pem

# Configure secrets & start server
cp .env.example .env
# Edit backend/.env with your DATABASE_URL & ALLOWED_ORIGINS
node server.js
```

> **Fedora / SELinux Docker Note:** `docker-compose.yml` configures `,z` volume mount labels so PostgreSQL migration volumes mount cleanly under enforcing SELinux policies.

---

## 2. Linux Desktop Client (Python + GTK4)

### System Dependencies

- **Ubuntu / Debian:**
  ```bash
  sudo apt update
  sudo apt install python3-gi python3-gi-cairo gir1.2-gtk-4.0 libgtk-4-dev
  ```
- **Fedora / RHEL:**
  ```bash
  sudo dnf install python3-gobject gtk4-devel
  ```

### Python Virtual Environment & Launch

```bash
cd linux-client
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

Launch either via the root helper scripts (`./run-client.sh`, or install a desktop launcher entry with `./install-desktop.sh`) or directly:

```bash
export VAULT_SERVER_URL="http://localhost:3000"
python3 main.py
```

---

## 3. Android Client (Kotlin + Jetpack Compose)

### Prerequisites
- Android Studio Hedgehog (`2023.1.1`) or newer
- Android SDK 34+ & NDK (required to compile `argon2kt` native libraries)

### Build & Install

```bash
cd android-client
./gradlew assembleDebug

# Install via ADB over USB debugging:
adb install app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, transfer `android-client/app/build/outputs/apk/debug/app-debug.apk` onto your device and open it in a file manager to install manually.

### Device Configuration
1. **Backend URL:** Update `BASE_URL` inside `SyncApiClient.kt` to point to your backend host/IP.
2. **Autofill:** Navigate to **Settings → System → Languages & Input → Autofill Service** and select **VaultManager**.
3. **Biometric Unlock:** Ensure device fingerprint/biometric lock is configured so Keystore can wrap/unwrap `enc_key`.
