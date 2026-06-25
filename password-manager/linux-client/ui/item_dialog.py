"""
Add/Edit/Delete credential dialog for the Linux client.

Fields: name, username, password, URL, notes.
Generates UUID v4 for new items.
"""

import uuid
from datetime import datetime, timezone

import gi
gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402


class VaultItem:
    """Represents a single credential item in the vault."""

    def __init__(
        self,
        item_id: str = None,
        name: str = "",
        username: str = "",
        password: str = "",
        url: str = "",
        notes: str = "",
        created_at: str = None,
        updated_at: str = None,
    ):
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.id = item_id or str(uuid.uuid4())
        self.name = name
        self.username = username
        self.password = password
        self.url = url
        self.notes = notes
        self.created_at = created_at or now
        self.updated_at = updated_at or now

    def to_dict(self) -> dict:
        """Convert to dict matching the vault item JSON schema."""
        return {
            "id": self.id,
            "name": self.name,
            "username": self.username,
            "password": self.password,
            "url": self.url,
            "notes": self.notes,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "VaultItem":
        """Create a VaultItem from a dict."""
        return cls(
            item_id=data.get("id"),
            name=data.get("name", ""),
            username=data.get("username", ""),
            password=data.get("password", ""),
            url=data.get("url", ""),
            notes=data.get("notes", ""),
            created_at=data.get("created_at"),
            updated_at=data.get("updated_at"),
        )

    def update(self, name: str, username: str, password: str, url: str, notes: str) -> None:
        """Update fields and set updated_at to now."""
        self.name = name
        self.username = username
        self.password = password
        self.url = url
        self.notes = notes
        self.updated_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class ItemDialog(Gtk.Dialog):
    """
    GTK4 dialog for adding or editing a vault credential item.

    In 'add' mode: all fields are empty, creates a new VaultItem.
    In 'edit' mode: fields are pre-filled from an existing VaultItem.

    The dialog returns the VaultItem via the on_save callback.
    """

    def __init__(
        self,
        parent: Gtk.Window,
        on_save: callable,
        item: VaultItem = None,
    ):
        """
        Args:
            parent:  Parent GTK4 window
            on_save: Callback(VaultItem) called when the user saves
            item:    Existing VaultItem for edit mode; None for add mode
        """
        is_edit = item is not None
        title = "Edit Credential" if is_edit else "Add Credential"

        super().__init__(
            title=title,
            transient_for=parent,
            modal=True,
        )

        self._on_save = on_save
        self._item = item
        self._is_edit = is_edit

        self.set_default_size(460, 420)
        self.set_resizable(False)

        content = self.get_content_area()
        content.set_spacing(8)
        content.set_margin_top(20)
        content.set_margin_bottom(20)
        content.set_margin_start(20)
        content.set_margin_end(20)

        # ── Title ────────────────────────────────────────────────────
        title_label = Gtk.Label(label=title)
        title_label.add_css_class("dialog-title")
        content.append(title_label)

        # ── Name ─────────────────────────────────────────────────────
        content.append(Gtk.Label(label="Name", xalign=0))
        self._name_entry = Gtk.Entry()
        self._name_entry.set_placeholder_text("e.g., GitHub")
        if is_edit:
            self._name_entry.set_text(item.name)
        content.append(self._name_entry)

        # ── Username ─────────────────────────────────────────────────
        content.append(Gtk.Label(label="Username / Email", xalign=0))
        self._username_entry = Gtk.Entry()
        self._username_entry.set_placeholder_text("user@example.com")
        if is_edit:
            self._username_entry.set_text(item.username)
        content.append(self._username_entry)

        # ── Password ────────────────────────────────────────────────
        content.append(Gtk.Label(label="Password", xalign=0))
        self._password_entry = Gtk.PasswordEntry()
        self._password_entry.set_show_peek_icon(True)
        if is_edit:
            self._password_entry.set_text(item.password)
        content.append(self._password_entry)

        # ── URL ──────────────────────────────────────────────────────
        content.append(Gtk.Label(label="URL", xalign=0))
        self._url_entry = Gtk.Entry()
        self._url_entry.set_placeholder_text("https://github.com")
        if is_edit:
            self._url_entry.set_text(item.url)
        content.append(self._url_entry)

        # ── Notes ────────────────────────────────────────────────────
        content.append(Gtk.Label(label="Notes", xalign=0))
        self._notes_view = Gtk.TextView()
        self._notes_view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        self._notes_view.set_size_request(-1, 80)
        notes_scroll = Gtk.ScrolledWindow()
        notes_scroll.set_child(self._notes_view)
        notes_scroll.set_vexpand(False)
        notes_scroll.set_size_request(-1, 80)
        if is_edit and item.notes:
            self._notes_view.get_buffer().set_text(item.notes)
        content.append(notes_scroll)

        # ── Buttons ─────────────────────────────────────────────────
        button_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        button_box.set_halign(Gtk.Align.END)
        button_box.set_margin_top(12)

        cancel_btn = Gtk.Button(label="Cancel")
        cancel_btn.connect("clicked", lambda _: self.close())
        button_box.append(cancel_btn)

        save_btn = Gtk.Button(label="Save")
        save_btn.add_css_class("suggested-action")
        save_btn.connect("clicked", self._on_save_clicked)
        button_box.append(save_btn)

        content.append(button_box)

    def _get_notes_text(self) -> str:
        """Extract text from the notes TextView."""
        buf = self._notes_view.get_buffer()
        start = buf.get_start_iter()
        end = buf.get_end_iter()
        return buf.get_text(start, end, False)

    def _on_save_clicked(self, _btn: Gtk.Button) -> None:
        """Handle Save button click."""
        name = self._name_entry.get_text().strip()
        username = self._username_entry.get_text().strip()
        password = self._password_entry.get_text()
        url = self._url_entry.get_text().strip()
        notes = self._get_notes_text().strip()

        if not name:
            # Name is required
            self._name_entry.add_css_class("error")
            return

        if self._is_edit:
            # Update existing item
            self._item.update(name, username, password, url, notes)
            self._on_save(self._item)
        else:
            # Create new item
            new_item = VaultItem(
                name=name,
                username=username,
                password=password,
                url=url,
                notes=notes,
            )
            self._on_save(new_item)

        self.close()


class DeleteConfirmDialog(Gtk.MessageDialog):
    """
    Confirmation dialog for deleting a vault item.
    """

    def __init__(self, parent: Gtk.Window, item_name: str, on_confirm: callable):
        """
        Args:
            parent:     Parent GTK4 window
            item_name:  Name of the item being deleted
            on_confirm: Callback() called when deletion is confirmed
        """
        super().__init__(
            transient_for=parent,
            modal=True,
            message_type=Gtk.MessageType.WARNING,
            buttons=Gtk.ButtonsType.NONE,
            text=f'Delete "{item_name}"?',
        )

        self.set_secondary_text(
            "This action cannot be undone. The credential will be "
            "permanently removed from your vault."
        )

        self.add_button("Cancel", Gtk.ResponseType.CANCEL)
        delete_btn = self.add_button("Delete", Gtk.ResponseType.ACCEPT)
        delete_btn.add_css_class("destructive-action")

        self.connect("response", self._on_response, on_confirm)

    def _on_response(self, _dialog, response, on_confirm):
        """Handle dialog response."""
        if response == Gtk.ResponseType.ACCEPT:
            on_confirm()
        self.close()
