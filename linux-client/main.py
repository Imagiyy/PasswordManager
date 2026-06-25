"""
VaultManager — Zero-Knowledge Password Manager (Linux Client)

Entry point: Gio.Application subclass that initializes the GTK4 app,
loads CSS styles, and creates the main window.

Usage:
  python3 main.py

Environment:
  VAULT_SERVER_URL  — Backend API URL (must be HTTPS in production)
                      Default: https://localhost:3000
"""

import sys
import os

import gi
gi.require_version("Gtk", "4.0")
from gi.repository import Gtk, Gdk, Gio, GLib  # noqa: E402


class VaultManagerApp(Gtk.Application):
    """
    GTK4 Application subclass for VaultManager.

    Handles:
      - Application lifecycle (startup, activate, shutdown)
      - CSS theme loading
      - Main window creation
    """

    def __init__(self):
        super().__init__(
            application_id="com.vaultmanager.linux",
            flags=Gio.ApplicationFlags.DEFAULT_FLAGS,
        )
        self._window = None

    def do_startup(self):
        """
        Called once when the application starts.
        Loads the GTK4 CSS theme.
        """
        Gtk.Application.do_startup(self)
        self._load_css()

    def do_activate(self):
        """
        Called when the application is activated (launched or re-focused).
        Creates the main window if it doesn't exist.
        """
        if self._window is None:
            # Import here to avoid circular imports
            from ui.main_window import MainWindow
            self._window = MainWindow(self)

        self._window.present()

    def do_shutdown(self):
        """
        Called when the application is shutting down.
        Clean up resources.
        """
        Gtk.Application.do_shutdown(self)

    def _load_css(self):
        """
        Load the GTK4 CSS stylesheet from ui/styles.css.
        The CSS file path is resolved relative to this script.
        """
        css_provider = Gtk.CssProvider()

        # Resolve CSS path relative to this script
        script_dir = os.path.dirname(os.path.abspath(__file__))
        css_path = os.path.join(script_dir, "ui", "styles.css")

        if os.path.isfile(css_path):
            css_provider.load_from_path(css_path)

            # Apply CSS to the default display
            display = Gdk.Display.get_default()
            if display:
                Gtk.StyleContext.add_provider_for_display(
                    display,
                    css_provider,
                    Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
                )


def main():
    """Application entry point."""
    app = VaultManagerApp()
    return app.run(sys.argv)


if __name__ == "__main__":
    sys.exit(main())
