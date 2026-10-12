package com.basir.inventory.ui.reports

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.basir.inventory.AppContainer
import com.basir.inventory.R
import com.basir.inventory.data.db.ItemEntity
import com.basir.inventory.data.db.SaleRow
import com.basir.inventory.data.db.SupplierEntity
import com.basir.inventory.ui.AppTopBar
import com.basir.inventory.ui.Routes
import com.basir.inventory.ui.SectionCard
import com.basir.inventory.ui.appViewModel
import com.basir.inventory.ui.stock.StockLevel
import com.basir.inventory.ui.stock.stateIn
import com.basir.inventory.ui.stock.stockLevel
import com.basir.inventory.ui.theme.Good
import com.basir.inventory.ui.theme.LowStock
import com.basir.inventory.ui.theme.OutOfStock
import com.basir.inventory.ui.unitLabel
import com.basir.inventory.util.Fmt
import com.basir.inventory.util.lineTotal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Inclusive date range. */
data class DateRange(val from: LocalDate, val to: LocalDate) {
    private val zone get() = ZoneId.systemDefault()
    val fromMs get() = from.atStartOfDay(zone).toInstant().toEpochMilli()
    val toMsExclusive get() = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(c: AppContainer) : ViewModel() {
    val range = MutableStateFlow(DateRange(LocalDate.now(), LocalDate.now()))
    val sales = range.flatMapLatest { c.inventory.salesBetween(it.fromMs, it.toMsExclusive) }.stateIn(this, emptyList<SaleRow>())
    val items = c.inventory.items.stateIn(this, emptyList<ItemEntity>())
    val suppliers = c.inventory.suppliers.stateIn(this, emptyList<SupplierEntity>())
    val threshold = c.settings.globalThresholdMilli.stateIn(this, 5_000L)
}

@Composable
fun ReportsScreen(nav: NavHostController) {
    val vm = appViewModel { ReportsViewModel(it) }
    var tab by rememberSaveable { mutableStateOf(0) }
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val sales by vm.sales.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val threshold by vm.threshold.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()

    val low = items.filter { stockLevel(it, threshold) != StockLevel.OK }.sortedBy { it.qtyMilli }
    val supplierRows = supplierSummary(items, suppliers, stringResource(R.string.rep_no_supplier))
    val unitNames = mapOf(
        "PCS" to stringResource(R.string.unit_PCS), "BOX" to stringResource(R.string.unit_BOX),
        "FT" to stringResource(R.string.unit_FT), "SET" to stringResource(R.string.unit_SET),
    )
    val tabs = listOf(R.string.rep_low_stock, R.string.rep_sales, R.string.rep_supplier).map { stringResource(it) }

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.nav_reports)) {
                IconButton(onClick = {
                    val csv = when (tab) {
                        0 -> lowStockCsv(low, unitNames)
                        1 -> salesCsv(sales, unitNames)
                        else -> supplierCsv(supplierRows)
                    }
                    shareCsv(context, "Ajwa_${listOf("LowStock", "Sales", "Suppliers")[tab]}_${LocalDate.now()}.csv", csv)
                }) { Icon(Icons.Default.Share, stringResource(R.string.rep_share)) }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> LowStockTab(low, threshold) { nav.navigate(Routes.item(it)) }
                1 -> SalesTab(range, sales, onRange = { vm.range.value = it }) { nav.navigate(Routes.item(it)) }
                else -> SupplierTab(supplierRows)
            }
        }
    }
}

@Composable
private fun Empty() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.rep_no_data)) }

