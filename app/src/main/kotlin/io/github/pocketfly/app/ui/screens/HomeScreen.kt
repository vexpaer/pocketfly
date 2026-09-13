package io.github.pocketfly.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pocketfly.app.AppContainer
import io.github.pocketfly.app.ui.components.StatusDot
import io.github.pocketfly.app.ui.components.StatCard

@Composable
fun HomeScreen(
    container: AppContainer,
    onPlay: () -> Unit,
    onCreate: () -> Unit,
    onBrain: () -> Unit,
    onExperiments: () -> Unit,
    onRuntime: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val status by container.brains.status.collectAsStateWithLifecycle()
    val snapshot by container.brains.snapshot.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "PocketFly",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "A fruit fly brain in your pocket.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                IconButton(onClick = onAbout) {
                    Icon(Icons.Outlined.Info, contentDescription = "About")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Runtime status card.
        Card(
            onClick = onRuntime,
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(active = status.active != null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "RUNTIME",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        status.active?.mode?.label ?: "none",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    status.active?.manifest?.name ?: "No brain loaded",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (status.loadError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        status.loadError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = "Neurons",
                        value = formatCount((status.active?.manifest?.neuronCount ?: 0).toLong()),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Edges",
                        value = formatCount((status.active?.manifest?.edgeCount ?: 0L).toLong()),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = "Active",
                        value = snapshot.activeNeurons.toString(),
                        sub = "neurons spiking",
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Steps/s",
                        value = formatCount(snapshot.stepsPerSecond.toLong()),
                        sub = "%.2f ms/step".format(snapshot.meanStepMs),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            "Simplified neural dynamics over a real connectome structure. " +
                "Not a full biophysical simulation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        HomeAction(icon = Icons.Outlined.SportsEsports, title = "Play", sub = "Closed-loop mini games") { onPlay() }
        HomeAction(icon = Icons.Outlined.Tune, title = "Create", sub = "Build your own experiments") { onCreate() }
        HomeAction(icon = Icons.Outlined.Psychology, title = "Brain", sub = "Activity, inspector, ablation") { onBrain() }
        HomeAction(icon = Icons.Outlined.Biotech, title = "Experiments", sub = "Stimulus protocols & sweeps") { onExperiments() }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun PulsingDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .size(10.dp)
            .alpha(if (active) alpha else 1f)
            .background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
    )
}

@Composable
private fun HomeAction(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fk".format(n / 1_000f)
    else -> n.toString()
}

// Used by the nav host to decide when the bottom bar is visible.
val bottomBarRoutes = setOf("play", "create", "brain", "experiments")

@Composable
fun showBottomBar(route: String?): Boolean = route in bottomBarRoutes
