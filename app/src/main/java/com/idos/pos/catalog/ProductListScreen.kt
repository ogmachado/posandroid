package com.idos.pos.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
 * Product catalog list (task 4.5). A pure composable taking navigation
 * callbacks so it can be wired into a `NavHost` by a later phase — no
 * navigation graph exists yet in Slice A (see MainActivity.kt bootstrap note).
 */
@Composable
fun ProductListScreen(
    onAddProduct: () -> Unit,
    onProductClick: (ProductEntity) -> Unit,
    viewModel: ProductViewModel = posViewModel(LocalAppContainer.current),
) {
    val products by viewModel.products.collectAsState()

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Button(
                onClick = onAddProduct,
                modifier = Modifier.testTag(ADD_PRODUCT_BUTTON_TEST_TAG),
            ) {
                Text("Add product")
            }
            LazyColumn(modifier = Modifier.testTag(PRODUCT_LIST_TEST_TAG)) {
                items(products, key = { it.id }) { product ->
                    ListItem(
                        headlineContent = { Text(product.name) },
                        supportingContent = { Text("${product.code} · ${product.price}") },
                        modifier = Modifier
                            .testTag(productListItemTestTag(product.id))
                            .clickable { onProductClick(product) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

const val ADD_PRODUCT_BUTTON_TEST_TAG = "product-list-add-button"
const val PRODUCT_LIST_TEST_TAG = "product-list"

fun productListItemTestTag(productId: Long) = "product-list-item-$productId"
