package com.basir.inventory.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.basir.inventory.R
import com.basir.inventory.data.InventoryException
import com.basir.inventory.data.db.CategoryEntity
import com.basir.inventory.data.db.ItemEntity
import com.basir.inventory.data.db.StockUnit
import com.basir.inventory.data.db.SupplierEntity
import com.basir.inventory.ui.AppTopBar
import com.basir.inventory.ui.DropdownField
import com.basir.inventory.ui.NumberField
import com.basir.inventory.ui.Routes
import com.basir.inventory.ui.appViewModel
import com.basir.inventory.ui.unitLabel
import com.basir.inventory.util.Fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Add (itemId == 0) or edit an item. Stock of existing items changes only via sell/stock-in/adjust. */
@Composable
fun ItemEditScreen(itemId: Long, nav: NavHostController) {
    val isNew = itemId == 0L
    val vm = appViewModel(key = "edit$itemId") { ItemDetailViewModel(it, itemId) }
    val tree by vm.tree.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val globalThreshold by vm.threshold.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var loaded by rememberSaveable { mutableStateOf(isNew) }
    var name by rememberSaveable { mutableStateOf("") }
    var sku by rememberSaveable { mutableStateOf("") }
    var cat1 by rememberSaveable { mutableStateOf<Long?>(null) }
    var cat2 by rememberSaveable { mutableStateOf<Long?>(null) }
    var cat3 by rememberSaveable { mutableStateOf<Long?>(null) }
    var supplierId by rememberSaveable { mutableStateOf<Long?>(null) }
    var unit by rememberSaveable { mutableStateOf(StockUnit.PCS.name) }
    var purchase by rememberSaveable { mutableStateOf("") }
    var selling by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf("") }
    var threshold by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var skuError by rememberSaveable { mutableStateOf<String?>(null) }
    var existing by rememberSaveable { mutableStateOf<Long?>(null) }

    // Load the existing item once into the editable fields.
    LaunchedEffect(itemId) {
        if (isNew || loaded) return@LaunchedEffect
        val item = vm.item.first { it != null } ?: return@LaunchedEffect
        val chain = vm.tree.first { it.all.isNotEmpty() || item.categoryId == null }.chain(item.categoryId)
        name = item.name; sku = item.sku
        cat1 = chain.getOrNull(0)?.id; cat2 = chain.getOrNull(1)?.id; cat3 = chain.getOrNull(2)?.id
        supplierId = item.supplierId; unit = item.unit
        purchase = Fmt.plainMoney(item.purchasePoisha); selling = Fmt.plainMoney(item.sellingPoisha)
        threshold = item.thresholdMilli?.let(Fmt::plainQty).orEmpty()
        existing = item.createdAt
        loaded = true
    }

    val required = stringResource(R.string.err_required)
    val badNumber = stringResource(R.string.err_number)
    val skuTaken = stringResource(R.string.err_sku_taken)
    fun moneyError(text: String) = when {
        !showErrors -> null
        text.isBlank() -> required
        Fmt.parseMoney(text) == null -> badNumber
        else -> null
    }
    val nameError = if (showErrors && name.isBlank()) required else null
    val qtyError = if (showErrors && qty.isNotBlank() && Fmt.parseQty(qty) == null) badNumber else null
    val thresholdError = if (showErrors && threshold.isNotBlank() && Fmt.parseQty(threshold) == null) badNumber else null

    Scaffold(topBar = { AppTopBar(stringResource(if (isNew) R.string.item_new else R.string.item_edit), onBack = { nav.popBackStack() }) }) { padding ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                name, { name = it }, label = { Text(stringResource(R.string.f_name)) }, singleLine = true,
                isError = nameError != null, supportingText = nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                sku, { sku = it.uppercase(); skuError = null }, label = { Text(stringResource(R.string.f_sku)) }, singleLine = true,
                isError = skuError != null, supportingText = { Text(skuError ?: stringResource(R.string.f_sku_auto)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )

            val none = stringResource(R.string.none)
            DropdownField(stringResource(R.string.f_category), tree.children(null), tree.get(cat1), CategoryEntity::name,
                { cat1 = it?.id; cat2 = null; cat3 = null }, noneLabel = none)
            if (cat1 != null && tree.children(cat1).isNotEmpty()) {
                DropdownField(stringResource(R.string.f_subcategory), tree.children(cat1), tree.get(cat2), CategoryEntity::name,
                    { cat2 = it?.id; cat3 = null }, noneLabel = none)
            }
            if (cat2 != null && tree.children(cat2).isNotEmpty()) {
                DropdownField(stringResource(R.string.f_subsubcategory), tree.children(cat2), tree.get(cat3), CategoryEntity::name,
                    { cat3 = it?.id }, noneLabel = none)
            }
            DropdownField(stringResource(R.string.f_supplier), suppliers, suppliers.firstOrNull { it.id == supplierId }, SupplierEntity::name,
                { supplierId = it?.id }, noneLabel = none)

            Text(stringResource(R.string.f_unit), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StockUnit.entries.forEach { u ->
                    FilterChip(selected = unit == u.name, onClick = { unit = u.name }, label = { Text(unitLabel(u.name)) })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(purchase, { purchase = it }, stringResource(R.string.f_purchase_price), Modifier.weight(1f), error = moneyError(purchase))
                NumberField(selling, { selling = it }, stringResource(R.string.f_selling_price), Modifier.weight(1f), error = moneyError(selling))
            }
            if (isNew) {
                NumberField(qty, { qty = it }, stringResource(R.string.f_initial_qty) + " (${unitLabel(unit)})", error = qtyError)
            }
            NumberField(
                threshold, { threshold = it }, stringResource(R.string.f_threshold), error = thresholdError,
                supporting = stringResource(R.string.f_threshold_hint, Fmt.qty(globalThreshold)),
            )

            Button(
                onClick = {
                    showErrors = true
                    val p = Fmt.parseMoney(purchase)
                    val s = Fmt.parseMoney(selling)
                    val q = if (qty.isBlank()) 0L else Fmt.parseQty(qty)
                    val t = if (threshold.isBlank()) null else Fmt.parseQty(threshold)
                    if (name.isBlank() || p == null || s == null || q == null || (threshold.isNotBlank() && t == null)) return@Button
                    val item = ItemEntity(
                        id = itemId,
                        name = name,
                        sku = sku,
                        categoryId = cat3 ?: cat2 ?: cat1,
                        supplierId = supplierId,
                        unit = unit,
                        purchasePoisha = p,
                        sellingPoisha = s,
                        qtyMilli = q,
                        thresholdMilli = t,
                        createdAt = existing ?: 0,
                        updatedAt = 0,
                    )
                    scope.launch {
                        try {
                            val id = vm.repo.saveItem(item, suppliers.firstOrNull { it.id == supplierId }?.name)
                            nav.popBackStack()
                            if (isNew) nav.navigate(Routes.item(id))
                        } catch (e: InventoryException) {
                            if (e.reason == InventoryException.Reason.SKU_TAKEN) skuError = skuTaken
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium) }
        }
    }
}
