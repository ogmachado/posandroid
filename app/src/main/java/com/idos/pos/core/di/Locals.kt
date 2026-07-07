package com.idos.pos.core.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Compose bridge for [AppContainer] (design.md "Decision: Manual DI via
 * AppContainer service-locator + Compose bridge"). Composables read the
 * container via [LocalAppContainer]; ViewModels obtain it through [posViewModel].
 */
val LocalAppContainer = compositionLocalOf<AppContainer> {
    error("No AppContainer provided — wrap the composition root with CompositionLocalProvider(LocalAppContainer provides ...)")
}

/**
 * `@Composable` factory helper — builds a [ViewModel] that takes an [AppContainer]
 * as its sole constructor argument, reading the container from [LocalAppContainer]
 * so screens never construct a [ViewModelProvider.Factory] by hand.
 */
@Composable
inline fun <reified VM : ViewModel> posViewModel(container: AppContainer = LocalAppContainer.current): VM {
    val factory = remember(container) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return modelClass.getConstructor(AppContainer::class.java).newInstance(container) as T
            }
        }
    }
    return viewModel(factory = factory)
}
