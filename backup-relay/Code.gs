/**
 * Ajwa Inventory backup relay (Google Apps Script web app).
 *
 * The phone POSTs each encrypted backup here. This script:
 *   1. checks the shared TOKEN,
 *   2. saves the file in Google Drive: "Ajwa Inventory Backups/<shop> <device>/",
 *   3. e-mails it to TO with a readable usage summary.
 *
 * The attachment is encrypted; open it with the "Ajwa Inventory Admin" Windows app.
 * Setup steps: see README.md in this folder.
 */
const TOKEN = 'CHANGE-ME'; // must equal token in relay.properties (use Code.private.gs locally)
const TO = 'basirtechno@gmail.com';
const ROOT_FOLDER = 'Ajwa Inventory Backups';
const TZ = 'Asia/Dhaka';

function doPost(e) {
  try {
    const req = JSON.parse(e.postData.contents);
    if (req.token !== TOKEN) return reply_({ ok: false, error: 'bad token' });
    if (!req.file || !/^[\w.\-]+\.ajwb$/.test(req.fileName || '')) return reply_({ ok: false, error: 'bad file' });

    const s = req.summary || {};
    const shop = s.shopName || req.shop || 'Unknown shop';
    const folder = folder_([ROOT_FOLDER, shop + ' ' + (s.deviceCode || '')]);

    // A retried upload must not create a second copy or a second e-mail.
    if (folder.getFilesByName(req.fileName).hasNext()) return reply_({ ok: true, duplicate: true });

    const blob = Utilities.newBlob(Utilities.base64Decode(req.file), 'application/octet-stream', req.fileName);
    folder.createFile(blob);

    MailApp.sendEmail({
      to: TO,
      subject: shop + ' backup ' + fmtDate_(s.createdAt) + ' · ' + (s.itemCount || 0) + ' items · ' +
        (s.salesCountSinceLastBackup || 0) + ' new sales',
      body: summaryText_(s, shop, folder.getUrl()),
      attachments: [blob],
      name: 'Ajwa Inventory',
    });
    return reply_({ ok: true });
  } catch (err) {
    return reply_({ ok: false, error: String(err) });
  }
}

/** Opening the URL in a browser just confirms the deployment works. */
function doGet() {
  return reply_({ ok: true, service: 'Ajwa backup relay' });
}

function summaryText_(s, shop, folderUrl) {
  const days = s.licenseExpiresAt ? Math.ceil((s.licenseExpiresAt - Date.now()) / 86400000) : null;
  return [
    shop + ' — device ' + (s.deviceCode || '?'),
    '',
    'Backup made:        ' + fmtDate_(s.createdAt),
    'Previous backup:    ' + fmtDate_(s.lastBackupAt),
    'App last opened:    ' + fmtDate_(s.lastOpenedAt),
    'App version:        ' + (s.appVersion || '?'),
    '',
    'License key #' + (s.licenseSerial || '-') + ' expires ' + fmtDate_(s.licenseExpiresAt) +
      (days === null ? '' : days >= 0 ? '  (' + days + ' days left)' : '  (EXPIRED)'),
    '',
    'Items:              ' + (s.itemCount || 0),
    'Out of stock:       ' + (s.outOfStockCount || 0),
    'Low stock:          ' + (s.lowStockCount || 0),
    'Stock value:        ' + taka_(s.stockValuePoisha),
    '',
    'Sales since previous backup: ' + (s.salesCountSinceLastBackup || 0) + '  (' + taka_(s.salesPoishaSinceLastBackup) + ')',
    'All-time sales count:        ' + (s.salesCountTotal || 0),
    '',
    'Drive folder: ' + folderUrl,
    'Open the attached .ajwb file with the Ajwa Inventory Admin app to see full details.',
  ].join('\n');
}

function folder_(path) {
  let f = DriveApp.getRootFolder();
  path.forEach(function (name) {
    const it = f.getFoldersByName(name);
    f = it.hasNext() ? it.next() : f.createFolder(name);
  });
  return f;
}

function fmtDate_(ms) {
  return ms ? Utilities.formatDate(new Date(ms), TZ, 'dd MMM yyyy, hh:mm a') : '-';
}

function taka_(poisha) {
  return 'Tk ' + ((poisha || 0) / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

function reply_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
