package com.basir.inventory.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.basir.inventory.AppContainer
import com.basir.inventory.R
import com.basir.inventory.data.CategoryTree
import com.basir.inventory.data.InventoryException
import com.basir.inventory.data.db.ItemEntity
import com.basir.inventory.data.db.MovementEntity
import com.basir.inventory.data.db.MovementType
import com.basir.inventory.data.db.SupplierEntity
import com.basir.inventory.ui.AppTopBar
import com.basir.inventory.ui.ConfirmDialog
import com.basir.inventory.ui.LocalSnackbar
import com.basir.inventory.ui.NumberField
import com.basir.inventory.ui.Routes
import com.basir.inventory.ui.SectionCard
import com.basir.inventory.ui.appViewModel
import com.basir.inventory.ui.inventoryError
import com.basir.inventory.ui.theme.Good
import com.basir.inventory.ui.theme.LowStock
import com.basir.inventory.ui.theme.OutOfStock
import com.basir.inventory.ui.unitLabel
import com.basir.inventory.util.Fmt
import com.basir.inventory.util.lineTotal
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ItemDetailViewModel(c: AppContainer, itemId: Long) : ViewModel() {
    val repo = c.inventory
    val item = repo.item(itemId).stateIn(this, null)
    val movements = repo.movements(itemId).stateIn(this, emptyList())
    val tree = repo.categories.map(::CategoryTree).stateIn(this, CategoryTree(emptyList()))
    val suppliers = repo.suppliers.stateIn(this, emptyList<SupplierEntity>())
    val threshold = c.settings.globalThresholdMilli.stateIn(this, 5_000L)
}

private enum class QtyDialog { SELL, STOCK_IN, ADJUST }

