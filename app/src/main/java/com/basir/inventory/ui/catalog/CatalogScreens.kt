package com.basir.inventory.ui.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.basir.inventory.AppContainer
import com.basir.inventory.R
import com.basir.inventory.data.CategoryTree
import com.basir.inventory.data.InventoryException
import com.basir.inventory.data.db.CategoryEntity
import com.basir.inventory.data.db.SupplierEntity
import com.basir.inventory.ui.AppTopBar
import com.basir.inventory.ui.ConfirmDialog
import com.basir.inventory.ui.LocalSnackbar
import com.basir.inventory.ui.TextInputDialog
import com.basir.inventory.ui.appViewModel
import com.basir.inventory.ui.stock.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CatalogViewModel(c: AppContainer) : ViewModel() {
    val repo = c.inventory
    val tree = repo.categories.map(::CategoryTree).stateIn(this, CategoryTree(emptyList()))
    val suppliers = repo.suppliers.stateIn(this, emptyList())
}

private sealed interface CatDialog {
    data class Add(val parent: CategoryEntity?) : CatDialog
    data class Rename(val category: CategoryEntity) : CatDialog
    data class Delete(val category: CategoryEntity) : CatDialog
}

@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val vm = appViewModel { CatalogViewModel(it) }
    val tree by vm.tree.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<CatDialog?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val inUse = stringResource(R.string.cat_in_use)

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.cat_title), onBack = onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { dialog = CatDialog.Add(null) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.cat_add_root)) })
        },
    ) { padding ->
        val list = tree.flattened()
        if (list.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.cat_empty)) }
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(list, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().padding(start = (16 + 24 * (c.level - 1)).dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (c.level > 1) Icon(Icons.Default.SubdirectoryArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(c.name, Modifier.weight(1f).padding(vertical = 14.dp, horizontal = 6.dp), fontWeight = if (c.level == 1) FontWeight.SemiBold else FontWeight.Normal)
                    if (c.level < 3) IconButton(onClick = { dialog = CatDialog.Add(c) }) { Icon(Icons.Default.Add, stringResource(R.string.cat_add_child)) }
                    IconButton(onClick = { dialog = CatDialog.Rename(c) }) { Icon(Icons.Default.Edit, stringResource(R.string.rename)) }
                    IconButton(onClick = { dialog = CatDialog.Delete(c) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                }
                HorizontalDivider()
            }
        }
    }

    when (val d = dialog) {
        is CatDialog.Add -> TextInputDialog(
            title = d.parent?.let { "${it.name} › ${stringResource(R.string.cat_add_child)}" } ?: stringResource(R.string.cat_add_root),
            label = stringResource(R.string.cat_name),
            onSave = { name, _ -> scope.launch { vm.repo.addCategory(name, d.parent) }; dialog = null },
            onDismiss = { dialog = null },
        )
        is CatDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.cat_name),
            initial = d.category.name,
            onSave = { name, _ -> scope.launch { vm.repo.renameCategory(d.category, name) }; dialog = null },
            onDismiss = { dialog = null },
        )
        is CatDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.delete) + "?",
            message = d.category.name,
            confirm = stringResource(R.string.delete),
            onConfirm = {
                scope.launch {
                    try { vm.repo.deleteCategory(d.category) } catch (e: InventoryException) { snackbar.showSnackbar(inUse) }
                }
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
fun SuppliersScreen(onBack: () -> Unit) {
    val vm = appViewModel { CatalogViewModel(it) }
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SupplierEntity?>(null) }
    var deleting by remember { mutableStateOf<SupplierEntity?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val inUse = stringResource(R.string.sup_in_use)
    val nameTaken = stringResource(R.string.name_taken)

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.sup_title), onBack = onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = SupplierEntity(name = "") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.sup_add)) })
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(suppliers, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold)
                        s.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                    IconButton(onClick = { deleting = s }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { s ->
        TextInputDialog(
            title = if (s.id == 0L) stringResource(R.string.sup_add) else stringResource(R.string.edit),
            label = stringResource(R.string.cat_name),
            initial = s.name,
            secondLabel = stringResource(R.string.sup_phone),
            secondInitial = s.phone.orEmpty(),
            onSave = { name, phone ->
                scope.launch {
                    try { vm.repo.saveSupplier(s.copy(name = name, phone = phone)) } catch (e: InventoryException) { snackbar.showSnackbar(nameTaken) }
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = stringResource(R.string.delete) + "?",
            message = s.name,
            confirm = stringResource(R.string.delete),
            onConfirm = { scope.launch { try { vm.repo.deleteSupplier(s) } catch (e: InventoryException) { snackbar.showSnackbar(inUse) } } },
            onDismiss = { deleting = null },
        )
    }
}
