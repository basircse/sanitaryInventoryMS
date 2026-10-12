# Ajwa Sanitary web app — deployment

Everything needed to run the web app (React app + API in one jar) on a server or PC.

| File | Purpose |
|---|---|
| `ajwa-web.jar` | The app. Serves the web pages and the API on one port. |
| `application.properties` | Database address and login, port. **Fill in the database password before the first start.** |
| `relay.properties` | Backup e-mail relay URL and token (secret). Without it backups are still made, but not e-mailed. |
| `start.sh` / `start.bat` | Start on Linux / Windows. |
| `ajwa-web.service` | Run as a Linux service (starts on boot, restarts after a crash). |
| `data/` | Created on first start; the newest 3 backups are kept here. |

## 1. Requirements

- Java 21 (`java -version`). Ubuntu/Debian: `sudo apt install openjdk-21-jre-headless`.
- A MySQL account that can connect from this machine to `149.20.188.83:3306`, with rights to create
  and alter tables in `ajwa_inventory`. If it doesn't exist, on the MySQL server:

  ```sql
  CREATE DATABASE IF NOT EXISTS ajwa_inventory CHARACTER SET utf8mb4;
  CREATE USER 'ajwa'@'%' IDENTIFIED BY 'a-strong-password';
  GRANT ALL PRIVILEGES ON ajwa_inventory.* TO 'ajwa'@'%';
  ```

  Then put that user and password in `application.properties`.

## 2. Moving the existing data (optional)

To keep the current stock, sales **and the current license activation**, copy the whole database
from the PC to the server (this includes the `licenses` and `app_state` tables, which hold the device
code and the license):

```bash
mysqldump -u root ajwa_inventory > ajwa_inventory.sql
mysql -h 149.20.188.83 -u ajwa -p ajwa_inventory < ajwa_inventory.sql
```

If you start on an empty database instead, the app gets a **new device code**, and you issue it a
new key in the admin app.

## 3. Start

Linux:

```bash
sudo mkdir -p /opt/ajwa-web && sudo cp -r . /opt/ajwa-web && sudo useradd -r ajwa && sudo chown -R ajwa /opt/ajwa-web
sudo cp /opt/ajwa-web/ajwa-web.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now ajwa-web
journalctl -u ajwa-web -f
```

Windows: double-click `start.bat` (keep the window open).

On first start the app creates or updates its tables. The log ends with
`Started WebApplication`. Open `http://<server-address>:8110` in a phone browser. Open port 8110 in
the server's firewall, or put the app behind nginx/Caddy for HTTPS on port 443 (recommended when the
server is on the internet; HTTPS also enables the *Paste* button on the license screen).

## 4. License

The lock screen shows the device code. Issue a key for it in the admin app (*License keys*) and
paste it there, the same as for a phone.

## Security

The app has no user login beyond the license, the same as the phone app. If this server is
reachable from the internet, anyone who knows the address can use the app. Restrict access (VPN,
firewall allow-list, or a reverse proxy with a password) before giving out the address.

## Troubleshooting

- `Access denied for user` → wrong user/password in `application.properties`, or the MySQL user is
  not allowed from this machine's IP.
- `Public Key Retrieval is not allowed` → add `&allowPublicKeyRetrieval=true` to the
  `spring.datasource.url`, or enable SSL on the MySQL server.
- `Communications link failure` → port 3306 is blocked between this machine and the database.
