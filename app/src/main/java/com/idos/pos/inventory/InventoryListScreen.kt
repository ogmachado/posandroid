package com.idos.pos.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel

/**
 * Inventory list screen (task 5.5): every product with its current stock and
 * minimum-stock threshold, joined live via [InventoryRepository.productStockFlow].
 * A pure composable taking a navigation callback so it can be wired into a
 * `NavHost` by a later phase — no navigation graph exists yet in Slice A
 * (matches [com.idos.pos.catalog.ProductListScreen]'s note).
 */
@Composable
fun InventoryListScreen(
    onProductClick: (ProductStockView) -> Unit,
    viewModel: InventoryViewModel = posViewModel(LocalAppContainer.current),
) {
    val stock by viewModel.productStock.collectAsState()

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(modifier = Modifier.testTag(INVENTORY_LIST_TEST_TAG)) {
                items(stock, key = { it.productId }) { row ->
                    ListItem(
                        headlineContent = { Text(row.productName) },
                        supportingContent = {
                            Text("${row.productCode} · stock ${row.stock} · min ${row.minimumStock}")
                        },
                        modifier = Modifier
                            .testTag(inventoryListItemTestTag(row.productId))
                            .clickable { onProductClick(row) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

const val INVENTORY_LIST_TEST_TAG = "inventory-list"

fun inventoryListItemTestTag(productId: Long) = "inventory-list-item-$productId"
