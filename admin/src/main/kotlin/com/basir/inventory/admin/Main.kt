package com.basir.inventory.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.basir.inventory.shared.BackupData
import com.basir.inventory.shared.BackupEnvelope
import com.basir.inventory.shared.BackupMode
import com.basir.inventory.shared.DeviceCode
import com.basir.inventory.shared.LicenseCodec
import com.basir.inventory.shared.LicensePayload
import com.basir.inventory.shared.ProviderKeys
import com.basir.inventory.shared.RestorePackage
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Brand = Color(0xFF0B6E99)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Ajwa Inventory Admin",
        state = rememberWindowState(size = DpSize(980.dp, 760.dp)),
    ) {
        MaterialTheme(colorScheme = lightColorScheme(primary = Brand, secondary = Color(0xFF2BA3C4))) {
            Surface(Modifier.fillMaxSize()) { AdminApp() }
        }
    }
}

@Composable
private fun AdminApp() {
    var keys by remember { mutableStateOf(AdminStore.loadKeys()) }
    var tab by remember { mutableIntStateOf(if (keys == null) 2 else 0) }
    Column {
        TabRow(selectedTabIndex = tab) {
            listOf("License keys", "Open backup", "Provider keys").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        val k = keys
        Column(Modifier.padding(20.dp)) {
            when {
                tab == 2 -> KeysTab(keys) { keys = it }
                k == null -> Text("Set up provider keys first (Provider keys tab).", color = MaterialTheme.colorScheme.error)
                tab == 0 -> LicenseTab(k)
                else -> BackupTab(k)
            }
        }
    }
}

// ---------------------------------------------------------------- License keys

@Composable
private fun LicenseTab(keys: ProviderKeys) {
    var device by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf("Ajwa Sanitary") }
    var days by remember { mutableStateOf("30") }
    var mode by remember { mutableStateOf(BackupMode.DAILY) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf(AdminStore.issuedKeys().reversed()) }

    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Issue a license key", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(device, { device = it.uppercase() }, label = { Text("Device code (from the phone, e.g. ABCD-EFGH)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(customer, { customer = it }, label = { Text("Customer / shop") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(days, { days = it.filter(Char::isDigit).take(3) }, label = { Text("Valid for (days, from the day it is entered)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Automatic backup")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupMode.entries.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.name.lowercase().replaceFirstChar(Char::uppercase)) })
                }
            }
            Button(onClick = {
                error = null; result = null
                val code = DeviceCode.parse(device.trim())
                val d = days.toIntOrNull()
                when {
                    code == null -> error = "Device code must be 8 letters/digits like ABCD-EFGH"
                    d == null || d !in 1..255 -> error = "Days must be 1-255"
                    else -> {
                        val serial = AdminStore.nextSerial()
                        val payload = LicensePayload(code, LicensePayload.dayOf(LocalDate.now()), d, mode, serial)
                        val key = LicenseCodec.sign(payload, keys.signPrivate)
                        AdminStore.recordIssued(serial, DeviceCode.display(code), customer, d, mode, key)
                        result = key
                        log = AdminStore.issuedKeys().reversed()
                    }
                }
            }) { Text("Generate key") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { key ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer { Text(key, fontFamily = FontFamily.Monospace) }
                        Text("Must be entered within 7 days. Works only on device ${DeviceCode.display(DeviceCode.parse(device)!!)}.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { copy(key) }) { Text("Copy key") }
                            OutlinedButton(onClick = {
                                copy("আপনার নতুন লাইসেন্স কী (Ajwa Inventory):\n\n$key\n\nঅ্যাপ খুলে কী-টি পেস্ট করুন। ৭ দিনের মধ্যে ব্যবহার করুন।")
                            }) { Text("Copy WhatsApp message") }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Issued keys", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(log) { k ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("#${k.serial}  ${k.customer}  (${k.device})", fontWeight = FontWeight.SemiBold)
                        Text("${k.issuedAt} · ${k.days} days · ${k.mode.name.lowercase()} backup", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(k.key.take(24) + "…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { copy(k.key) }) { Text("Copy") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Backups

@Composable
private fun BackupTab(keys: ProviderKeys) {
    var file by remember { mutableStateOf<File?>(null) }
    var data by remember { mutableStateOf<BackupData?>(null) }
    var json by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var newDevice by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Open a backup (.ajwb) received by e-mail or saved in Google Drive", style = MaterialTheme.typography.titleLarge)
        Button(onClick = {
            val f = chooseFile(load = true, "Open backup", "*.ajwb") ?: return@Button
            file = f; error = null; message = null; data = null
            runCatching {
                val text = BackupEnvelope.decryptAsProvider(f.readBytes(), keys.boxPrivate)
                json = text
                data = BackupData.decode(text)
            }.onFailure { error = it.message ?: it.toString() }
        }) { Text("Choose backup file…") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val d = data ?: return@Column
        Text(file?.name ?: "", fontWeight = FontWeight.SemiBold)
        SummaryCard(d)

        Text("Stock (${d.items.size} items)", style = MaterialTheme.typography.titleMedium)
        val units = d.items.sortedBy { it.name }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                units.take(200).forEach { i ->
                    Row {
                        Text(i.name, Modifier.weight(1f))
                        Text("${qty(i.qtyMilli)} ${i.unit}", Modifier.width(120.dp))
                        Text(taka(i.sellingPoisha), Modifier.width(120.dp))
                    }
                }
                if (units.size > 200) Text("… ${units.size - 200} more (export JSON to see all)")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val out = chooseFile(load = false, "Save JSON", (file?.nameWithoutExtension ?: "backup") + ".json") ?: return@OutlinedButton
                out.writeText(json); message = "Saved $out"
            }) { Text("Export as JSON") }
        }

        HorizontalDivider()
        Text("Move this data to a new phone", style = MaterialTheme.typography.titleMedium)
        Text("Enter the new phone's device code. Send the created .ajwr file to the shop (WhatsApp); they open Settings → Restore in the app.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newDevice, { newDevice = it.uppercase() }, label = { Text("New device code") }, singleLine = true)
            Button(onClick = {
                val code = DeviceCode.parse(newDevice.trim())
                if (code == null) { message = "Invalid device code"; return@Button }
                val out = chooseFile(load = false, "Save restore file", "Ajwa_Restore_${DeviceCode.display(code)}.ajwr") ?: return@Button
                out.writeBytes(RestorePackage.create(json, code, keys.signPrivate))
                message = "Created $out"
            }) { Text("Create restore file") }
        }
        message?.let { Text(it) }
    }
}

@Composable
private fun SummaryCard(d: BackupData) {
    val s = d.summary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${s.shopName} — device ${s.deviceCode}", style = MaterialTheme.typography.titleMedium)
            Text("Backup made: ${dateTime(s.createdAt)}   ·   app ${s.appVersion}")
            Text("Last opened: ${dateTime(s.lastOpenedAt)}")
            Text("License #${s.licenseSerial ?: "-"} expires: ${s.licenseExpiresAt?.let(::dateTime) ?: "-"}")
            Text("Items: ${s.itemCount}   ·   out of stock: ${s.outOfStockCount}   ·   low stock: ${s.lowStockCount}")
            Text("Stock value (selling price): ${taka(s.stockValuePoisha)}")
            Text("Sales since previous backup: ${s.salesCountSinceLastBackup} (${taka(s.salesPoishaSinceLastBackup)})   ·   all-time sales: ${s.salesCountTotal}")
        }
    }
}

