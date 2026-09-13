package io.github.pocketfly.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pocketfly.app.AppContainer
import io.github.pocketfly.app.data.GameSession
import io.github.pocketfly.app.data.PerformanceMode
import io.github.pocketfly.app.ui.components.ChannelBar
import io.github.pocketfly.core.engine.WorldEvent
import io.github.pocketfly.core.game.GameSpec
import io.github.pocketfly.core.game.WorldObjectType
import io.github.pocketfly.core.games.BuiltInGames
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Plays one game. The closed loop is real: a [GameSession] drives
 * environment -> runtime -> decoder on the sim thread at the frame rate
 * chosen by the performance mode; this screen renders the latest snapshot.
 */
@Composable
fun GameScreen(
    container: AppContainer,
    gameId: String,
    onBack: () -> Unit,
) {
    val spec = remember(gameId) {
        BuiltInGames.byId(gameId) ?: container.games.customGames.value.firstOrNull { it.id == gameId }
    }
    if (spec == null) {
        Text("Game not found", Modifier.padding(24.dp))
        return
    }

    val scope = rememberCoroutineScope()
    val performance by container.settings.performanceMode.collectAsStateWithLifecycle(PerformanceMode.BALANCED)
    val session = remember(spec) {
        GameSession(
            repo = container.brains,
            spec = spec,
            frameHz = when (performance) {
                PerformanceMode.ECO -> 24
                PerformanceMode.BALANCED -> 36
                PerformanceMode.MAX -> 60
            },
        )
    }

    DisposableEffect(session) {
        session.start(scope)
        onDispose { session.stop() }
    }

    val view by session.view.collectAsStateWithLifecycle()
    val paused by session.paused.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text(spec.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Simplified neural dynamics - not a real fly",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // World canvas.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .aspectRatio(spec.world.width / spec.world.height)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)),
        ) {
            val v = view
            if (v != null) {
                WorldCanvas(spec, v)
            } else {
                Text(
                    "Waiting for the first frame…",
                    Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val v2 = view
            if (v2?.episodeOver == true) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Episode over", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Score %.0f  ·  %.1fs".format(v2.score, v2.elapsed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            onClick = { session.reset(scope) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                "Run again",
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }

        // HUD.
        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                val v = view
                val channels = v?.channels ?: emptyMap()
                val activities = v?.groupActivity ?: emptyMap()
                ChannelBar(
                    label = "Vis L",
                    value = channels["visual.left"] ?: 0f,
                    color = MaterialTheme.colorScheme.secondary,
                )
                ChannelBar(
                    label = "Vis R",
                    value = channels["visual.right"] ?: 0f,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(6.dp))
                ChannelBar(
                    label = "DN L",
                    value = activities["DN_L"] ?: 0f,
                    color = MaterialTheme.colorScheme.primary,
                )
                ChannelBar(
                    label = "DN R",
                    value = activities["DN_R"] ?: 0f,
                    color = MaterialTheme.colorScheme.primary,
                )
                ChannelBar(
                    label = "DN F",
                    value = activities["DN_F"] ?: 0f,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    HudStat("Active", (v?.activeNeurons ?: 0).toString(), Modifier.weight(1f))
                    HudStat("Steps/s", formatCount(v?.stepsPerSecond?.toLong() ?: 0), Modifier.weight(1f))
                    HudStat("Score", "%.0f".format(v?.score ?: 0f), Modifier.weight(1f))
                    HudStat("Step", (v?.totalSteps ?: 0).toString(), Modifier.weight(1f))
                }
            }
        }

        // Controls.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            IconButton(onClick = { if (paused) session.resume() else session.pause() }) {
                Icon(
                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = if (paused) "Resume" else "Pause",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            ControlButton("Step") { session.stepFrames(scope, 1) }
            ControlButton("×10") { session.stepFrames(scope, 10) }
            ControlButton("Reset") { session.reset(scope) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ControlButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun HudStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Renders the world snapshot. Pure function of the latest view state. */
@Composable
private fun WorldCanvas(spec: GameSpec, v: GameSession.View) {
    val flyColor = MaterialTheme.colorScheme.primary
    val dangerColor = Color(0xFFE5735F)
    Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / spec.world.width
        val sy = size.height / spec.world.height

        for (o in v.objects) {
            val color = Color(GameSession.objectColor(o.type)).let {
                if (o.type == WorldObjectType.DANGER) dangerColor else it
            }
            if (o.type == WorldObjectType.SPAWN) continue
            drawRoundRect(
                color = if (o.type == WorldObjectType.LIGHT || o.type == WorldObjectType.FOOD ||
                    o.type == WorldObjectType.GOAL
                ) {
                    color
                } else {
                    color
                },
                topLeft = Offset(o.x * sx, o.y * sy),
                size = androidx.compose.ui.geometry.Size(o.w * sx, o.h * sy),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
            )
        }

        // The fly: a small directional capsule.
        val px = v.bodyX * sx
        val py = v.bodyY * sy
        val r = 0.9f * sx
        withTransform({ rotate(degrees = Math.toDegrees(v.heading.toDouble()).toFloat(), pivot = Offset(px, py)) }) {
            drawOval(
                color = flyColor,
                topLeft = Offset(px - r * 1.6f, py - r * 0.62f),
                size = androidx.compose.ui.geometry.Size(r * 3.2f, r * 1.24f),
            )
            // wing hint
            drawLine(
                color = flyColor.copy(alpha = 0.55f),
                start = Offset(px - r * 0.4f, py),
                end = Offset(px - r * 2.6f, py - r * 1.8f),
                strokeWidth = 2.4f,
            )
            drawLine(
                color = flyColor.copy(alpha = 0.55f),
                start = Offset(px - r * 0.4f, py),
                end = Offset(px - r * 2.6f, py + r * 1.8f),
                strokeWidth = 2.4f,
            )
        }

        // decoded command indicator: steering tick
        if (v.command.steer != 0f) {
            val dir = if (v.command.steer > 0) 1f else -1f
            drawLine(
                color = flyColor.copy(alpha = 0.8f),
                start = Offset(px, py - r * 2.4f),
                end = Offset(px + dir * r * 2.2f, py - r * 3.6f),
                strokeWidth = 2.6f,
            )
        }
    }
}
