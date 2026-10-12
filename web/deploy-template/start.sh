#!/bin/sh
# Starts the Ajwa Sanitary web app (Linux/macOS). Needs Java 21.
cd "$(dirname "$0")" || exit 1
exec java -Duser.timezone=Asia/Dhaka -jar ajwa-web.jar "$@"
