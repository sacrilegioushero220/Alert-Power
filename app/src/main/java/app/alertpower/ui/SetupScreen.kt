package app.alertpower.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private const val ADB_GRANT_SECURE_SETTINGS = "adb shell pm grant app.alertpower android.permission.WRITE_SECURE_SETTINGS"
private const val ADB_ALLOW_USAGE_STATS = "adb shell appops set app.alertpower GET_USAGE_STATS allow"

@Composable
fun SetupScreen(
    state: AppState,
    actions: AppActions,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val done = 3 - state.requiredStepsLeft
    val progress by animateFloatAsState(targetValue = done / 3f, label = "progress")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text("Setup", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.setupComplete) "All required steps are done" else "$done of 3 required steps done",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 20.dp)
                .height(6.dp)
                .clip(CircleShape),
            color = cs.tertiary,
            trackColor = cs.surfaceContainerHigh,
        )

        SetupStep(
            icon = Icons.Rounded.Notifications,
            title = "Notifications",
            done = state.notificationsGranted,
            optional = false,
            description = "Lets the slider service show its status and stay alive as a foreground service.",
            actionLabel = "Allow",
            onAction = actions::requestNotifications,
        )
        SetupStep(
            icon = Icons.Rounded.Accessibility,
            title = "Accessibility service",
            done = state.accessibilityOn,
            optional = false,
            description = "Turns the screen off, opens the power menu and taps Restart for you. Find \"AlertPower\" in the list and turn it on. AlertPower never reads your screen otherwise.",
            actionLabel = "Open accessibility settings",
            onAction = actions::openAccessibilitySettings,
            secondaryLabel = if (state.canToggleA11y) "Turn on now" else null,
            onSecondary = {
                if (!actions.turnAccessibilityOn()) onMessage("Could not turn the service on. Use Accessibility settings.")
            },
        )
        SetupStep(
            icon = Icons.Rounded.BatteryChargingFull,
            title = "Unrestricted battery",
            done = state.batteryUnrestricted,
            optional = false,
            description = "OxygenOS kills background apps during long sleep. This keeps the slider service alive.",
            actionLabel = "Allow",
            onAction = actions::requestBatteryUnrestricted,
        )
        SetupStep(
            icon = Icons.Rounded.Layers,
            title = "Display over other apps",
            done = state.overlayGranted,
            optional = true,
            description = "Fallback used only if the normal wake fails to light the screen. " +
                "Google Pay refuses to run while a sideloaded app holds this, so leave it off unless the normal wake fails.",
            actionLabel = "Allow",
            onAction = actions::requestOverlay,
        )

        SectionTitle("Pay mode")
        SetupStep(
            icon = Icons.Rounded.Terminal,
            title = "Settings permission (adb)",
            done = state.canToggleA11y,
            optional = true,
            description = "Lets AlertPower turn its accessibility service back on after a payment. Granted once from a computer.",
            actionLabel = "Copy command",
            onAction = { actions.copyToClipboard("adb", ADB_GRANT_SECURE_SETTINGS) },
            extra = {
                CommandBox(command = ADB_GRANT_SECURE_SETTINGS, onCopy = { actions.copyToClipboard("adb", ADB_GRANT_SECURE_SETTINGS) })
                if (state.permissionMonitoringOn == true) {
                    Spacer(Modifier.height(10.dp))
                    InlineWarning(
                        "OxygenOS is blocking adb grants. Turn on Developer options › Disable permission monitoring first, then run the command."
                    )
                }
            },
        )
        SetupStep(
            icon = Icons.Rounded.DataUsage,
            title = "Usage access",
            done = state.usageAccessGranted,
            optional = true,
            description = "Lets AlertPower notice when you leave the payment app so it can turn the service back on.",
            actionLabel = "Allow",
            onAction = actions::openUsageAccessSettings,
            secondaryLabel = "Copy adb command",
            onSecondary = { actions.copyToClipboard("adb", ADB_ALLOW_USAGE_STATS) },
            extra = {
                Text(
                    "Also works from a computer:",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                CommandBox(command = ADB_ALLOW_USAGE_STATS, onCopy = { actions.copyToClipboard("adb", ADB_ALLOW_USAGE_STATS) })
            },
        )

        SectionTitle("OxygenOS extras")
        TipsCard(onOpenAppSettings = actions::openAppSettings)
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * One setup step. [extra] is shown under the description while the step is not done, before the
 * buttons; [secondaryLabel] adds an outlined second button next to the main action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupStep(
    icon: ImageVector,
    title: String,
    done: Boolean,
    optional: Boolean,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val chipTint = when {
        done -> cs.tertiary
        optional -> cs.onSurfaceVariant
        else -> cs.secondary
    }
    Surface(
        shape = CardShape,
        color = if (done) cs.surfaceContainerLow else cs.surfaceContainer,
        border = if (done) null else BorderStroke(1.dp, chipTint.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    icon = if (done) Icons.Rounded.CheckCircle else icon,
                    container = if (done) cs.tertiaryContainer else cs.primaryContainer,
                    content = if (done) cs.onTertiaryContainer else cs.onPrimaryContainer,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    StatusChip(
                        label = when {
                            done -> "Done"
                            optional -> "Optional"
                            else -> "Required"
                        },
                        tint = chipTint,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (!done) {
                if (extra != null) {
                    Spacer(Modifier.height(12.dp))
                    extra()
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = onAction, shape = CircleShape) { Text(actionLabel) }
                    if (secondaryLabel != null && onSecondary != null) {
                        OutlinedButton(onClick = onSecondary, shape = CircleShape) { Text(secondaryLabel) }
                    }
                }
            }
        }
    }
}

/** A shell command in monospace with a copy button. */
@Composable
private fun CommandBox(command: String, onCopy: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "Copy command",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
