"""
GTK4 main window for the VaultManager Linux client.

Features:
  - Two-column vault list: Name | Username, with SearchEntry filter
  - Toolbar: Add, Edit, Delete, Sync Now, Lock
  - Sync status bar: "Synced 2 min ago" or "Sync failed: <reason>"
  - Idle timer: 5-minute auto-lock via GLib.timeout_add_seconds
  - Conflict merge dialog on 409 sync conflict

Security:
  - On lock: zero_bytes(enc_key), clear vault list, show login_dialog
  - enc_key is never written to disk
"""

import base64
import json
import threading
from datetime import datetime, timezone

import gi
gi.require_version("Gtk", "4.0")
from gi.repository import Gtk, GLib, Gdk, Gio, GObject  # noqa: E402

from crypto.vault_crypto import encrypt, decrypt, zero_bytes
from storage.local_db import (
    get_device_id,
    get_cached_vault,
    save_cached_vault,
    clear_cached_vault,
)
from sync.sync_client import pull, push, SyncError
from ui.item_dialog import VaultItem, ItemDialog, DeleteConfirmDialog
from ui.login_dialog import LoginDialog

# Idle timeout: 5 minutes (300 seconds)
IDLE_TIMEOUT_SECONDS = 300

# Vault JSON version
VAULT_VERSION = 1


