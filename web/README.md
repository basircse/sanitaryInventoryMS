# Ajwa Sanitary — Web app

The Android app's features in a browser, for use from the shop's phones over Wi-Fi.

| Folder | What it is |
|---|---|
| `server/` | Spring Boot 3.5 (Java 21) REST API + scheduled backups. MySQL via JDBC. |
| `client/` | React 19 + Vite + TypeScript. Mobile-first: bottom tabs on phones, side menu on wide screens. Bangla by default (Bangla digits), English in Settings. |

Same features as the phone: dashboard, stock list with search/filters, sell / stock in / adjust with
history, add/edit/delete items, categories (3 levels), suppliers, reports (low stock, sales by date
range with profit, by supplier) with CSV download, general low-stock limit, backup/restore, license.

## Licensing (same as the phone)

The whole web installation counts as **one device**. On first start the server creates a random
installation id (table `app_state`) and derives a device code from it, e.g. `A8KU-2342`.
The lock screen shows it with a *Send code on WhatsApp* button. Issue the key in the admin app
(*License keys* → that device code) like for a phone; the key is pasted on the lock screen and then
works for every browser. The rules are the phone's: the key must be for this device code, it works
once, it must be entered within 7 days, it is valid for 30 days from entry, there's a reminder banner
from 3 days before expiry, the app is fully locked once it expires, and also if the server clock goes
back by more than a day. The server enforces this: every `/api/**` call except `/api/license`
returns 403 while locked.

If `app_state` is wiped or the database is replaced by one without it, the device code changes and a
new key is needed (a re-import of an admin-app MySQL export does **not** touch `app_state` or
`licenses`).

## Database

Uses the database the admin app's *Export as MySQL* creates (`ajwa_inventory`, money in taka,
quantities in units, times in Bangladesh time), so a phone backup can be imported and then used
from the web. On start the server adds what it needs: `AUTO_INCREMENT` on the ids, plus the
`licenses` and `app_state` tables. Defaults: `localhost:3306/ajwa_inventory`, user `root`, no
password. Override with environment variables `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.

## Backups

Same `.ajwb` format as the phone, so the admin app's *Open backup* / *Export as MySQL* read them.
Automatic at 9 PM (daily/weekly/monthly as set in the license key), with a catch-up if the PC was off.
The newest 3 are kept in `data/backups` (next to where the server runs; `AJWA_DATA_DIR` changes it) and can
be downloaded from Settings. They are e-mailed through the backup relay, and the daily heartbeat
goes to the monitor sheet, when `relay.properties` (the same file the APK build uses) is in the
working directory (the deploy folder) or at `android/relay.properties` when run from `web/server`.

*Restore from backup* accepts this server's own `.ajwb` files and `.ajwr` restore files made in the
admin app for the web device code. To move a shop from a phone to the web, open the phone's latest
backup in the admin app → *Create restore file* for the web device code → restore it in Settings.

## Run

Development (two terminals; open http://localhost:5173, or http://<pc-ip>:5173 from a phone):

```bash
cd web/server && mvn spring-boot:run
```

```bash
cd web/client && npm install && npm run dev
```

Deployment: `powershell -ExecutionPolicy Bypass -File web\build-deploy.ps1` builds everything into
`deploy/` (jar, config for the server database at 149.20.188.83, start scripts, Linux service file);
see `deploy/README.md` (template in `web/deploy-template/`).

Production by hand — one jar serving both the app and the API on port 8110:

```bash
cd web/client && npm install && npm run build
```

```bash
cd web/server && mvn package && java -jar target/ajwa-web-1.0.0.jar
```

Phones on the shop Wi-Fi then open `http://<pc-ip>:8110` (allow port 8110 in Windows Firewall).
Maven needs JDK 21 (`JAVA_HOME`).

Note: the app has no login beyond the license, like the phone. Anyone who can reach the port can use
it, so keep it on the shop network and don't forward the port to the internet.
