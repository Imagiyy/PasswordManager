"""
Login/Registration dialog for the Linux client.

Provides:
  - Email + master password entry fields
  - Login / Register mode toggle
  - Confirm password field (shown only in register mode)
  - Calls vault_crypto.derive_keys → sync_client.login/register

All key material is zeroed in finally blocks.
"""

import base64
import threading

import gi
gi.require_version("Gtk", "4.0")
from gi.repository import Gtk, GLib  # noqa: E402

from crypto.vault_crypto import derive_keys, generate_kdf_salt, zero_bytes
from sync.sync_client import (
    prelogin,
    register as api_register,
    login as api_login,
    SyncError,
)


class LoginDialog(Gtk.Dialog):
    """
    GTK4 dialog for user login and registration.

    Emits no signals — instead, calls the on_success callback with
    (email, enc_key, auth_key, access_token, refresh_token) on successful auth.
    """

    def __init__(self, parent: Gtk.Window, on_success: callable):
        """
        Args:
            parent:     Parent GTK4 window
            on_success: Callback(email, enc_key, access_token, refresh_token)
                        enc_key is a bytearray — caller must zero it when done.
        """
        super().__init__(
            title="VaultManager — Login",
            transient_for=parent,
            modal=True,
        )
        self._on_success = on_success
        self._is_register_mode = False

        self.set_default_size(420, 320)
        self.set_resizable(False)

        content = self.get_content_area()
        content.set_spacing(12)
        content.set_margin_top(24)
        content.set_margin_bottom(24)
        content.set_margin_start(24)
        content.set_margin_end(24)

        # ── Title ────────────────────────────────────────────────────
        title_label = Gtk.Label(label="VaultManager")
        title_label.add_css_class("dialog-title")
        content.append(title_label)

        # ── Email ────────────────────────────────────────────────────
        email_label = Gtk.Label(label="Email", xalign=0)
        content.append(email_label)

        self._email_entry = Gtk.Entry()
        self._email_entry.set_placeholder_text("you@example.com")
        self._email_entry.set_input_purpose(Gtk.InputPurpose.EMAIL)
        content.append(self._email_entry)

        # ── Password ────────────────────────────────────────────────
        pw_label = Gtk.Label(label="Master Password", xalign=0)
        content.append(pw_label)

        self._password_entry = Gtk.PasswordEntry()
        self._password_entry.set_show_peek_icon(True)
        content.append(self._password_entry)

        # ── Confirm Password (register mode only) ───────────────────
        self._confirm_label = Gtk.Label(label="Confirm Password", xalign=0)
        self._confirm_label.set_visible(False)
        content.append(self._confirm_label)

        self._confirm_entry = Gtk.PasswordEntry()
        self._confirm_entry.set_show_peek_icon(True)
        self._confirm_entry.set_visible(False)
        content.append(self._confirm_entry)

        # ── Error Label ─────────────────────────────────────────────
        self._error_label = Gtk.Label(label="")
        self._error_label.add_css_class("sync-status-error")
        self._error_label.set_visible(False)
        content.append(self._error_label)

        # ── Spinner ─────────────────────────────────────────────────
        self._spinner = Gtk.Spinner()
        self._spinner.set_visible(False)
        content.append(self._spinner)

        # ── Buttons ─────────────────────────────────────────────────
        button_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        button_box.set_halign(Gtk.Align.END)

        self._toggle_btn = Gtk.Button(label="Switch to Register")
        self._toggle_btn.connect("clicked", self._on_toggle_mode)
        button_box.append(self._toggle_btn)

        self._action_btn = Gtk.Button(label="Login")
        self._action_btn.add_css_class("suggested-action")
        self._action_btn.connect("clicked", self._on_action)
        button_box.append(self._action_btn)

        content.append(button_box)

        # Allow Enter key to submit
        self._password_entry.connect("activate", self._on_action)
        self._confirm_entry.connect("activate", self._on_action)

    def _on_toggle_mode(self, _btn: Gtk.Button) -> None:
        """Toggle between Login and Register modes."""
        self._is_register_mode = not self._is_register_mode

        self._confirm_label.set_visible(self._is_register_mode)
        self._confirm_entry.set_visible(self._is_register_mode)
        self._error_label.set_visible(False)

        if self._is_register_mode:
            self._action_btn.set_label("Register")
            self._toggle_btn.set_label("Switch to Login")
            self.set_title("VaultManager — Register")
        else:
            self._action_btn.set_label("Login")
            self._toggle_btn.set_label("Switch to Register")
            self.set_title("VaultManager — Login")

    def _show_error(self, message: str) -> None:
        """Display an error message in the dialog."""
        self._error_label.set_label(message)
        self._error_label.set_visible(True)

    def _set_loading(self, loading: bool) -> None:
        """Show/hide spinner and disable/enable inputs."""
        self._spinner.set_visible(loading)
        if loading:
            self._spinner.start()
        else:
            self._spinner.stop()
        self._action_btn.set_sensitive(not loading)
        self._toggle_btn.set_sensitive(not loading)
        self._email_entry.set_sensitive(not loading)
        self._password_entry.set_sensitive(not loading)
        self._confirm_entry.set_sensitive(not loading)

    def _on_action(self, *_args) -> None:
        """Handle Login or Register button click."""
        email = self._email_entry.get_text().strip()
        password = self._password_entry.get_text()

        if not email:
            self._show_error("Email is required")
            return
        if not password:
            self._show_error("Master password is required")
            return

        if self._is_register_mode:
            confirm = self._confirm_entry.get_text()
            if password != confirm:
                self._show_error("Passwords do not match")
                return
            if len(password) < 8:
                self._show_error("Password must be at least 8 characters")
                return

        self._error_label.set_visible(False)
        self._set_loading(True)

        # Run auth in background thread to avoid blocking GTK main loop
        if self._is_register_mode:
            thread = threading.Thread(
                target=self._do_register,
                args=(email, password),
                daemon=True,
            )
        else:
            thread = threading.Thread(
                target=self._do_login,
                args=(email, password),
                daemon=True,
            )
        thread.start()

    def _do_register(self, email: str, password: str) -> None:
        """Background thread: register flow."""
        enc_key = None
        auth_key = None

        try:
            # Step 1: Generate KDF salt
            kdf_salt_b64 = generate_kdf_salt()

            # Step 2: Derive keys from password + salt
            enc_key, auth_key = derive_keys(password, kdf_salt_b64)

            # Step 3: Send auth_key + kdf_salt to server
            auth_key_b64 = base64.b64encode(bytes(auth_key)).decode("ascii")
            api_register(email, auth_key_b64, kdf_salt_b64)

            # Step 4: Login to get tokens
            access_token, refresh_token = api_login(email, auth_key_b64)

            # Step 5: Success — call back on main thread
            # Transfer enc_key ownership to callback (do NOT zero here)
            GLib.idle_add(
                self._on_auth_success,
                email, enc_key, access_token, refresh_token,
            )
            enc_key = None  # Prevent finally from zeroing transferred key

        except SyncError as e:
            GLib.idle_add(self._on_auth_error, str(e))
        except Exception as e:
            GLib.idle_add(self._on_auth_error, f"Registration failed: {e}")
        finally:
            # Zero any keys that weren't transferred
            if enc_key is not None:
                zero_bytes(enc_key)
            if auth_key is not None:
                zero_bytes(auth_key)

    def _do_login(self, email: str, password: str) -> None:
        """Background thread: login flow."""
        enc_key = None
        auth_key = None

        try:
            # Step 1: Prelogin — get kdf_salt
            kdf_salt_b64 = prelogin(email)

            # Step 2: Derive keys from password + salt
            enc_key, auth_key = derive_keys(password, kdf_salt_b64)

            # Step 3: Login with auth_key
            auth_key_b64 = base64.b64encode(bytes(auth_key)).decode("ascii")
            access_token, refresh_token = api_login(email, auth_key_b64)

            # Step 4: Success — call back on main thread
            GLib.idle_add(
                self._on_auth_success,
                email, enc_key, access_token, refresh_token,
            )
            enc_key = None  # Prevent finally from zeroing transferred key

        except SyncError as e:
            GLib.idle_add(self._on_auth_error, str(e))
        except Exception as e:
            GLib.idle_add(self._on_auth_error, f"Login failed: {e}")
        finally:
            if enc_key is not None:
                zero_bytes(enc_key)
            if auth_key is not None:
                zero_bytes(auth_key)

    def _on_auth_success(self, email, enc_key, access_token, refresh_token):
        """Main thread: handle successful authentication."""
        self._set_loading(False)
        self._on_success(email, enc_key, access_token, refresh_token)
        self.close()
        return False  # Remove from GLib idle

    def _on_auth_error(self, message: str):
        """Main thread: handle authentication error."""
        self._set_loading(False)
        self._show_error(message)
        return False  # Remove from GLib idle
