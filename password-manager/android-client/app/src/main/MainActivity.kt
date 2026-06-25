package com.vaultmanager.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vaultmanager.app.ui.ItemDetailScreen
import com.vaultmanager.app.ui.LoginScreen
import com.vaultmanager.app.ui.VaultListScreen
import com.vaultmanager.app.ui.VaultViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main activity — entry point for the Android app.
 *
 * Security:
 *   - FLAG_SECURE prevents screenshots and app-switcher previews
 *   - Set BEFORE setContent() per spec
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE: prevent screenshots and app-switcher previews
        // Set before setContent() — this is a security requirement
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VaultNavHost()
                }
            }
        }
    }
}

/**
 * Navigation host with all destinations.
 *
 * Routes:
 *   - "login": LoginScreen (start destination)
 *   - "vault_list": VaultListScreen
 *   - "item_detail/{itemId}": ItemDetailScreen
 */
@Composable
fun VaultNavHost() {
    val navController = rememberNavController()
    val viewModel: VaultViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = "login"
    ) {
        composable("login") {
            LoginScreen(
                viewModel = viewModel,
                onLoginSuccess = {
                    navController.navigate("vault_list") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        composable("vault_list") {
            VaultListScreen(
                viewModel = viewModel,
                onItemClick = { itemId ->
                    navController.navigate("item_detail/$itemId")
                },
                onLock = {
                    navController.navigate("login") {
                        popUpTo("vault_list") { inclusive = true }
                    }
                },
                onResetIdle = { viewModel.resetIdleTimer() }
            )
        }

        composable("item_detail/{itemId}") { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString("itemId") ?: ""
            ItemDetailScreen(
                viewModel = viewModel,
                itemId = itemId,
                onBack = { navController.popBackStack() },
                onResetIdle = { viewModel.resetIdleTimer() }
            )
        }
    }
}
