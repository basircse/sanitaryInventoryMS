package com.basir.inventory.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.basir.inventory.AppContainer
import com.basir.inventory.R
import com.basir.inventory.data.db.SaleRow
import com.basir.inventory.license.LicenseState
import com.basir.inventory.ui.Routes
import com.basir.inventory.ui.SectionCard
import com.basir.inventory.ui.appViewModel
import com.basir.inventory.ui.theme.LowStock
import com.basir.inventory.ui.theme.OutOfStock
import com.basir.inventory.ui.unitLabel
import com.basir.inventory.util.Fmt
import com.basir.inventory.util.lineTotal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

data class DashboardStats(
    val totalItems: Int = 0,
    val lowStock: Int = 0,
    val outOfStock: Int = 0,
    val stockValuePoisha: Long = 0,
)

class DashboardViewModel(c: AppContainer) : ViewModel() {
    private val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val stats: StateFlow<DashboardStats> = combine(c.inventory.items, c.settings.globalThresholdMilli) { items, global ->
        DashboardStats(
            totalItems = items.size,
            lowStock = items.count { it.qtyMilli > 0 && it.qtyMilli <= (it.thresholdMilli ?: global) },
            outOfStock = items.count { it.qtyMilli <= 0 },
            stockValuePoisha = items.sumOf { lineTotal(it.sellingPoisha, it.qtyMilli.coerceAtLeast(0)) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardStats())

    val todaySales: StateFlow<Long> = c.inventory.salesTotalSince(startOfToday)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val recent: StateFlow<List<SaleRow>> = c.inventory.recentSales(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun DashboardScreen(license: LicenseState.Active, nav: NavHostController) {
    val vm = appViewModel { DashboardViewModel(it) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    val today by vm.todaySales.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(44.dp))
            Spacer(Modifier.size(10.dp))
            Column {
                Text(stringResource(R.string.shop_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(Fmt.date(LocalDate.now()), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (license.expiringSoon) {
            SectionCard(color = MaterialTheme.colorScheme.errorContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (license.daysLeft == 0) stringResource(R.string.dash_expiry_today)
                        else stringResource(R.string.dash_expiry_warning, Fmt.num(license.daysLeft)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                FilledTonalButton(onClick = { nav.navigate(Routes.LICENSE) }) { Text(stringResource(R.string.dash_enter_key)) }
            }
        }

        StatCard(stringResource(R.string.dash_today_sales), Fmt.taka(today), MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth(), big = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(stringResource(R.string.dash_total_items), Fmt.num(stats.totalItems), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) { nav.navigate(Routes.STOCK) }
            StatCard(stringResource(R.string.dash_stock_value), Fmt.taka(stats.stockValuePoisha), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(stringResource(R.string.dash_low_stock), Fmt.num(stats.lowStock), LowStock, Modifier.weight(1f)) { nav.navigate(Routes.REPORTS) }
            StatCard(stringResource(R.string.dash_out_of_stock), Fmt.num(stats.outOfStock), OutOfStock, Modifier.weight(1f)) { nav.navigate(Routes.REPORTS) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { nav.navigate(Routes.PICK_TO_SELL) }, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Default.PointOfSale, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_sell))
            }
            FilledTonalButton(onClick = { nav.navigate(Routes.edit(0)) }, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_add_item))
            }
        }

        SectionCard {
            Text(stringResource(R.string.dash_recent_sales), style = MaterialTheme.typography.titleMedium)
            if (recent.isEmpty()) Text(stringResource(R.string.dash_no_sales), color = MaterialTheme.colorScheme.onSurfaceVariant)
            recent.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.item(s.itemId)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.itemName ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Fmt.qty(s.qtyMilli)} ${unitLabel(s.unit ?: "")} · ${Fmt.dateTime(s.soldAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Fmt.taka(s.totalPoisha), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier, big: Boolean = false, onClick: (() -> Unit)? = null) {
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        colors = CardDefaults.cardColors(containerColor = if (big) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        }
    }
}
