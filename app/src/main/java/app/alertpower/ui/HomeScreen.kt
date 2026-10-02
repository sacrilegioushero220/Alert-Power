package app.alertpower.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    state: AppState,
    actions: AppActions,
    contentPadding: PaddingValues,
    onOpenSetup: () -> Unit,
    onRestart: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("AlertPower", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Your alert slider is the power button",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSetup) {
                Icon(Icons.Rounded.Tune, contentDescription = "Setup", tint = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(18.dp))

        HeroCard(state = state, actions = actions, onOpenSetup = onOpenSetup)

        SectionTitle("Gestures")
        GestureCard(
            icon = Icons.Rounded.Bolt,
            title = "Wake",
            description = "Screen off: move the slider one notch. The lock screen appears.",
            moves = listOf(Move.Out),
            checked = state.wakeOnSlider,
            onCheckedChange = actions::setWakeOnSlider,
        )
        GestureCard(
            icon = Icons.Rounded.Lock,
            title = "Screen off",
            description = "Screen on: flick the slider and bring it straight back.",
            moves = listOf(Move.Out, Move.Back),
            checked = state.lockOnFlick,
            onCheckedChange = actions::setLockOnFlick,
        )
        GestureCard(
            icon = Icons.Rounded.MoreHoriz,
            title = "Power menu",
            description = "Screen on: flick, back, flick. Then move it back when done.",
            moves = listOf(Move.Out, Move.Back, Move.Out),
            checked = state.powerMenuOnTriple,
            onCheckedChange = actions::setPowerMenuOnTriple,
        )

        SectionTitle("Buttons")
        ToggleCard(
            icon = Icons.Rounded.TouchApp,
            title = "Android accessibility button",
            description = "Tap: screen off. Double tap: power menu. Android never passes press-and-hold on its own button to apps.",
            checked = state.systemButton,
            onCheckedChange = actions::setSystemButton,
            extra = {
                TextButton(
                    onClick = actions::openAccessibilitySettings,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text("Assign the button to AlertPower in Accessibility settings") }
            },
        )
        ToggleCard(
            icon = Icons.Rounded.Adjust,
            title = "AlertPower floating button",
            description = "Drawn by AlertPower, drag it anywhere. Tap: screen off. Press and hold: power menu. Hidden while locked.",
            checked = state.floatingButton,
            onCheckedChange = actions::setFloatingButton,
        )

        SectionTitle("Actions")
        Button(
            onClick = onRestart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CardShape,
            colors = ButtonDefaults.buttonColors(containerColor = cs.tertiary, contentColor = cs.onTertiary),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Restart phone", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { actions.testWake()?.let(onMessage) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = CardShape,
        ) {
            Icon(Icons.Rounded.Science, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Test wake: screen off, back on in 8 s")
        }
        Spacer(Modifier.height(10.dp))
        WarningCard(
            "Never choose Power off in the power menu. Without a working power button, a powered-off phone cannot be turned back on. Restart is always safe."
        )

        SectionTitle("Keep it alive")
        TipsCard(onOpenAppSettings = actions::openAppSettings)

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun HeroCard(state: AppState, actions: AppActions, onOpenSetup: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val armed = state.serviceRunning
    val accent by animateColorAsState(
        targetValue = when {
            !state.setupComplete -> cs.secondary
            armed -> cs.primary
            else -> cs.outline
        },
        label = "accent",
    )
    val (title, subtitle) = when {
        !state.setupComplete -> "Setup needed" to "${state.requiredStepsLeft} required step${if (state.requiredStepsLeft == 1) "" else "s"} left"
        armed -> "Armed" to "Watching the alert slider"
        else -> "Paused" to "Turn on to use the slider as power"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.linearGradient(listOf(cs.surfaceContainerHigh, cs.surfaceContainer)))
            .border(1.dp, accent.copy(alpha = 0.4f), CardShape)
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseRing(active = armed, color = accent) {
                    Icon(
                        Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                Switch(checked = armed, onCheckedChange = actions::setServiceEnabled)
            }
            if (!state.setupComplete) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth(), shape = CardShape) {
                    Icon(Icons.Rounded.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Finish setup")
                }
            }
        }
    }
}