@Composable
private fun LowStockTab(low: List<ItemEntity>, threshold: Long, onOpen: (Long) -> Unit) {
    if (low.isEmpty()) return Empty()
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { Text(stringResource(R.string.rep_items_count, Fmt.num(low.size)), style = MaterialTheme.typography.labelLarge) }
        items(low, key = { it.id }) { item ->
            val out = item.qtyMilli <= 0
            Row(Modifier.fillMaxWidth().clickable { onOpen(item.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (out) OutOfStock else MaterialTheme.colorScheme.onSurface)
                    Text(item.sku + " · ≤ " + Fmt.qty(item.thresholdMilli ?: threshold), style = MaterialTheme.typography.bodySmall)
                }
                Text("${Fmt.qty(item.qtyMilli)} ${unitLabel(item.unit)}", fontWeight = FontWeight.Bold, color = if (out) OutOfStock else LowStock)
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SalesTab(range: DateRange, sales: List<SaleRow>, onRange: (DateRange) -> Unit, onOpen: (Long) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    val presets = listOf(
        stringResource(R.string.rep_today) to DateRange(today, today),
        stringResource(R.string.rep_7days) to DateRange(today.minusDays(6), today),
        stringResource(R.string.rep_month) to DateRange(today.withDayOfMonth(1), today),
    )
    val revenue = sales.sumOf { it.totalPoisha }
    val profit = revenue - sales.sumOf { it.costPoisha }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (label, r) -> FilterChip(selected = range == r, onClick = { onRange(r) }, label = { Text(label) }) }
                FilterChip(selected = presets.none { it.second == range }, onClick = { picking = true }, label = { Text(stringResource(R.string.rep_custom)) })
            }
            Text(
                if (range.from == range.to) Fmt.date(range.from) else "${Fmt.date(range.from)} – ${Fmt.date(range.to)}",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            SectionCard {
                Row {
                    Metric(stringResource(R.string.rep_revenue), Fmt.taka(revenue), Modifier.weight(1f))
                    Metric(stringResource(R.string.rep_profit), Fmt.taka(profit), Modifier.weight(1f), if (profit >= 0) Good else OutOfStock)
                    Metric(stringResource(R.string.rep_count), Fmt.num(sales.size), Modifier.weight(0.7f))
                }
            }
        }
        if (sales.isEmpty()) item { Text(stringResource(R.string.rep_no_data), Modifier.padding(16.dp)) }
        items(sales, key = { it.id }) { s ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(s.itemId) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.itemName ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Fmt.qty(s.qtyMilli)} ${unitLabel(s.unit ?: "")} × ${Fmt.taka(s.unitPoisha)} · ${Fmt.dateTime(s.soldAt)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(Fmt.taka(s.totalPoisha), fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = range.from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = range.to.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = state.selectedStartDateMillis
                    if (start != null) {
                        val from = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
                        val to = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: from
                        onRange(DateRange(from, to))
                    }
                    picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

data class SupplierRow(val name: String, val items: Int, val sellValue: Long, val costValue: Long)

private fun supplierSummary(items: List<ItemEntity>, suppliers: List<SupplierEntity>, noSupplier: String): List<SupplierRow> {
    val names = suppliers.associate { it.id to it.name }
    return items.groupBy { it.supplierId }.map { (id, list) ->
        SupplierRow(
            name = id?.let(names::get) ?: noSupplier,
            items = list.size,
            sellValue = list.sumOf { lineTotal(it.sellingPoisha, it.qtyMilli.coerceAtLeast(0)) },
            costValue = list.sumOf { lineTotal(it.purchasePoisha, it.qtyMilli.coerceAtLeast(0)) },
        )
    }.sortedByDescending { it.sellValue }
}

@Composable
private fun SupplierTab(rows: List<SupplierRow>) {
    if (rows.isEmpty()) return Empty()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.name }) { r -> SupplierCard(r) }
        item {
            SupplierCard(SupplierRow(stringResource(R.string.rep_total), rows.sumOf { it.items }, rows.sumOf { it.sellValue }, rows.sumOf { it.costValue }), highlight = true)
        }
    }
}

@Composable
private fun SupplierCard(r: SupplierRow, highlight: Boolean = false) {
    SectionCard(color = if (highlight) MaterialTheme.colorScheme.primaryContainer else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.rep_items_count, Fmt.num(r.items)), style = MaterialTheme.typography.bodySmall)
        }
        Row {
            Metric(stringResource(R.string.rep_sell_value), Fmt.taka(r.sellValue), Modifier.weight(1f))
            Metric(stringResource(R.string.rep_cost_value), Fmt.taka(r.costValue), Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- CSV export

private fun csvLine(vararg cells: Any?): String =
    cells.joinToString(",") { c -> "\"" + (c?.toString() ?: "").replace("\"", "\"\"") + "\"" }

private fun lowStockCsv(items: List<ItemEntity>, units: Map<String, String>) = buildString {
    appendLine(csvLine("Name", "SKU", "Quantity", "Unit", "Limit"))
    items.forEach { appendLine(csvLine(it.name, it.sku, Fmt.plainQty(it.qtyMilli), units[it.unit], it.thresholdMilli?.let(Fmt::plainQty))) }
}

private fun salesCsv(sales: List<SaleRow>, units: Map<String, String>) = buildString {
    appendLine(csvLine("Date", "Item", "Quantity", "Unit", "Unit price", "Total", "Cost"))
    sales.forEach {
        appendLine(csvLine(
            Instant.ofEpochMilli(it.soldAt).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0),
            it.itemName, Fmt.plainQty(it.qtyMilli), units[it.unit], Fmt.plainMoney(it.unitPoisha),
            Fmt.plainMoney(it.totalPoisha), Fmt.plainMoney(it.costPoisha),
        ))
    }
}

private fun supplierCsv(rows: List<SupplierRow>) = buildString {
    appendLine(csvLine("Supplier", "Items", "Value at selling price", "Value at purchase price"))
    rows.forEach { appendLine(csvLine(it.name, it.items, Fmt.plainMoney(it.sellValue), Fmt.plainMoney(it.costValue))) }
}

private fun shareCsv(context: Context, name: String, csv: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, name)
    // BOM so Excel shows Bangla text correctly.
    file.writeText("﻿" + csv)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, name))
}
