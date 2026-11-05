package com.ruhuiliu.ereader.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ruhuiliu.ereader.AppContainer
import com.ruhuiliu.ereader.reader.ReaderScreen
import com.ruhuiliu.ereader.reader.ReaderViewModel
import com.ruhuiliu.ereader.ui.cloud.CloudBrowserScreen
import com.ruhuiliu.ereader.ui.cloud.CloudViewModel
import com.ruhuiliu.ereader.ui.library.LibraryScreen
import com.ruhuiliu.ereader.ui.library.LibraryViewModel
import com.ruhuiliu.ereader.ui.settings.SettingsScreen
import com.ruhuiliu.ereader.ui.settings.SettingsViewModel

@Composable
fun AppNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            val vm: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(container) } })
            LibraryScreen(
                vm = vm,
                onOpenBook = { nav.navigate("reader/$it") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenCloud = { provider -> nav.navigate("cloud/$provider") },
            )
        }
        composable("reader/{bookId}", arguments = listOf(navArgument("bookId") { type = NavType.LongType })) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: 0L
            val vm: ReaderViewModel = viewModel(factory = viewModelFactory { initializer { ReaderViewModel(container, bookId) } })
            ReaderScreen(
                viewModel = vm,
                fontsDir = container.storage.fontsDir,
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("cloud/{provider}") { entry ->
            val provider = entry.arguments?.getString("provider") ?: "gdrive"
            val vm: CloudViewModel = viewModel(
                key = "cloud-$provider",
                factory = viewModelFactory { initializer { CloudViewModel(container, provider) } },
            )
            CloudBrowserScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenBook = { id -> nav.navigate("reader/$id") { popUpTo("library") } },
            )
        }
    }
}
