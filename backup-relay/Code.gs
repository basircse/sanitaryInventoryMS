/**
 * Ajwa Inventory backup relay (Google Apps Script web app).
 *
 * The phone POSTs each encrypted backup here. This script:
 *   1. checks the shared TOKEN,
 *   2. saves the file in Google Drive: "Ajwa Inventory Backups/<shop> <device>/",
 *   3. e-mails it to TO with a readable usage summary.
 *
 * It also receives a daily "heartbeat" (counts only) from each phone and records heartbeats and
 * backups in the Google Sheet "Ajwa Inventory Monitor": a Log tab (one row per message) and a
 * Status tab (one live row per phone, problems highlighted).
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
    if (req.type === 'heartbeat') {
      logHeartbeat_(req.heartbeat || {}, req.shop || 'Unknown shop');
      return reply_({ ok: true });
    }
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
    // Monitoring is best effort: a sheet problem must not make the phone resend the backup.
    try { logBackup_(s, shop); } catch (sheetErr) { console.error(sheetErr); }
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

// ------------------------------------------------------------------ monitoring sheet

const SHEET_NAME = 'Ajwa Inventory Monitor';
const LOG_HEADERS = [
  'Received', 'Type', 'Shop', 'Device', 'App version', 'Phone', 'Last opened', 'Items', 'Out of stock',
  'Low stock', 'Stock value (Tk)', 'Sales count*', 'Sales (Tk)*', 'License', 'License expires',
  'Last backup', 'Unsent backups', 'Last error',
];
const STATUS_HEADERS = [
  'Device', 'Shop', 'Last heard', 'Last opened', 'App version', 'Phone', 'Items', 'Out of stock',
  'Low stock', 'Sales today', 'Sales today (Tk)', 'License', 'License expires', 'Days left',
  'Last backup', 'Unsent backups', 'Last error', 'Last error at',
];

function logHeartbeat_(h, shop) {
  withSheet_(function (ss) {
    ss.getSheetByName('Log').appendRow([
      new Date(), 'heartbeat', shop, h.deviceCode, h.appVersion, h.phone, date_(h.lastOpenedAt),
      h.itemCount, h.outOfStockCount, h.lowStockCount, tk_(h.stockValuePoisha), h.salesTodayCount,
      tk_(h.salesTodayPoisha), h.licenseState, date_(h.licenseExpiresAt), date_(h.lastBackupAt),
      h.pendingUploads, h.lastError ? fmtDate_(h.lastErrorAt) + ' ' + h.lastError : '',
    ]);
    const update = {
      'Shop': shop, 'Last heard': new Date(), 'Last opened': date_(h.lastOpenedAt), 'App version': h.appVersion,
      'Phone': h.phone, 'Items': h.itemCount, 'Out of stock': h.outOfStockCount, 'Low stock': h.lowStockCount,
      'Sales today': h.salesTodayCount, 'Sales today (Tk)': tk_(h.salesTodayPoisha), 'License': h.licenseState,
      'License expires': date_(h.licenseExpiresAt), 'Last backup': date_(h.lastBackupAt),
      'Unsent backups': h.pendingUploads,
    };
    // Keep the last error visible until a newer one replaces it.
    if (h.lastError) {
      update['Last error'] = h.lastError;
      update['Last error at'] = date_(h.lastErrorAt);
    }
    upsertStatus_(ss, h.deviceCode, update);
  });
}

function logBackup_(s, shop) {
  withSheet_(function (ss) {
    ss.getSheetByName('Log').appendRow([
      new Date(), 'backup', shop, s.deviceCode, s.appVersion, '', date_(s.lastOpenedAt), s.itemCount,
      s.outOfStockCount, s.lowStockCount, tk_(s.stockValuePoisha), s.salesCountSinceLastBackup,
      tk_(s.salesPoishaSinceLastBackup), '', date_(s.licenseExpiresAt), date_(s.createdAt), '', '',
    ]);
    upsertStatus_(ss, s.deviceCode, {
      'Shop': shop, 'Last heard': new Date(), 'Last opened': date_(s.lastOpenedAt), 'App version': s.appVersion,
      'Items': s.itemCount, 'Out of stock': s.outOfStockCount, 'Low stock': s.lowStockCount,
      'License expires': date_(s.licenseExpiresAt), 'Last backup': date_(s.createdAt),
    });
  });
}

/** Updates (or adds) the Status row for one device; only the given columns change. */
function upsertStatus_(ss, device, values) {
  const sheet = ss.getSheetByName('Status');
  const rows = Math.max(sheet.getLastRow() - 1, 1);
  const devices = sheet.getRange(2, 1, rows, 1).getValues().map(function (r) { return r[0]; });
  let row = devices.indexOf(device) + 2;
  if (row < 2) {
    row = sheet.getLastRow() + 1;
    sheet.getRange(row, 1).setValue(device);
  }
  STATUS_HEADERS.forEach(function (name, i) {
    if (name in values) sheet.getRange(row, i + 1).setValue(values[name]);
  });
  // Days left is a formula so it stays current between messages.
  const exp = colLetter_(STATUS_HEADERS.indexOf('License expires') + 1) + row;
  sheet.getRange(row, STATUS_HEADERS.indexOf('Days left') + 1)
    .setFormula('=IF(' + exp + '="","",ROUNDUP(' + exp + '-NOW(),0))');
}

