#!/bin/bash
# Get the absolute path to this project directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
LAUNCHER_PATH="$DIR/run-client.sh"
DESKTOP_DIR="$HOME/.local/share/applications"

# Ensure the applications directory exists
mkdir -p "$DESKTOP_DIR"

# Write the .desktop file
cat <<EOF > "$DESKTOP_DIR/vaultmanager.desktop"
[Desktop Entry]
Version=1.0
Type=Application
Name=VaultManager
Comment=Zero-Knowledge Password Manager
Exec=$LAUNCHER_PATH
Icon=system-lock-screen
Terminal=false
Categories=Utility;Security;
StartupWMClass=vaultmanager
EOF

# Make it executable
chmod +x "$DESKTOP_DIR/vaultmanager.desktop"

echo "Desktop entry installed to: $DESKTOP_DIR/vaultmanager.desktop"
echo "You can now find and click 'VaultManager' in your Fedora applications menu!"
