package app.alertpower.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onOpenApps: () -> Unit,
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

        SectionTitle("Payments")
        PayModeCard(state = state, actions = actions, onOpenSetup = onOpenSetup)
        PaymentAppsCard(state = state, actions = actions, onOpenApps = onOpenApps, onMessage = onMessage)

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

/** Master switch and status ring. Three states: paused for a payment, setup needed, armed / off. */
@Composable
private fun HeroCard(state: AppState, actions: AppActions, onOpenSetup: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val armed = state.serviceRunning
    val paused = state.a11yPaused
    val accent by animateColorAsState(
        targetValue = when {
            paused -> cs.tertiary
            !state.setupComplete -> cs.secondary
            armed -> cs.primary
            else -> cs.outline
        },
        label = "accent",
    )
    val (title, subtitle) = when {
        paused -> "Paused for payments" to
            (state.pausedFor?.let { "$it is open. Slider wake still works." } ?: "Paused by hand. Slider wake still works.")
        !state.setupComplete -> "Setup needed" to "${state.requiredStepsLeft} required step${if (state.requiredStepsLeft == 1) "" else "s"} left"
        armed -> "Armed" to "Watching the alert slider"
        else -> "Off" to "Turn on to use the slider as power"
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
                        if (paused) Icons.Rounded.Payments else Icons.Rounded.PowerSettingsNew,
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
            if (paused) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = actions::resumeFromPause, modifier = Modifier.fillMaxWidth(), shape = CardShape) {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Resume now")
                }
            }
            if (!state.setupComplete) {
                Spacer(Modifier.height(if (paused) 8.dp else 16.dp))
                FilledTonalButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth(), shape = CardShape) {
                    Icon(Icons.Rounded.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Finish setup")
                }
            }
        }
    }
}

/** Pay mode: explanation, adb-setup warning, auto-pause switch, pause/resume and tile buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayModeCard(state: AppState, actions: AppActions, onOpenSetup: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = state.a11yPaused || state.autoPause
    Surface(shape = CardShape, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    icon = Icons.Rounded.Payments,
                    container = if (active) cs.tertiaryContainer else cs.surfaceContainerHigh,
                    content = if (active) cs.onTertiaryContainer else cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Pay mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Paytm and other payment apps refuse to run while AlertPower's accessibility service is on. " +
                            "Pay mode turns it off while you pay and back on after.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            if (!state.canToggleA11y) {
                Spacer(Modifier.height(12.dp))
                InlineWarning(
                    text = "One-time adb setup needed. Without it AlertPower can turn the service off but not back on.",
                    actionLabel = "Set up",
                    onAction = onOpenSetup,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pause automatically when a payment app opens",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.autoPause, onCheckedChange = actions::setAutoPause)
            }
            if (!state.usageAccessGranted) {
                TextButton(
                    onClick = actions::openUsageAccessSettings,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text("Allow usage access") }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.a11yPaused) {
                    FilledTonalButton(onClick = actions::resumeFromPause, shape = CircleShape) { Text("Resume") }
                } else {
                    FilledTonalButton(
                        onClick = actions::pauseForPayment,
                        shape = CircleShape,
                        enabled = state.accessibilityOn,
                    ) { Text("Pause now") }
                }
                OutlinedButton(onClick = actions::requestQsTile, shape = CircleShape) { Text("Add quick settings tile") }
            }
        }
    }
}

/** "Open through AlertPower": one chip per selected payment app, plus the app picker link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentAppsCard(
    state: AppState,
    actions: AppActions,
    onOpenApps: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CardShape, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Open through AlertPower", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Opening a payment app from here turns the service off first, so the app's own check passes.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (state.paymentApps.isEmpty()) {
                Text(
                    "No payment apps selected yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (app in state.paymentApps) {
                        AppChip(
                            app = app,
                            onOpen = { actions.launchPaymentApp(app.pkg)?.let(onMessage) },
                            onPin = { actions.pinPaymentShortcut(app.pkg)?.let(onMessage) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tap to open. The pin adds a home screen shortcut that does the same.",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenApps, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("Choose apps…")
            }
        }
    }
}

/** Icon + label chip that opens the app, with a small pin button for a launcher shortcut. */
@Composable
private fun AppChip(app: PaymentApp, onOpen: () -> Unit, onPin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = CircleShape,
        color = cs.surfaceContainerHigh,
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon = app.icon, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(app.label, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1)
            IconButton(onClick = onPin, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = "Pin ${app.label} to the home screen",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