function withSheet_(fn) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    fn(spreadsheet_());
  } finally {
    lock.releaseLock();
  }
}

/** Opens the monitor spreadsheet, creating and formatting it on first use. */
function spreadsheet_() {
  const props = PropertiesService.getScriptProperties();
  const id = props.getProperty('SHEET_ID');
  if (id) {
    try { return SpreadsheetApp.openById(id); } catch (e) { /* deleted: make a new one */ }
  }
  const ss = SpreadsheetApp.create(SHEET_NAME);
  ss.setSpreadsheetTimeZone(TZ);
  const status = ss.getSheets()[0].setName('Status');
  const log = ss.insertSheet('Log');
  setUp_(status, STATUS_HEADERS, ['Last heard', 'Last opened', 'License expires', 'Last backup', 'Last error at']);
  setUp_(log, LOG_HEADERS, ['Received', 'Last opened', 'License expires', 'Last backup']);
  log.getRange(1, LOG_HEADERS.indexOf('Sales count*') + 1)
    .setNote('Heartbeat rows: sales today. Backup rows: sales since the previous backup.');

  // Highlight problems on the Status tab.
  const col = function (name) { return '$' + colLetter_(STATUS_HEADERS.indexOf(name) + 1) + '2'; };
  const all = status.getRange('A2:' + colLetter_(STATUS_HEADERS.length) + '1000');
  const red = '#f4c7c3';
  const amber = '#fce8b2';
  status.setConditionalFormatRules([
    rule_(all, '=AND(' + col('Last heard') + '<>"",' + col('Last heard') + '<NOW()-2)', red),
    rule_(all, '=AND(' + col('Last opened') + '<>"",' + col('Last opened') + '<NOW()-3)', red),
    rule_(all, '=OR(' + col('License') + '="expired",' + col('License') + '="clock_set_back")', red),
    rule_(all, '=AND(' + col('Days left') + '<>"",' + col('Days left') + '<=3)', amber),
    rule_(all, '=' + col('Unsent backups') + '>2', amber),
  ]);
  status.getRange(1, 1).setNote(
    'Red: not heard from for 2+ days, app not opened for 3+ days, or license expired / clock set back. ' +
    'Amber: license ends within 3 days, or more than 2 backups waiting to send.');

  props.setProperty('SHEET_ID', ss.getId());
  MailApp.sendEmail(TO, 'Ajwa Inventory monitor sheet created', 'Usage monitoring sheet: ' + ss.getUrl());
  return ss;
}

function setUp_(sheet, headers, dateCols) {
  sheet.getRange(1, 1, 1, headers.length).setValues([headers]).setFontWeight('bold').setBackground('#d3eef8');
  sheet.setFrozenRows(1);
  dateCols.forEach(function (name) {
    sheet.getRange(2, headers.indexOf(name) + 1, 999, 1).setNumberFormat('dd mmm yyyy, hh:mm AM/PM');
  });
  sheet.autoResizeColumns(1, headers.length);
}

function rule_(range, formula, color) {
  return SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied(formula).setBackground(color)
    .setRanges([range]).build();
}

function colLetter_(n) {
  let s = '';
  while (n > 0) {
    const m = (n - 1) % 26;
    s = String.fromCharCode(65 + m) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}

function date_(ms) {
  return ms ? new Date(ms) : '';
}

function tk_(poisha) {
  return Math.round(poisha || 0) / 100;
}

/** Run once from the Apps Script editor to create the sheet and grant Sheets permission. */
function setupMonitorSheet() {
  withSheet_(function (ss) { console.log('Monitor sheet: ' + ss.getUrl()); });
}
