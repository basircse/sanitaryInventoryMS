package com.basir.inventory.ui.settings

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.basir.inventory.AppContainer
import com.basir.inventory.BuildConfig
import com.basir.inventory.R
import com.basir.inventory.data.SettingsRepository
import com.basir.inventory.license.LicenseManager
import com.basir.inventory.license.LicenseState
import com.basir.inventory.shared.BackupData
import com.basir.inventory.shared.BackupMode
import com.basir.inventory.ui.AppTopBar
import com.basir.inventory.ui.LocalSnackbar
import com.basir.inventory.ui.NumberField
import com.basir.inventory.ui.ProviderContact
import com.basir.inventory.ui.Routes
import com.basir.inventory.ui.SectionCard
import com.basir.inventory.ui.appViewModel
import com.basir.inventory.ui.stock.stateIn
import com.basir.inventory.backup.WorkScheduler
import com.basir.inventory.util.Fmt
import com.basir.inventory.util.LocalePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(val c: AppContainer) : ViewModel() {
    val threshold = c.settings.globalThresholdMilli.stateIn(this, 5_000L)
    val lastBackup = c.settings.observeLong(SettingsRepository.LAST_BACKUP).stateIn(this, null)
}

@Composable
fun SettingsScreen(license: LicenseState.Active, nav: NavHostController) {
    val vm = appViewModel { SettingsViewModel(it) }
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val threshold by vm.threshold.collectAsStateWithLifecycle()
    val lastBackup by vm.lastBackup.collectAsStateWithLifecycle()
    var pending by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var restoreCandidate by remember { mutableStateOf<BackupData?>(null) }

    val backupDone = stringResource(R.string.set_backup_done)
    val restoreDone = stringResource(R.string.restore_done)
    val restoreFailed = stringResource(R.string.restore_failed)

    LifecycleResumeEffect(lastBackup) {
        pending = vm.c.backupManager.pendingUploads()
        onPauseOrDispose { }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                restoreCandidate = vm.c.backupManager.readFile(bytes)
            } catch (e: Exception) {
                snackbar.showSnackbar(restoreFailed.format(e.message ?: e.toString()))
            }
        }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.nav_settings)) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text(stringResource(R.string.set_language), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "বাংলা", false to "English").forEach { (bn, label) ->
                        FilterChip(selected = Fmt.bangla == bn, onClick = {
                            if (Fmt.bangla != bn) {
                                LocalePrefs.setBangla(context, bn)
                                (context as? Activity)?.recreate()
                            }
                        }, label = { Text(label) })
                    }
                }
            }

            SectionCard {
                Text(stringResource(R.string.set_threshold), style = MaterialTheme.typography.titleMedium)
                var text by rememberSaveable(threshold) { mutableStateOf(Fmt.plainQty(threshold)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(text, { text = it }, stringResource(R.string.set_threshold_desc), Modifier.weight(1f))
                    val parsed = Fmt.parseQty(text)
                    FilledTonalButton(enabled = parsed != null && parsed != threshold, onClick = {
                        scope.launch { vm.c.settings.setGlobalThreshold(parsed!!) }
                    }) { Text(stringResource(R.string.save)) }
                }
            }

            SectionCard {
                Text(stringResource(R.string.set_catalog), style = MaterialTheme.typography.titleMedium)
                LinkRow(stringResource(R.string.cat_title)) { nav.navigate(Routes.CATEGORIES) }
                LinkRow(stringResource(R.string.sup_title)) { nav.navigate(Routes.SUPPLIERS) }
            }

            SectionCard {
                Text(stringResource(R.string.set_backup), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.set_backup_mode, stringResource(modeLabel(license.backupMode))))
                Text(stringResource(R.string.set_last_backup, lastBackup?.let(Fmt::dateTime) ?: stringResource(R.string.set_never)))
                if (pending > 0) Text(stringResource(R.string.set_pending_upload, Fmt.num(pending)), color = MaterialTheme.colorScheme.tertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                vm.c.backupManager.createBackup()
                                WorkScheduler.uploadPending(context)
                                pending = vm.c.backupManager.pendingUploads()
                                snackbar.showSnackbar(backupDone)
                            } finally { busy = false }
                        }
                    }) { Text(if (busy) stringResource(R.string.working) else stringResource(R.string.set_backup_now)) }
                    OutlinedButton(enabled = !busy, onClick = { pickFile.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.set_restore)) }
                }
            }

            SectionCard {
                Text(stringResource(R.string.set_license), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.set_license_info, Fmt.digits(license.license.serial.toString()), Fmt.date(LicenseManager.expiryDate(license.license))))
                Text(stringResource(R.string.set_device, vm.c.license.deviceCode), fontFamily = FontFamily.Monospace)
                OutlinedButton(onClick = { nav.navigate(Routes.LICENSE) }) { Text(stringResource(R.string.set_enter_key)) }
            }

            ProviderContact()
            Text(stringResource(R.string.set_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }

    restoreCandidate?.let { data ->
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                Text(stringResource(R.string.restore_confirm_msg, Fmt.num(data.items.size), Fmt.num(data.sales.size), Fmt.dateTime(data.summary.createdAt)))
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreCandidate = null
                    busy = true
                    scope.launch {
                        try {
                            vm.c.backupManager.restore(data)
                            snackbar.showSnackbar(restoreDone)
                        } catch (e: Exception) {
                            snackbar.showSnackbar(restoreFailed.format(e.message ?: e.toString()))
                        } finally { busy = false }
                    }
                }) { Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { restoreCandidate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun modeLabel(mode: BackupMode) = when (mode) {
    BackupMode.DAILY -> R.string.mode_DAILY
    BackupMode.WEEKLY -> R.string.mode_WEEKLY
    BackupMode.MONTHLY -> R.string.mode_MONTHLY
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}
