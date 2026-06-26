#!/bin/bash
# Get the directory of this script
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

# Navigate to the linux-client directory
cd "$DIR/linux-client"

# Activate the virtual environment
source venv/bin/activate

# Set the backend URL environment variable
export VAULT_SERVER_URL="http://localhost:3000"

# Run the app
python3 main.py
