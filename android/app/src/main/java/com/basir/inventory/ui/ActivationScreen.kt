package com.basir.inventory.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.basir.inventory.BuildConfig
import com.basir.inventory.R
import com.basir.inventory.license.ActivationError
import com.basir.inventory.license.LicenseManager
import com.basir.inventory.license.LicenseState
import com.basir.inventory.shared.BackupMode
import com.basir.inventory.util.Fmt
import kotlinx.coroutines.launch

/**
 * Shown when the app is locked (no license, expired, clock tampering) and, with [onBack], for
 * entering a renewal key early.
 */
@Composable
fun ActivationScreen(state: LicenseState, onActivated: (BackupMode) -> Unit, onBack: (() -> Unit)? = null) {
    val license = appContainer().license
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var key by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    val errorText = mapOf(
        ActivationError.MALFORMED to stringResource(R.string.err_key_malformed),
        ActivationError.BAD_SIGNATURE to stringResource(R.string.err_key_signature),
        ActivationError.WRONG_DEVICE to stringResource(R.string.err_key_device),
        ActivationError.ALREADY_USED to stringResource(R.string.err_key_used),
        ActivationError.TOO_OLD to stringResource(R.string.err_key_old),
        ActivationError.FROM_FUTURE to stringResource(R.string.err_key_future),
        ActivationError.CLOCK to stringResource(R.string.act_msg_clock),
    )
    val successFormat = stringResource(R.string.act_success)
    val copiedText = stringResource(R.string.copied)
    val waMessage = stringResource(R.string.wa_device_msg, license.deviceCode)

    val (title, message) = when (state) {
        is LicenseState.Expired -> stringResource(R.string.act_title_expired) to stringResource(R.string.act_msg_expired)
        LicenseState.ClockTampered -> stringResource(R.string.act_title_clock) to stringResource(R.string.act_msg_clock)
        is LicenseState.Active -> stringResource(R.string.set_enter_key) to null
        else -> stringResource(R.string.act_title_new) to stringResource(R.string.act_msg_new)
    }
    val locked = state is LicenseState.Expired || state == LicenseState.ClockTampered

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) { Text("← " + stringResource(R.string.back)) }
        }
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(96.dp))
        Text(stringResource(R.string.shop_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (locked) Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 6.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = if (locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        if (message != null) {
            SectionCard(color = if (locked) MaterialTheme.colorScheme.errorContainer else null) {
                Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }

        // Device code: what the provider needs to issue a key for this phone.
        SectionCard {
            Text(stringResource(R.string.act_device_code), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(license.deviceCode, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(license.deviceCode))
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Default.ContentCopy, contentDescription = null) }
            }
            Text(stringResource(R.string.act_device_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openWhatsApp(context, waMessage) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Send, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.act_send_code))
            }
        }

        if (state != LicenseState.ClockTampered) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; error = null },
                label = { Text(stringResource(R.string.act_key_label)) },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                minLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let { key = it.trim(); error = null } }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.act_paste))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = key.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        license.activate(key).fold(
                            onSuccess = { l ->
                                Toast.makeText(context, successFormat.format(Fmt.date(LicenseManager.expiryDate(l))), Toast.LENGTH_LONG).show()
                                onActivated(BackupMode.fromCode(l.backupMode) ?: BackupMode.DAILY)
                                key = ""
                                onBack?.invoke()
                            },
                            onFailure = { e ->
                                error = (e as? LicenseManager.ActivationException)?.let { errorText[it.error] } ?: e.message
                            },
                        )
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Text(stringResource(R.string.act_activate), style = MaterialTheme.typography.titleMedium)
            }
        }

        ProviderContact()
        Text(stringResource(R.string.act_privacy), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ProviderContact() {
    val context = LocalContext.current
    val waMessage = stringResource(R.string.wa_device_msg, appContainer().license.deviceCode)
    SectionCard {
        Text(stringResource(R.string.act_contact), style = MaterialTheme.typography.labelLarge)
        Text(Fmt.digits(BuildConfig.PROVIDER_PHONE), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + BuildConfig.PROVIDER_PHONE))) }
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Call, null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.act_call))
            }
            OutlinedButton(onClick = { openWhatsApp(context, waMessage) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.Send, null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.act_whatsapp))
            }
        }
    }
}

fun openWhatsApp(context: android.content.Context, message: String) {
    val uri = Uri.parse("https://wa.me/${BuildConfig.PROVIDER_WHATSAPP}?text=" + Uri.encode(message))
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + BuildConfig.PROVIDER_PHONE)))
    }
}
