package com.basir.inventory.ui.stock

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.basir.inventory.data.CategoryTree
import com.basir.inventory.data.db.CategoryEntity
import com.basir.inventory.data.db.ItemEntity
import com.basir.inventory.data.db.SupplierEntity
import com.basir.inventory.ui.AppTopBar
import com.basir.inventory.ui.Routes
import com.basir.inventory.ui.appViewModel
import com.basir.inventory.ui.theme.LowStock
import com.basir.inventory.ui.theme.OutOfStock
import com.basir.inventory.ui.unitLabel
import com.basir.inventory.util.Fmt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers

enum class StockLevel { OK, LOW, OUT }

fun stockLevel(item: ItemEntity, globalThreshold: Long): StockLevel = when {
    item.qtyMilli <= 0 -> StockLevel.OUT
    item.qtyMilli <= (item.thresholdMilli ?: globalThreshold) -> StockLevel.LOW
    else -> StockLevel.OK
}

fun <T> Flow<T>.stateIn(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

private data class Filters(val query: String, val supplierId: Long?, val categoryId: Long?, val lowOnly: Boolean)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class StockViewModel(c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val supplierId = MutableStateFlow<Long?>(null)
    val categoryId = MutableStateFlow<Long?>(null)
    val lowOnly = MutableStateFlow(false)

    val tree: StateFlow<CategoryTree> = c.inventory.categories.map(::CategoryTree).stateIn(this, CategoryTree(emptyList()))
    val suppliers: StateFlow<List<SupplierEntity>> = c.inventory.suppliers.stateIn(this, emptyList())
    val threshold: StateFlow<Long> = c.settings.globalThresholdMilli.stateIn(this, 5_000L)

    private val filters = combine(query.debounce(120), supplierId, categoryId, lowOnly, ::Filters)

    /** Filtering in memory is well under 50 ms for 5,000 items and avoids LIKE/IN query juggling. */
    val items: StateFlow<List<ItemEntity>?> = combine(c.inventory.items, filters, tree, threshold) { all, f, t, global ->
        val q = Fmt.toAscii(f.query.trim()).lowercase()
        val cats = f.categoryId?.let(t::withDescendants)
        all.filter { item ->
            (q.isEmpty() || item.name.lowercase().contains(q) || item.sku.lowercase().contains(q)) &&
                (f.supplierId == null || item.supplierId == f.supplierId) &&
                (cats == null || item.categoryId in cats) &&
                (!f.lowOnly || stockLevel(item, global) != StockLevel.OK)
        }
    }.flowOn(Dispatchers.Default).stateIn(this, null)
}

@Composable
fun InventoryScreen(nav: NavHostController, pickToSell: Boolean) {
    val vm = appViewModel(key = if (pickToSell) "pick" else "stock") { StockViewModel(it) }
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val supplierId by vm.supplierId.collectAsStateWithLifecycle()
    val categoryId by vm.categoryId.collectAsStateWithLifecycle()
    val lowOnly by vm.lowOnly.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val threshold by vm.threshold.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (pickToSell) stringResource(R.string.inv_pick_item) else stringResource(R.string.nav_stock),
                onBack = if (pickToSell) ({ nav.popBackStack() }) else null,
            )
        },
        floatingActionButton = {
            if (!pickToSell) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.edit(0)) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.action_add_item)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                placeholder = { Text(stringResource(R.string.inv_search)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Clear, null) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MenuChip(
                    label = suppliers.firstOrNull { it.id == supplierId }?.name ?: stringResource(R.string.inv_all_suppliers),
                    selected = supplierId != null,
                    allLabel = stringResource(R.string.inv_all_suppliers),
                    options = suppliers,
                    optionLabel = SupplierEntity::name,
                    onSelect = { vm.supplierId.value = it?.id },
                )
                MenuChip(
                    label = tree.get(categoryId)?.name ?: stringResource(R.string.inv_all_categories),
                    selected = categoryId != null,
                    allLabel = stringResource(R.string.inv_all_categories),
                    options = tree.flattened(),
                    optionLabel = { c: CategoryEntity -> "    ".repeat(c.level - 1) + c.name },
                    onSelect = { vm.categoryId.value = it?.id },
                )
                FilterChip(selected = lowOnly, onClick = { vm.lowOnly.value = !lowOnly }, label = { Text(stringResource(R.string.inv_low_only)) })
            }
            val list = items
            if (list != null) {
                Text(
                    stringResource(R.string.inv_count, Fmt.num(list.size)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.inv_empty)) }
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.id }) { item ->
                            ItemRow(
                                item = item,
                                level = stockLevel(item, threshold),
                                category = tree.path(item.categoryId),
                                supplier = suppliers.firstOrNull { it.id == item.supplierId }?.name,
                                onClick = { nav.navigate(Routes.item(item.id, sell = pickToSell)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ItemEntity, level: StockLevel, category: String, supplier: String?, onClick: () -> Unit) {
    val container = when (level) {
        StockLevel.OUT -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val qtyColor: Color = when (level) {
        StockLevel.OUT -> OutOfStock
        StockLevel.LOW -> LowStock
        StockLevel.OK -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (level == StockLevel.OUT) OutOfStock else MaterialTheme.colorScheme.onSurface)
                Text(
                    listOfNotNull(item.sku, supplier, category.ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${Fmt.qty(item.qtyMilli)} ${unitLabel(item.unit)}", fontWeight = FontWeight.Bold, color = qtyColor)
                Text(Fmt.taka(item.sellingPoisha), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun <T> MenuChip(
    label: String,
    selected: Boolean,
    allLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { onSelect(null); open = false })
            options.forEach { o -> DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}
