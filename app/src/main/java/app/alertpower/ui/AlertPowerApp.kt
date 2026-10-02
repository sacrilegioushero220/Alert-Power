package app.alertpower.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

private const val SCREEN_HOME = "home"
private const val SCREEN_SETUP = "setup"

@Composable
fun AlertPowerApp(state: AppState, actions: AppActions) {
    var screen by rememberSaveable { mutableStateOf(SCREEN_HOME) }
    var showRestartDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message: (String) -> Unit = { text -> scope.launch { snackbarHostState.showSnackbar(text) } }

    BackHandler(enabled = screen == SCREEN_SETUP) { screen = SCREEN_HOME }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val forward = targetState == SCREEN_SETUP
                val enter = slideInHorizontally { full -> if (forward) full / 5 else -full / 5 } + fadeIn()
                val exit = slideOutHorizontally { full -> if (forward) -full / 5 else full / 5 } + fadeOut()
                enter togetherWith exit
            },
            label = "screen",
        ) { current ->
            when (current) {
                SCREEN_SETUP -> SetupScreen(
                    state = state,
                    actions = actions,
                    contentPadding = padding,
                    onBack = { screen = SCREEN_HOME },
                )
                else -> HomeScreen(
                    state = state,
                    actions = actions,
                    contentPadding = padding,
                    onOpenSetup = { screen = SCREEN_SETUP },
                    onRestart = { showRestartDialog = true },
                    onMessage = message,
                )
            }
        }
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            icon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) },
            title = { Text("Restart phone?") },
            text = {
                Text(
                    "AlertPower opens the power menu and does the two-finger swipe up (or taps Restart) for you. The phone reboots and AlertPower " +
                        "starts again on its own.\n\nPower off is deliberately not offered: without a working power " +
                        "button, a powered-off phone cannot be turned back on."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRestartDialog = false
                    actions.restartPhone()?.let(message)
                }) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) { Text("Cancel") }
            },
        )
    }
}
