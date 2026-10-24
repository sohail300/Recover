package com.bytestudio.deletedsaver.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bytestudio.deletedsaver.App
import com.bytestudio.deletedsaver.capture.Alerts
import com.bytestudio.deletedsaver.capture.Cleanup
import com.bytestudio.deletedsaver.capture.MediaBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Set when the user taps a "deleted" alert. */
    private var deepLink by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLink = intent
        setContent {
            AppTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = HOME) {
                    composable(
                        HOME,
                        arguments = listOf(navArgument("media") { type = NavType.BoolType; defaultValue = false }),
                    ) { entry ->
                        HomeScreen(
                            startOnMedia = entry.arguments?.getBoolean("media") == true,
                            onOpenChat = { pkg, chat -> nav.navigate(chatRoute(pkg, chat)) },
                            onSearch = { nav.navigate("search") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable(
                        "chat/{pkg}/{chat}",
                        arguments = listOf(navArgument("pkg") {}, navArgument("chat") {}),
                    ) { entry ->
                        ChatScreen(
                            pkg = entry.arguments?.getString("pkg").orEmpty(),
                            chat = entry.arguments?.getString("chat").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("search") {
                        SearchScreen(
                            onOpenChat = { pkg, chat -> nav.navigate(chatRoute(pkg, chat)) },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }

                LaunchedEffect(deepLink) {
                    val link = deepLink ?: return@LaunchedEffect
                    deepLink = null
                    val pkg = link.getStringExtra(Alerts.EXTRA_PKG)
                    val chat = link.getStringExtra(Alerts.EXTRA_CHAT)
                    when {
                        pkg != null && chat != null -> nav.navigate(chatRoute(pkg, chat))
                        link.getBooleanExtra(Alerts.EXTRA_MEDIA, false) ->
                            nav.navigate("home?media=true") { popUpTo(nav.graph.id) { inclusive = true } }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink = intent
    }

    override fun onResume() {
        super.onResume()
        val app = application as App
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { Cleanup.run(app) }
            runCatching { MediaBackup.scan(app) }
        }
    }

    private fun chatRoute(pkg: String, chat: String) = "chat/${Uri.encode(pkg)}/${Uri.encode(chat)}"

    private companion object {
        const val HOME = "home?media={media}"
    }
}
