# 🔐 Zero-Knowledge Password Manager

End-to-end encrypted password vault syncing across a **Linux desktop client** (Python + GTK4) and an **Android app** (Kotlin + Jetpack Compose), backed by a **Node.js / Express + PostgreSQL** server that never sees your keys or plaintext secrets.

[![Crypto](https://img.shields.io/badge/Crypto-Argon2id%20%2B%20HKDF%20%2B%20AES--256--GCM-0f766e?style=for-the-badge)](#security-model)
[![Backend](https://img.shields.io/badge/Backend-Node%2020%20%7C%20PostgreSQL%2016-2563eb?style=for-the-badge&logo=postgresql&logoColor=white)](docker-compose.yml)
[![Clients](https://img.shields.io/badge/Clients-Linux%20GTK4%20%7C%20Android%20Compose-3DDC84?style=for-the-badge)](#quickstart)
[![License](https://img.shields.io/badge/Use-Personal%20%26%20Educational-475569?style=for-the-badge)](#license--contact)

```mermaid
graph LR
    subgraph Client ["Client (Linux GTK4 / Android Compose)"]
        MP["Master Password"] -->|Argon2id| MK["master_key"]
        MK -->|"HKDF (enc_key)"| EK["enc_key (Never Leaves Device)"]
        MK -->|"HKDF (auth_key)"| AK["auth_key"]
        EK -->|AES-256-GCM| EB["encrypted_blob"]
    end
    subgraph Server ["Backend (Node.js + PostgreSQL)"]
        AK -->|"Login (once)"| AV["argon2id(auth_key)"]
        EB -->|"HTTPS Push/Pull"| DB[("Opaque Ciphertext + Vector Clock")]
    end
```

---

## ⚡ Quickstart

### 1. Start the Backend (Docker Compose — Recommended)

Prerequisites: **Docker & Docker Compose** (or Node.js 20+ & PostgreSQL 16+).

```bash
git clone https://github.com/Imagiyy/PasswordManager.git
cd PasswordManager

# Generate RS256 session token keys & configure environment
openssl genrsa -out backend/private.pem 2048
openssl rsa -pubout -in backend/private.pem -out backend/public.pem
cp backend/.env.example backend/.env

# Launch Node.js API + PostgreSQL container stack on http://localhost:3000
docker-compose up -d
```

### 2. Launch Clients

```bash
# Linux GTK4 Desktop (uses VAULT_SERVER_URL="http://localhost:3000" inside venv)
./run-client.sh
# Optional: register desktop application shortcut
./install-desktop.sh

# Android App (outputs android-client/app/build/outputs/apk/debug/app-debug.apk)
cd android-client && ./gradlew assembleDebug
```

---

## 🛡️ Security Model

One master password yields two cryptographically independent subkeys via **HKDF**: `enc_key` (encrypts/decrypts the local SQLite/Room vault via AES-256-GCM and **never leaves the client**) and `auth_key` (proves identity to the backend, where it is hashed again with Argon2id before storage).

| Server Data | Contents | Exposure Risk |
| :--- | :--- | :--- |
| `auth_verifier` | Server-side Argon2id hash of `auth_key` | Cannot derive `enc_key` even if breached |
| `kdf_salt` | 16-byte Base64 random salt | Non-secret salt |
| `encrypted_blob` | `base64(iv + ciphertext + auth_tag)` | Opaque ciphertext — server cannot decrypt |
| `vector_clock` | `{ "device-id": counter }` | Sync conflict metadata |

- **Protects against:** Server database breaches, MITM snooping of login credentials, stolen locked Android devices (`enc_key` hardware-wrapped by Android Keystore), and curious server operators.
- **Out of scope:** Root/memory inspection while unlocked, low-entropy master passwords, OS keyloggers, and compromised supply chains.

<details>
<summary>⚙️ Environment variables (.env.example), REST API endpoints, and client security controls</summary>

### Environment Variables ([`backend/.env.example`](backend/.env.example))
Server refuses to start if any required variable is missing:
- **Server & DB:** `PORT=3000`, `NODE_ENV=development`, `DATABASE_URL=postgresql://vaultuser:vaultpass@localhost:5432/vaultdb`, `ALLOWED_ORIGINS=http://localhost:3000`
- **JWT & Sessions:** `JWT_PRIVATE_KEY_PATH=./private.pem`, `JWT_PUBLIC_KEY_PATH=./public.pem`, `JWT_ACCESS_EXPIRY=900`, `REFRESH_TOKEN_EXPIRY_DAYS=30`
- **Server Auth KDF:** `ARGON2_MEMORY_COST=65536`, `ARGON2_TIME_COST=3`, `ARGON2_PARALLELISM=4`

### Backend API Endpoints
| Method | Endpoint | Auth | Purpose |
| :--- | :--- | :---: | :--- |
| `POST` | `/api/auth/prelogin` | Public | Fetch `kdf_salt` for client key derivation |
| `POST` | `/api/auth/register` | Public | Create user account |
| `POST` | `/api/auth/login` | Public | Verify `auth_key` and issue tokens |
| `POST` | `/api/auth/refresh` | Public | Rotate access token |
| `POST` | `/api/auth/logout` | JWT | Revoke refresh token |
| `GET` | `/api/sync/pull` | JWT | Fetch encrypted vault blob |
| `POST` | `/api/sync/push` | JWT | Push updated encrypted blob |
| `GET` | `/api/user/profile` | JWT | Retrieve account profile |
| `DELETE` | `/api/user/account` | JWT | Delete account (re-auth required) |

### Client Hardening Details
- **Linux (`~/.config/vaultmanager/` `0700`):** Stores `device_id`, `refresh_token`, and `vault.db` with `0600` permissions.
- **Android:** Applies `FLAG_SECURE` against screenshots/recents previews, 5-minute idle lock, `CharArray` password buffers, Android Keystore biometric key wrapping, and re-auth on process death.

</details>

> 📖 **Manual Installation & Full Platform Guides:** Step-by-step non-Docker PostgreSQL setup, Ubuntu/Fedora GTK4 system package installation, and Android Studio / ADB / Autofill setup are in [`docs/CLIENT_SETUP.md`](docs/CLIENT_SETUP.md).

---

## 📁 Repository Layout

```text
PasswordManager/
├── backend/              # Node.js / Express API, PostgreSQL migrations & Dockerfile
├── linux-client/         # Native Python + GTK4 client, local crypto & SQLite sync
├── android-client/       # Kotlin + Jetpack Compose app, Keystore & AutofillService
├── docs/CLIENT_SETUP.md  # Manual Postgres, Linux dependencies & Android setup guide
├── docker-compose.yml    # Full API + PostgreSQL stack (with Fedora :z SELinux mounts)
├── run-client.sh         # Linux client launcher inside virtualenv
└── install-desktop.sh    # Installs ~/.local/share/applications/vaultmanager.desktop
```

---

## 📄 License & Contact

Provided as-is for personal and educational use (`backend/package.json` marked `"private": true`).

- **Issues & Security Notes:** [GitHub Issues](https://github.com/Imagiyy/PasswordManager/issues)
- **Direct Contact:** [zuwiabrar@gmail.com](mailto:zuwiabrar@gmail.com)
