package za.co.meridiangroup.attendance.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import za.co.meridiangroup.attendance.AppGraph
import za.co.meridiangroup.attendance.MeridianApplication

/** Generic screen state. */
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Content<T>(val value: T, val refreshing: Boolean = false, val message: String? = null) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

fun Context.appGraph(): AppGraph = (applicationContext as MeridianApplication).graph

@Composable
fun rememberGraph(): AppGraph = LocalContext.current.appGraph()

/** `viewModel()` with a manual-DI initializer, keyed so different arguments produce different VMs. */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = rememberGraph()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}
