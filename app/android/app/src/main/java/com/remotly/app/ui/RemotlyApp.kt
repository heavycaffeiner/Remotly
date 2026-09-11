package com.remotly.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.remotly.app.settings.SettingsState
import com.remotly.app.ui.components.TransferBar
import com.remotly.app.ui.screens.SettingsContent
import com.remotly.app.ui.screens.HerdrWorkspaceScreen
import com.remotly.app.ui.screens.HostEditorScreen
import com.remotly.app.ui.screens.MainTabs
import com.remotly.app.ui.screens.SshTerminalScreen
import com.remotly.app.ui.theme.RemotlyTheme

/**
 * The whole app: one theme, one navigation graph.
 *
 * The transfer bar and its sheet sit above the graph rather than inside a
 * screen. Transfers outlive the screen that started them, so an indicator
 * mounted within one disappears the moment the user navigates anywhere else,
 * which is exactly when they most want to see it.
 */
@Composable
fun RemotlyApp() {
    val settings by SettingsState.settings.collectAsStateWithLifecycle()
    // Nothing is drawn until the stored settings arrive. Composing against
    // the defaults and correcting afterwards shows as a light flash to
    // anyone who chose the dark theme.
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        SettingsState.load()
        ready = true
    }

    RemotlyTheme(settings) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                if (ready) RemotlyNavHost()
                // Pinned to the bottom edge over whatever screen is up,
                // because a transfer belongs to the app rather than to the
                // screen that started it.
                TransferBar(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun RemotlyNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOSTS) {
        composable(Routes.HOSTS) { MainTabs(nav) }
        composable(Routes.SETTINGS) { MainTabs(nav) { SettingsContent() } }

        composable(
            Routes.HOST_EDITOR,
            arguments = listOf(
                navArgument(Routes.ARG_HOST_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            HostEditorScreen(
                hostId = entry.arguments?.getString(Routes.ARG_HOST_ID),
                onDone = { nav.popBackStack() },
            )
        }

        composable(
            Routes.SSH_TERMINAL,
            arguments = listOf(navArgument(Routes.ARG_HOST_ID) { type = NavType.StringType }),
        ) { entry ->
            SshTerminalScreen(
                hostId = entry.arguments?.getString(Routes.ARG_HOST_ID).orEmpty(),
                nav = nav,
            )
        }


        composable(
            Routes.HERDR_WORKSPACE,
            arguments = listOf(
                navArgument(Routes.ARG_HOST_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_HOST_NAME) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.ARG_WORKSPACE_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Routes.ARG_LABEL) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Routes.ARG_SESSION) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val args = entry.arguments
            HerdrWorkspaceScreen(
                hostId = args?.getString(Routes.ARG_HOST_ID).orEmpty(),
                hostName = args?.getString(Routes.ARG_HOST_NAME).orEmpty(),
                workspaceId = args?.getString(Routes.ARG_WORKSPACE_ID),
                label = args?.getString(Routes.ARG_LABEL),
                session = args?.getString(Routes.ARG_SESSION),
                nav = nav,
            )
        }
    }
}