class MainWindow(Gtk.ApplicationWindow):
    """
    Main application window with vault list, toolbar, and sync status.
    """

    def __init__(self, app: Gtk.Application):
        super().__init__(application=app, title="VaultManager")
        self.set_default_size(800, 600)

        # ── State ────────────────────────────────────────────────────
        self._enc_key = None        # bytearray — zeroed on lock
        self._access_token = None   # str
        self._refresh_token = None  # str
        self._email = None          # str
        self._vault_items = []      # list[VaultItem]
        self._vector_clock = {}     # dict
        self._device_id = get_device_id()
        self._idle_timer_id = None
        self._last_sync_time = None
        self._sync_error = None

        # ── Layout ───────────────────────────────────────────────────
        main_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.set_child(main_box)

        # ── Toolbar ──────────────────────────────────────────────────
        toolbar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        toolbar.add_css_class("toolbar")
        toolbar.set_margin_start(8)
        toolbar.set_margin_end(8)
        toolbar.set_margin_top(4)
        toolbar.set_margin_bottom(4)

        self._add_btn = Gtk.Button(label="Add")
        self._add_btn.set_icon_name("list-add-symbolic")
        self._add_btn.set_tooltip_text("Add new credential")
        self._add_btn.connect("clicked", self._on_add)
        toolbar.append(self._add_btn)

        self._edit_btn = Gtk.Button(label="Edit")
        self._edit_btn.set_icon_name("document-edit-symbolic")
        self._edit_btn.set_tooltip_text("Edit selected credential")
        self._edit_btn.connect("clicked", self._on_edit)
        self._edit_btn.set_sensitive(False)
        toolbar.append(self._edit_btn)

        self._delete_btn = Gtk.Button(label="Delete")
        self._delete_btn.set_icon_name("edit-delete-symbolic")
        self._delete_btn.set_tooltip_text("Delete selected credential")
        self._delete_btn.connect("clicked", self._on_delete)
        self._delete_btn.set_sensitive(False)
        toolbar.append(self._delete_btn)

        # Spacer
        spacer = Gtk.Box()
        spacer.set_hexpand(True)
        toolbar.append(spacer)

        self._sync_btn = Gtk.Button(label="Sync Now")
        self._sync_btn.set_icon_name("emblem-synchronizing-symbolic")
        self._sync_btn.set_tooltip_text("Sync vault with server")
        self._sync_btn.connect("clicked", self._on_sync_now)
        toolbar.append(self._sync_btn)

        self._lock_btn = Gtk.Button(label="Lock")
        self._lock_btn.set_icon_name("system-lock-screen-symbolic")
        self._lock_btn.set_tooltip_text("Lock vault")
        self._lock_btn.connect("clicked", self._on_lock)
        toolbar.append(self._lock_btn)

        main_box.append(toolbar)

        # ── Search ───────────────────────────────────────────────────
        self._search_entry = Gtk.SearchEntry()
        self._search_entry.set_placeholder_text("Search vault...")
        self._search_entry.set_margin_start(8)
        self._search_entry.set_margin_end(8)
        self._search_entry.set_margin_top(4)
        self._search_entry.set_margin_bottom(4)
        self._search_entry.connect("search-changed", self._on_search_changed)
        main_box.append(self._search_entry)

        # ── Vault List ───────────────────────────────────────────────
        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        scroll.set_hexpand(True)

        self._list_store = Gio.ListStore(item_type=VaultListItem)
        self._selection_model = Gtk.SingleSelection(model=self._list_store)
        self._selection_model.connect("selection-changed", self._on_selection_changed)

        factory = Gtk.SignalListItemFactory()
        factory.connect("setup", self._on_factory_setup)
        factory.connect("bind", self._on_factory_bind)

        self._list_view = Gtk.ListView(model=self._selection_model, factory=factory)
        scroll.set_child(self._list_view)
        main_box.append(scroll)

        # ── Status Bar ───────────────────────────────────────────────
        self._status_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        self._status_bar.add_css_class("statusbar")

        self._status_label = Gtk.Label(label="Locked")
        self._status_label.add_css_class("sync-status")
        self._status_label.set_halign(Gtk.Align.START)
        self._status_label.set_hexpand(True)
        self._status_bar.append(self._status_label)

        self._item_count_label = Gtk.Label(label="")
        self._item_count_label.add_css_class("sync-status")
        self._item_count_label.set_halign(Gtk.Align.END)
        self._status_bar.append(self._item_count_label)

        main_box.append(self._status_bar)

        # ── Idle Timer Events ────────────────────────────────────────
        # GTK4 uses event controllers instead of signal connections
        key_controller = Gtk.EventControllerKey()
        key_controller.connect("key-pressed", self._on_key_event)
        self.add_controller(key_controller)

        motion_controller = Gtk.EventControllerMotion()
        motion_controller.connect("motion", self._on_motion_event)
        self.add_controller(motion_controller)

        click_controller = Gtk.GestureClick()
        click_controller.connect("pressed", self._on_click_event)
        self.add_controller(click_controller)

        # ── Initial State: Show Login ────────────────────────────────
        self._set_locked_ui(True)

        # Show login dialog after window is presented
        GLib.idle_add(self._show_login)

    # ── Factory Methods (ListView) ───────────────────────────────────

    def _on_factory_setup(self, _factory, list_item):
        """Create the widget for a list row."""
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        box.set_margin_top(8)
        box.set_margin_bottom(8)
        box.set_margin_start(12)
        box.set_margin_end(12)

        name_label = Gtk.Label(xalign=0)
        name_label.add_css_class("vault-item-name")
        box.append(name_label)

        username_label = Gtk.Label(xalign=0)
        username_label.add_css_class("vault-item-username")
        box.append(username_label)

        list_item.set_child(box)

    def _on_factory_bind(self, _factory, list_item):
        """Bind data to a list row widget."""
        item = list_item.get_item()
        box = list_item.get_child()

        name_label = box.get_first_child()
        username_label = name_label.get_next_sibling()

        name_label.set_label(item.name)
        username_label.set_label(item.username)

    def _on_selection_changed(self, _model, _position, _n_items):
        """Enable/disable edit/delete buttons based on selection."""
        has_selection = self._selection_model.get_selected_item() is not None
        self._edit_btn.set_sensitive(has_selection)
        self._delete_btn.set_sensitive(has_selection)

    # ── Search ───────────────────────────────────────────────────────

    def _on_search_changed(self, search_entry):
        """Filter vault list based on search text."""
        self._reset_idle_timer()
        query = search_entry.get_text().strip().lower()
        self._refresh_list(query)

    # ── Vault Data Management ────────────────────────────────────────

    def _get_vault_json(self) -> dict:
        """Build the vault JSON structure from current items."""
        return {
            "version": VAULT_VERSION,
            "items": [item.to_dict() for item in self._vault_items],
        }

    def _load_vault_from_blob(self, blob: str) -> None:
        """Decrypt an encrypted blob and load items into the vault list."""
        if not blob or not self._enc_key:
            self._vault_items = []
            self._refresh_list()
            return

        plaintext = decrypt(self._enc_key, blob)
        vault_data = json.loads(plaintext.decode("utf-8"))
        self._vault_items = [
            VaultItem.from_dict(item) for item in vault_data.get("items", [])
        ]
        self._refresh_list()

    def _encrypt_vault(self) -> str:
        """Encrypt the current vault items and return a base64 blob."""
        vault_json = self._get_vault_json()
        plaintext = json.dumps(vault_json).encode("utf-8")
        return encrypt(self._enc_key, plaintext)

    def _refresh_list(self, query: str = "") -> None:
        """Refresh the list store with current vault items, optionally filtered."""
        self._list_store.remove_all()

        for item in self._vault_items:
            if query:
                if (query not in item.name.lower() and
                    query not in item.username.lower() and
                    query not in item.url.lower()):
                    continue
            self._list_store.append(VaultListItem(item.id, item.name, item.username))

        self._item_count_label.set_label(f"{len(self._vault_items)} items")

    def _save_and_sync(self) -> None:
        """Encrypt vault, save locally, and push to server."""
        if not self._enc_key:
            return

        # Increment device clock
        self._vector_clock[self._device_id] = (
            self._vector_clock.get(self._device_id, 0) + 1
        )

        # Encrypt
        blob = self._encrypt_vault()

        # Save locally
        save_cached_vault(blob, self._vector_clock)

        # Push to server in background
        if self._access_token:
            thread = threading.Thread(
                target=self._do_push,
                args=(self._access_token, blob, dict(self._vector_clock)),
                daemon=True,
            )
            thread.start()

    def _do_push(self, access_token: str, blob: str, clock: dict) -> None:
        """Background thread: push encrypted vault to server."""
        try:
            result = push(access_token, blob, clock)
            if result is True:
                GLib.idle_add(self._update_sync_status, "Synced just now", False)
            elif isinstance(result, tuple):
                # 409 conflict — show merge dialog
                server_blob, server_clock = result
                GLib.idle_add(
                    self._show_conflict_dialog,
                    server_blob, server_clock,
                )
        except SyncError as e:
            GLib.idle_add(
                self._update_sync_status,
                f"Sync failed: {e}",
                True,
            )
        except Exception as e:
            GLib.idle_add(
                self._update_sync_status,
                f"Sync error: {e}",
                True,
            )

    def _do_pull(self, access_token: str) -> None:
        """Background thread: pull encrypted vault from server."""
        try:
            blob, clock = pull(access_token)
            GLib.idle_add(self._on_pull_complete, blob, clock)
        except SyncError as e:
            GLib.idle_add(
                self._update_sync_status,
                f"Sync failed: {e}",
                True,
            )
        except Exception as e:
            GLib.idle_add(
                self._update_sync_status,
                f"Sync error: {e}",
                True,
            )

    def _on_pull_complete(self, blob, clock):
        """Main thread: handle pull completion."""
        if blob:
            self._load_vault_from_blob(blob)
            self._vector_clock = clock or {}
            save_cached_vault(blob, self._vector_clock)
        self._update_sync_status("Synced just now", False)
        return False

    # ── UI State ─────────────────────────────────────────────────────

    def _set_locked_ui(self, locked: bool) -> None:
        """Enable/disable UI based on lock state."""
        self._add_btn.set_sensitive(not locked)
        self._edit_btn.set_sensitive(False)  # Always start with no selection
        self._delete_btn.set_sensitive(False)
        self._sync_btn.set_sensitive(not locked)
        self._lock_btn.set_sensitive(not locked)
        self._search_entry.set_sensitive(not locked)
        self._list_view.set_sensitive(not locked)

        if locked:
            self._status_label.set_label("Locked")
            self._status_label.remove_css_class("sync-status-error")
            self._status_label.add_css_class("sync-status")

    def _update_sync_status(self, message: str, is_error: bool) -> None:
        """Update the status bar text."""
        self._status_label.set_label(message)
        if is_error:
            self._status_label.remove_css_class("sync-status")
            self._status_label.add_css_class("sync-status-error")
        else:
            self._status_label.remove_css_class("sync-status-error")
            self._status_label.add_css_class("sync-status")
            self._last_sync_time = datetime.now(timezone.utc)
        return False

    # ── Login / Lock ─────────────────────────────────────────────────

    def _show_login(self) -> None:
        """Show the login dialog."""
        dialog = LoginDialog(self, self._on_login_success)
        dialog.present()
        return False  # For GLib.idle_add

    def _on_login_success(self, email, enc_key, access_token, refresh_token) -> None:
        """Handle successful login."""
        self._email = email
        self._enc_key = enc_key  # bytearray — ownership transferred
        self._access_token = access_token
        self._refresh_token = refresh_token

        self._set_locked_ui(False)
        self._start_idle_timer()

        # Try loading cached vault first
        cached_blob, cached_clock = get_cached_vault()
        if cached_blob:
            try:
                self._load_vault_from_blob(cached_blob)
                self._vector_clock = cached_clock
            except Exception:
                pass  # Cache invalid — will pull from server

        # Pull latest from server
        thread = threading.Thread(
            target=self._do_pull,
            args=(self._access_token,),
            daemon=True,
        )
        thread.start()

    def _on_lock(self, *_args) -> None:
        """Lock the vault: zero enc_key, clear list, show login."""
        self._auto_lock()

    def _auto_lock(self) -> bool:
        """
        Auto-lock callback for idle timer.
        Zeros enc_key, clears vault list, re-shows login dialog.

        Returns:
            False to stop the GLib timeout (it will be recreated on unlock).
        """
        # Zero all sensitive data
        if self._enc_key is not None:
            zero_bytes(self._enc_key)
            self._enc_key = None

        self._access_token = None
        self._vault_items = []
        self._vector_clock = {}

        # Clear UI
        self._list_store.remove_all()
        self._search_entry.set_text("")
        self._set_locked_ui(True)
        self._item_count_label.set_label("")

        # Cancel idle timer
        self._cancel_idle_timer()

        # Show login dialog
        GLib.idle_add(self._show_login)

        return False  # Stop GLib timeout

    # ── Idle Timer ───────────────────────────────────────────────────

    def _start_idle_timer(self) -> None:
        """Start (or restart) the idle auto-lock timer."""
        self._cancel_idle_timer()
        self._idle_timer_id = GLib.timeout_add_seconds(
            IDLE_TIMEOUT_SECONDS, self._auto_lock
        )

    def _cancel_idle_timer(self) -> None:
        """Cancel the current idle timer if running."""
        if self._idle_timer_id is not None:
            GLib.source_remove(self._idle_timer_id)
            self._idle_timer_id = None

    def _reset_idle_timer(self) -> None:
        """Reset the idle timer — called on any user interaction."""
        if self._enc_key is not None:
            self._start_idle_timer()

    # ── Event Handlers (idle timer reset) ────────────────────────────

    def _on_key_event(self, _controller, _keyval, _keycode, _state):
        """Reset idle timer on key press."""
        self._reset_idle_timer()
        return False

    def _on_motion_event(self, _controller, _x, _y):
        """Reset idle timer on mouse motion."""
        self._reset_idle_timer()

    def _on_click_event(self, _controller, _n_press, _x, _y):
        """Reset idle timer on mouse click."""
        self._reset_idle_timer()

    # ── Toolbar Actions ──────────────────────────────────────────────

    def _on_add(self, _btn) -> None:
        """Show add credential dialog."""
        self._reset_idle_timer()
        dialog = ItemDialog(self, self._on_item_saved)
        dialog.present()

    def _on_edit(self, _btn) -> None:
        """Show edit credential dialog for the selected item."""
        self._reset_idle_timer()
        selected = self._selection_model.get_selected_item()
        if not selected:
            return

        # Find the VaultItem by ID
        item = next(
            (i for i in self._vault_items if i.id == selected.item_id),
            None,
        )
        if item:
            dialog = ItemDialog(self, self._on_item_saved, item=item)
            dialog.present()

    def _on_delete(self, _btn) -> None:
        """Show delete confirmation for the selected item."""
        self._reset_idle_timer()
        selected = self._selection_model.get_selected_item()
        if not selected:
            return

        item = next(
            (i for i in self._vault_items if i.id == selected.item_id),
            None,
        )
        if item:
            dialog = DeleteConfirmDialog(
                self,
                item.name,
                lambda: self._on_item_deleted(item.id),
            )
            dialog.present()

    def _on_item_saved(self, item: VaultItem) -> None:
        """Handle item save (add or edit)."""
        # Check if item already exists (edit)
        existing_idx = next(
            (i for i, v in enumerate(self._vault_items) if v.id == item.id),
            None,
        )
        if existing_idx is not None:
            self._vault_items[existing_idx] = item
        else:
            self._vault_items.append(item)

        self._refresh_list()
        self._save_and_sync()

    def _on_item_deleted(self, item_id: str) -> None:
        """Handle item deletion."""
        self._vault_items = [i for i in self._vault_items if i.id != item_id]
        self._refresh_list()
        self._save_and_sync()

    def _on_sync_now(self, _btn) -> None:
        """Manual sync button: push current vault, then pull latest."""
        self._reset_idle_timer()
        if not self._access_token or not self._enc_key:
            return

        self._update_sync_status("Syncing...", False)

        # Encrypt and push
        self._vector_clock[self._device_id] = (
            self._vector_clock.get(self._device_id, 0) + 1
        )
        blob = self._encrypt_vault()
        save_cached_vault(blob, self._vector_clock)

        thread = threading.Thread(
            target=self._do_push,
            args=(self._access_token, blob, dict(self._vector_clock)),
            daemon=True,
        )
        thread.start()

    # ── Conflict Merge Dialog ────────────────────────────────────────

    def _show_conflict_dialog(self, server_blob: str, server_clock: dict) -> None:
        """
        Show a merge dialog when a 409 sync conflict occurs.

        Decrypts both local and server blobs, displays items from both sides,
        allows the user to pick per-item winners, then merges and re-pushes.
        """
        if not self._enc_key:
            return

        # Decrypt server blob
        try:
            server_plaintext = decrypt(self._enc_key, server_blob)
            server_data = json.loads(server_plaintext.decode("utf-8"))
            server_items = [
                VaultItem.from_dict(item)
                for item in server_data.get("items", [])
            ]
        except Exception as e:
            self._update_sync_status(f"Conflict resolution failed: {e}", True)
            return

        local_items = list(self._vault_items)

        # Build conflict dialog
        dialog = Gtk.Dialog(
            title="Sync Conflict — Merge Required",
            transient_for=self,
            modal=True,
        )
        dialog.set_default_size(600, 500)

        content = dialog.get_content_area()
        content.set_spacing(8)
        content.set_margin_top(16)
        content.set_margin_bottom(16)
        content.set_margin_start(16)
        content.set_margin_end(16)

        header = Gtk.Label(label="Sync Conflict Detected")
        header.add_css_class("conflict-header")
        content.append(header)

        desc = Gtk.Label(
            label="Another device has made changes. Select which version to keep for each item."
        )
        desc.set_wrap(True)
        content.append(desc)

        # Scrollable item list
        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        scroll.set_min_content_height(300)

        items_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)

        # Build maps by item ID
        local_map = {item.id: item for item in local_items}
        server_map = {item.id: item for item in server_items}
        all_ids = set(local_map.keys()) | set(server_map.keys())

        checkboxes = {}  # item_id → (checkbox, local_item, server_item)

        for item_id in sorted(all_ids):
            local_item = local_map.get(item_id)
            server_item = server_map.get(item_id)

            row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
            row.add_css_class("conflict-item")

            # Checkbox: checked = keep local, unchecked = keep server
            checkbox = Gtk.CheckButton()
            checkbox.set_active(True)  # Default: keep local

            name = (local_item or server_item).name
            if local_item and server_item:
                label_text = f"{name} (both sides)"
            elif local_item:
                label_text = f"{name} (local only)"
                checkbox.set_active(True)
            else:
                label_text = f"{name} (server only)"
                checkbox.set_active(False)

            checkbox.set_label(f"Keep local: {label_text}")
            checkboxes[item_id] = (checkbox, local_item, server_item)

            row.append(checkbox)
            items_box.append(row)

        scroll.set_child(items_box)
        content.append(scroll)

        # Buttons
        button_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        button_box.set_halign(Gtk.Align.END)
        button_box.set_margin_top(12)

        cancel_btn = Gtk.Button(label="Cancel")
        cancel_btn.connect("clicked", lambda _: dialog.close())
        button_box.append(cancel_btn)

        merge_btn = Gtk.Button(label="Merge & Push")
        merge_btn.add_css_class("suggested-action")
        merge_btn.connect(
            "clicked",
            lambda _: self._do_merge(dialog, checkboxes, server_clock),
        )
        button_box.append(merge_btn)

        content.append(button_box)
        dialog.present()

        return False  # For GLib.idle_add

    def _do_merge(self, dialog, checkboxes, server_clock):
        """Execute the merge based on user selections."""
        merged_items = []

        for item_id, (checkbox, local_item, server_item) in checkboxes.items():
            if checkbox.get_active():
                # Keep local version
                if local_item:
                    merged_items.append(local_item)
            else:
                # Keep server version
                if server_item:
                    merged_items.append(server_item)

        self._vault_items = merged_items

        # Build merged clock: max of each key
        merged_clock = {}
        for key in set(list(self._vector_clock.keys()) + list(server_clock.keys())):
            merged_clock[key] = max(
                self._vector_clock.get(key, 0),
                server_clock.get(key, 0),
            )

        # Increment own device counter
        merged_clock[self._device_id] = merged_clock.get(self._device_id, 0) + 1
        self._vector_clock = merged_clock

        self._refresh_list()
        dialog.close()

        # Encrypt and push merged vault
        blob = self._encrypt_vault()
        save_cached_vault(blob, self._vector_clock)

        if self._access_token:
            thread = threading.Thread(
                target=self._do_push,
                args=(self._access_token, blob, dict(self._vector_clock)),
                daemon=True,
            )
            thread.start()


class VaultListItem(GObject.Object):
    """
    GObject wrapper for vault list items (used by Gio.ListStore).
    """

    def __init__(self, item_id: str, name: str, username: str):
        super().__init__()
        self.item_id = item_id
        self.name = name
        self.username = username
