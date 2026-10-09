package com.basir.inventory

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.basir.inventory.backup.WorkScheduler
import com.basir.inventory.data.SettingsRepository
import com.basir.inventory.license.LicenseState
import com.basir.inventory.ui.ActivationScreen
import com.basir.inventory.ui.MainNav
import com.basir.inventory.ui.theme.AjwaTheme
import com.basir.inventory.util.LocalePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as AjwaApp).container

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val state = container.license.refresh()
            container.settings.putLong(SettingsRepository.LAST_OPENED, System.currentTimeMillis())
            if (state is LicenseState.Active) WorkScheduler.scheduleBackups(this@MainActivity, state.backupMode)
            WorkScheduler.scheduleReminders(this@MainActivity)
            WorkScheduler.catchUpIfOverdue(this@MainActivity)
        }
        setContent {
            AjwaTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LicenseGate()
                }
            }
        }
    }

    @Composable
    private fun LicenseGate() {
        val license = container.license
        val state by license.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        // Re-check whenever the app comes back to the foreground, and every minute while open.
        LifecycleResumeEffect(Unit) {
            scope.launch { license.refresh() }
            onPauseOrDispose { }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000)
                license.refresh()
            }
        }

        when (val s = state) {
            LicenseState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(120.dp))
            }
            is LicenseState.Active -> MainNav(s)
            else -> ActivationScreen(state = s, onActivated = { mode -> WorkScheduler.scheduleBackups(this, mode) })
        }
    }
}
