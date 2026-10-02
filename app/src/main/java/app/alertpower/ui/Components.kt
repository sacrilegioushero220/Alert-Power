package app.alertpower.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val CardShape = RoundedCornerShape(24.dp)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 28.dp, bottom = 10.dp, start = 4.dp),
    )
}

/** Small pill used for "Done", "Required", "Optional" and similar states. */
@Composable
fun StatusChip(label: String, tint: Color) {
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Rounded tonal square holding an icon. */
@Composable
fun IconTile(icon: ImageVector, container: Color, content: Color, size: Int = 46) {
    Box(
        modifier = Modifier.size(size.dp).background(container, RoundedCornerShape((size * 0.3).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size((size * 0.52).dp))
    }
}

/** Glowing ring that pulses outward while [active]. */
@Composable
fun PulseRing(active: Boolean, color: Color, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f, targetValue = 1.45f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "scale",
    )
    val fade by transition.animateFloat(
        initialValue = 0.55f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "fade",
    )
    Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size(64.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = fade }
                    .border(2.dp, color, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(color.copy(alpha = if (active) 0.16f else 0.08f), CircleShape)
                .border(1.5.dp, color.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

enum class Move { Out, Back }

/** Pictogram of the slider moves that make up a gesture, with a short caption underneath. */
@Composable
fun MoveSequence(moves: List<Move>, enabled: Boolean) {
    val cs = MaterialTheme.colorScheme
    val tint = if (enabled) cs.primary else cs.outline
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            moves.forEachIndexed { index, move ->
                if (index > 0) Box(Modifier.width(10.dp).height(1.dp).background(tint.copy(alpha = 0.5f)))
                Box(
                    modifier = Modifier.size(26.dp).border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (move == Move.Out) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when (moves.size) {
                1 -> "One move"
                2 -> "There and back"
                else -> "${moves.size} quick moves"
            },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier.alpha(0.9f),
        )
    }
}

/** A gesture row: icon, title, description, pictogram and a switch. */
@Composable
fun GestureCard(
    icon: ImageVector,
    title: String,
    description: String,
    moves: List<Move>,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CardShape, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                icon = icon,
                container = if (checked) cs.primaryContainer else cs.surfaceContainerHigh,
                content = if (checked) cs.onPrimaryContainer else cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                MoveSequence(moves, enabled = checked)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** Generic option row: icon, title, description, optional extra content, and a switch. */
@Composable
fun ToggleCard(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CardShape, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    icon = icon,
                    container = if (checked) cs.secondaryContainer else cs.surfaceContainerHigh,
                    content = if (checked) cs.onSecondaryContainer else cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
            if (extra != null) {
                Spacer(Modifier.height(6.dp))
                extra()
            }
        }
    }
}

@Composable
fun WarningCard(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = CardShape,
        color = cs.errorContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, cs.error.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = cs.error)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
        }
    }
}

@Composable
fun TipsCard(onOpenAppSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CardShape, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "OxygenOS stops background apps during long sleep. This is also why double-tap to wake fades after a while. Do these once:",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Tip("App settings → Battery usage → allow background activity and auto-launch")
            Tip("Settings → Battery → More settings → turn off Sleep standby optimisation")
            Tip("Recents → long-press the AlertPower card → Lock")
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onOpenAppSettings, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                Text("Open AlertPower app settings")
            }
        }
    }
}

@Composable
private fun Tip(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(cs.primary, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
    }
}