// ---------------------------------------------------------------- Provider keys

@Composable
private fun KeysTab(keys: ProviderKeys?, onChange: (ProviderKeys) -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Provider keys", style = MaterialTheme.typography.titleLarge)
        Text("Stored in ${AdminStore.keysFile}")
        if (keys == null) {
            Text("No keys yet. Import your keys file, or create new keys (only if no app has been built yet).", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val f = chooseFile(load = true, "Import keys", "*.properties") ?: return@Button
                    val k = AdminStore.loadKeys(f)
                    if (k == null) message = "Not a valid keys file" else { AdminStore.saveKeys(k); onChange(k) }
                }) { Text("Import keys file…") }
                OutlinedButton(onClick = {
                    val k = ProviderKeys.generate(); AdminStore.saveKeys(k); onChange(k)
                    message = "New keys created. Rebuild the Android app with the public keys below."
                }) { Text("Create new keys") }
            }
        } else {
            Text("Keys loaded ✓", color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
            Text("Public keys (these go into provider-keys/public.properties of the Android project):")
            SelectionContainer { Text(AdminStore.publicProperties(keys), fontFamily = FontFamily.Monospace) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val out = chooseFile(load = false, "Save keys backup", "ajwa_provider_keys_BACKUP.properties") ?: return@Button
                    AdminStore.saveKeys(keys, out); message = "Saved a copy to $out — keep it somewhere safe and private."
                }) { Text("Save a backup copy of keys…") }
            }
        }
        message?.let { Text(it) }
    }
}

// ---------------------------------------------------------------- helpers

private fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

private fun chooseFile(load: Boolean, title: String, name: String): File? {
    val dialog = FileDialog(null as Frame?, title, if (load) FileDialog.LOAD else FileDialog.SAVE)
    dialog.file = name
    dialog.isVisible = true
    val f = dialog.file ?: return null
    return File(dialog.directory, f)
}

private val dtf = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")
private fun dateTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dtf)
private fun taka(poisha: Long) = "৳" + NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(poisha / 100.0)
private fun qty(milli: Long) = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 3 }.format(milli / 1000.0)
