package app.alertpower.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(
    state: AppState,
    actions: AppActions,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
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
            description = "Fallback used only if the normal wake fails to light the screen.",
            actionLabel = "Allow",
            onAction = actions::requestOverlay,
        )

        SectionTitle("OxygenOS extras")
        TipsCard(onOpenAppSettings = actions::openAppSettings)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SetupStep(
    icon: ImageVector,
    title: String,
    done: Boolean,
    optional: Boolean,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
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
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onAction, shape = CircleShape) { Text(actionLabel) }
            }
        }
    }
}