@Composable
fun ItemDetailScreen(itemId: Long, openSell: Boolean, nav: NavHostController) {
    val vm = appViewModel(key = "item$itemId") { ItemDetailViewModel(it, itemId) }
    val item by vm.item.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val threshold by vm.threshold.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(if (openSell) QtyDialog.SELL else null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val deleteBlocked = stringResource(R.string.delete_item_blocked)

    val it = item ?: run {
        Scaffold(topBar = { AppTopBar("", onBack = { nav.popBackStack() }) }) { p -> Spacer(Modifier.padding(p)) }
        return
    }
    val unit = unitLabel(it.unit)
    val level = stockLevel(it, threshold)

    Scaffold(
        topBar = {
            AppTopBar(it.name, onBack = { nav.popBackStack() }) {
                IconButton(onClick = { nav.navigate(Routes.edit(it.id)) }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text(stringResource(R.string.det_in_stock), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${Fmt.qty(it.qtyMilli)} $unit",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = when (level) { StockLevel.OUT -> OutOfStock; StockLevel.LOW -> LowStock; StockLevel.OK -> Good },
                    )
                    Info(stringResource(R.string.f_sku), it.sku)
                    Info(stringResource(R.string.f_selling_price), Fmt.taka(it.sellingPoisha))
                    Info(stringResource(R.string.f_purchase_price), Fmt.taka(it.purchasePoisha))
                    Info(stringResource(R.string.f_supplier), suppliers.firstOrNull { s -> s.id == it.supplierId }?.name ?: "—")
                    Info(stringResource(R.string.f_category), tree.path(it.categoryId).ifEmpty { "—" })
                    Info(stringResource(R.string.f_threshold), Fmt.qty(it.thresholdMilli ?: threshold) + if (it.thresholdMilli == null) " *" else "")
                }
            }
            item {
                Button(onClick = { dialog = QtyDialog.SELL }, enabled = it.qtyMilli > 0, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(stringResource(R.string.det_sell), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { dialog = QtyDialog.STOCK_IN }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.det_stock_in)) }
                    OutlinedButton(onClick = { dialog = QtyDialog.ADJUST }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.det_adjust)) }
                }
            }
            item { Text(stringResource(R.string.det_history), style = MaterialTheme.typography.titleMedium) }
            items(movements, key = { m -> m.id }) { m -> MovementRow(m, unit); HorizontalDivider() }
        }
    }

    dialog?.let { kind ->
        QuantityDialog(
            kind = kind,
            item = it,
            unit = unit,
            onDismiss = { dialog = null },
            onDone = { msg -> dialog = null; scope.launch { snackbar.showSnackbar(msg) } },
            vm = vm,
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_item_q),
            message = it.name,
            confirm = stringResource(R.string.delete),
            onConfirm = {
                scope.launch {
                    try {
                        vm.repo.deleteItem(it.id)
                        nav.popBackStack()
                    } catch (e: InventoryException) {
                        snackbar.showSnackbar(deleteBlocked)
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MovementRow(m: MovementEntity, unit: String) {
    val label = when (m.type) {
        MovementType.SALE.name -> stringResource(R.string.mv_SALE)
        MovementType.STOCK_IN.name -> stringResource(R.string.mv_STOCK_IN)
        MovementType.ADJUST.name -> stringResource(R.string.mv_ADJUST)
        MovementType.INITIAL.name -> stringResource(R.string.mv_INITIAL)
        else -> stringResource(R.string.mv_RESTORE)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label + (m.note?.let { " · $it" } ?: ""))
            Text(Fmt.dateTime(m.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val sign = if (m.qtyDeltaMilli > 0) "+" else ""
        Text("$sign${Fmt.qty(m.qtyDeltaMilli)} $unit", fontWeight = FontWeight.SemiBold, color = if (m.qtyDeltaMilli < 0) OutOfStock else Good)
    }
}

@Composable
private fun QuantityDialog(
    kind: QtyDialog,
    item: ItemEntity,
    unit: String,
    vm: ItemDetailViewModel,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var qtyText by rememberSaveable { mutableStateOf(if (kind == QtyDialog.ADJUST) Fmt.plainQty(item.qtyMilli) else "") }
    var note by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val qty = Fmt.parseQty(qtyText)

    val errNumber = stringResource(R.string.err_number)
    val errPositive = stringResource(R.string.err_qty_positive)
    val errNotEnough = stringResource(R.string.err_not_enough, "${Fmt.qty(item.qtyMilli)} $unit")
    val soldOk = stringResource(R.string.sold_ok)
    val savedOk = stringResource(R.string.saved_ok)
    var failure by rememberSaveable { mutableStateOf<String?>(null) }

    val title = when (kind) {
        QtyDialog.SELL -> stringResource(R.string.sell_title, item.name)
        QtyDialog.STOCK_IN -> stringResource(R.string.stockin_title, item.name)
        QtyDialog.ADJUST -> stringResource(R.string.adjust_title, item.name)
    }
    val label = if (kind == QtyDialog.ADJUST) stringResource(R.string.adjust_new_qty) else stringResource(R.string.qty_label, unit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(qtyText, { qtyText = it; error = null }, label, error = error ?: failure)
                if (kind == QtyDialog.SELL && qty != null && qty > 0) {
                    Text(stringResource(R.string.sell_total, Fmt.taka(lineTotal(item.sellingPoisha, qty))), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (kind != QtyDialog.SELL) {
                    OutlinedTextField(
                        note, { note = it },
                        label = { Text(if (kind == QtyDialog.ADJUST) stringResource(R.string.adjust_reason) else stringResource(R.string.note_label)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                error = when {
                    qty == null -> errNumber
                    kind != QtyDialog.ADJUST && qty <= 0 -> errPositive
                    kind == QtyDialog.SELL && qty > item.qtyMilli -> errNotEnough
                    else -> null
                }
                if (error != null || qty == null) return@TextButton
                busy = true
                scope.launch {
                    try {
                        when (kind) {
                            QtyDialog.SELL -> vm.repo.sell(item.id, qty)
                            QtyDialog.STOCK_IN -> vm.repo.stockIn(item.id, qty, note)
                            QtyDialog.ADJUST -> vm.repo.adjustTo(item.id, qty, note)
                        }
                        onDone(if (kind == QtyDialog.SELL) soldOk else savedOk)
                    } catch (e: InventoryException) {
                        failure = if (e.reason == InventoryException.Reason.NOT_ENOUGH_STOCK) errNotEnough else e.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
