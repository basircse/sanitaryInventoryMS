# Ajwa Sanitary — Inventory (SPIM)

Offline Android inventory app for **Ajwa Sanitary** with offline license keys and automatic
encrypted backups e-mailed to the provider.

| Module | What it is |
|---|---|
| `app/` | Android app (`com.basir.inventory`, min Android 7.0). Kotlin, Jetpack Compose, Room on SQLCipher, WorkManager. Bangla by default. |
| `admin/` | **Ajwa Inventory Admin** — Windows app for the provider: issue license keys, open backups, move data to a new phone. |
| `shared/` | Code used by both: license key format (Ed25519), backup encryption (X25519 + AES-GCM), backup data model. |
| `backup-relay/` | Google Apps Script that receives backups and e-mails them to basirtechno@gmail.com. |

## Secrets — back these up (USB + Google Drive), never share them

| File | Why it matters |
|---|---|
| `%APPDATA%\AjwaAdmin\provider_keys.properties` | Provider **private** keys. Without them you cannot issue keys or open backups. If leaked, anyone can make license keys. |
| `signing/ajwa-release.jks` + `keystore.properties` | APK signing key. Without it, updates cannot be installed over the existing app, and every phone's device code changes. |
| `relay.properties`, `backup-relay/Ajwa backup relay.gs` | Backup upload URL and token (git-ignored). |
| `%APPDATA%\AjwaAdmin\issued_keys.csv` | Log of every key issued (serial numbers continue from here). |

`provider-keys/public.properties` holds only the public halves; it is safe to keep in the project.

## One-time setup

1. **Provider keys** — already created. To recreate on a new PC, install the admin app and use
   *Provider keys → Import keys file* with your backup copy. (Only run
   `gradlew :admin:initProviderKeys` on a PC that has no keys yet.)
2. **Backup e-mail relay** (Google Apps Script, free):
   1. Sign in to basirtechno@gmail.com, open <https://script.google.com> → *New project*.
   2. Replace the code with `backup-relay/Ajwa backup relay.gs` (your local copy with the real token;
      the committed `Code.gs` has `CHANGE-ME`) and save (name it "Ajwa backup relay").
   3. *Deploy → New deployment → Web app*. Execute as **Me**, Who has access **Anyone**. Deploy and
      allow the Gmail/Drive permissions it asks for.
   4. Copy the web-app URL (ends in `/exec`) into `relay.properties` → `endpoint=`.
      Opening that URL in a browser should show `{"ok":true,...}`.
   5. `token` in `relay.properties` must equal `TOKEN` in the script. Both files are git-ignored;
      on a fresh clone create `relay.properties` with `endpoint=` and `token=<random string>`.
   6. In the script editor, choose the function `authorize` and click *Run* once. On Google's
      permission screen tick **every** box (Gmail send, Drive, Sheets) — a missing Drive or Gmail
      permission makes backups fail silently. It also creates the **Ajwa Inventory Monitor** sheet
      (its link is e-mailed to you). After granting, use *Manage deployments → Edit → New version*.
   Gmail allows ~100 e-mails/day from scripts — plenty for daily backups from one shop.

   **Updating the script later:** paste the new code, then *Deploy → Manage deployments → ✏️ Edit
   → Version: New version → Deploy*. This keeps the same URL, so no APK rebuild is needed.

## Usage monitoring (Google Sheet)

Each phone sends a small **heartbeat** once a day when online (and when the app is opened, at most
every 20 hours). It carries counts only — no item names or prices. The script records it, and every
backup, in the **Ajwa Inventory Monitor** sheet in your Google Drive:

- **Status** tab — one live row per phone: last heard, last opened, items, out of stock, low stock,
  today's sales, license state/expiry/days left, last backup, unsent backups, last error.
  Red = not heard from for 2+ days, app not opened for 3+ days, or license expired / clock set
  back. Amber = license ends within 3 days, or more than 2 backups waiting.
- **Log** tab — one row per heartbeat or backup, for history and charts.

"Last error" shows the latest app crash or failed backup, so problems surface without the shop
having to report them. Open the sheet in the Google Sheets app on your phone to check it anywhere.
3. **Build the APK**: `gradlew :app:assembleRelease` →
   `app/build/outputs/apk/release/app-release.apk`. Send it to the shop phone and install
   (allow "install unknown apps" once).
4. **Build the admin installer**: `gradlew :admin:packageExe` →
   `admin/build/compose/binaries/main/exe/Ajwa Inventory Admin-1.0.0.exe`.

## Daily operations

**Issuing / renewing a license**
1. The shop owner opens the app; the lock screen shows a **device code** (e.g. `K7MQ-3XRT`) with a
   *Send code on WhatsApp* button.
2. In the admin app → *License keys*: enter the device code, days (30), backup frequency
   (Daily/Weekly/Monthly) → *Generate key* → *Copy WhatsApp message* and send it.
3. The owner pastes the key and taps *Activate*. Valid for 30 days **from that moment**.

Rules enforced by the app: the key works only on that phone, only once, and must be entered within
7 days of being issued. From 3 days before expiry the app shows a banner and a notification. On
expiry the app is fully locked (provider contact shown). If the phone clock is turned back by more
than a day, the app locks until the clock is fixed.

**Backups**
- Automatic and silent: daily at ~9 PM (or weekly/monthly, per the key). The last 3 are kept on the
  phone in `Android/data/com.basir.inventory/files/Backups/`. Each one is uploaded when internet is
  available (queued while offline) and arrives by e-mail with a usage summary; a copy is filed in
  Drive under *Ajwa Inventory Backups*.
- Backups keep running after the license expires, so you can still see usage.
- To read a backup: admin app → *Open backup* → choose the `.ajwb` file.

**Restoring**
- Same phone: Settings → *Restore from backup* → choose a file from `Backups/`.
- New phone: install the APK, note the new device code, issue a key for it. In the admin app open
  the latest emailed backup → *Create restore file* for the new device code → send the `.ajwr`
  file → Settings → *Restore from backup* on the new phone.
- A restore first backs up the current data, then replaces everything in one transaction.

## Development

```
gradlew :shared:test :app:testDebugUnitTest   # unit tests
gradlew :app:assembleDebug                     # debug APK
gradlew :admin:run                             # run the admin app
```

Data notes: money is stored in poisha (1/100 taka) and quantities in thousandths of a unit, so
12.5 ft is stored as 12500 — no floating-point rounding. Prices and costs are copied onto each
sale, so later price edits do not change past reports. Every stock change is logged in
`stock_movements`.

Not in v1 (by agreement): barcode scanning, discounts, purchase records, multiple users.
