package io.github.pocketfly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pocketfly.app.AppContainer
import io.github.pocketfly.app.data.DynamicsSettings
import io.github.pocketfly.app.ui.components.ActivityLineChart
import io.github.pocketfly.app.ui.components.ChartSeries
import io.github.pocketfly.app.ui.components.ChannelBar
import io.github.pocketfly.app.ui.components.LabeledSlider
import io.github.pocketfly.app.ui.components.SectionHeader
import io.github.pocketfly.app.ui.components.StatCard
import kotlinx.coroutines.launch

/**
 * Brain dashboard: runtime overview, live activity timeline, pause/step
 * control, dynamics tuning, ablation, and the neuron inspector.
 */
@Composable
fun BrainScreen(container: AppContainer) {
    val status by container.brains.status.collectAsStateWithLifecycle()
    val snapshot by container.brains.snapshot.collectAsStateWithLifecycle()
    val history by container.brains.history.collectAsStateWithLifecycle()
    val ablated by container.brains.ablatedGroups.collectAsStateWithLifecycle()
    val running by container.brains.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Free-run while this screen is visible; stop when leaving.
    DisposableEffect(Unit) {
        container.brains.setFreeRun(true)
        onDispose { container.brains.setFreeRun(false) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            "Brain",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            status.active?.manifest?.name ?: "no brain loaded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("Runtime overview")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                "Neurons",
                formatCount((status.active?.manifest?.neuronCount ?: 0).toLong()),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                "Connections",
                formatCount((status.active?.manifest?.edgeCount ?: 0L).toLong()),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                "Mode",
                status.active?.mode?.label ?: "—",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                "Active",
                snapshot.activeNeurons.toString(),
                sub = "last step",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                "Steps/s",
                formatCount(snapshot.stepsPerSecond.toLong()),
                sub = "%.2f ms/step".format(snapshot.meanStepMs),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                "Memory",
                formatCount((snapshot.memoryBytes / 1024).toLong()) + " KB",
                sub = "runtime estimate",
                modifier = Modifier.weight(1f),
            )
        }

        SectionHeader(
            "Activity timeline",
            "Last ~10 s, sampled at UI rate. Simulation runs independently.",
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(14.dp)) {
                ActivityLineChart(
                    series = listOf(
                        ChartSeries(
                            "VIS L", MaterialTheme.colorScheme.secondary,
                            history.buffers["VIS_L"]?.toList().orEmpty(),
                        ),
                        ChartSeries(
                            "VIS R", MaterialTheme.colorScheme.tertiary,
                            history.buffers["VIS_R"]?.toList().orEmpty(),
                        ),
                        ChartSeries(
                            "DN L", MaterialTheme.colorScheme.primary,
                            history.buffers["DN_L"]?.toList().orEmpty(),
                        ),
                        ChartSeries(
                            "DN R", MaterialTheme.colorScheme.error,
                            history.buffers["DN_R"]?.toList().orEmpty(),
                        ),
                        ChartSeries(
                            "DN F", MaterialTheme.colorScheme.tertiary,
                            history.buffers["DN_F"]?.toList().orEmpty(),
                        ),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(MaterialTheme.colorScheme.secondary, "VIS L")
                    LegendDot(MaterialTheme.colorScheme.tertiary, "VIS R")
                    LegendDot(MaterialTheme.colorScheme.primary, "DN L")
                    LegendDot(MaterialTheme.colorScheme.error, "DN R")
                    LegendDot(MaterialTheme.colorScheme.tertiary, "DN F")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StepButton(if (running) "Pause" else "Run") {
                        container.brains.setFreeRun(!running)
                    }
                    StepButton("Step") {
                        container.brains.setFreeRun(false)
                        scope.launch { container.brains.onSim { rt, _ -> rt.step(1); container.brains.publishSnapshot() } }
                    }
                    StepButton("×10") {
                        container.brains.setFreeRun(false)
                        scope.launch { container.brains.onSim { rt, _ -> rt.step(10); container.brains.publishSnapshot() } }
                    }
                    StepButton("Reset") {
                        scope.launch {
                            container.brains.onSim { rt, _ -> rt.reset(); container.brains.publishSnapshot() }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sim steps: ${snapshot.totalSteps}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeader("Channel levels")
        val acts = snapshot.groupActivity
        ChannelBar("VIS L", acts["VIS_L"] ?: 0f, color = MaterialTheme.colorScheme.secondary)
        ChannelBar("VIS R", acts["VIS_R"] ?: 0f, color = MaterialTheme.colorScheme.tertiary)
        ChannelBar("DN L", acts["DN_L"] ?: 0f, color = MaterialTheme.colorScheme.primary)
        ChannelBar("DN R", acts["DN_R"] ?: 0f, color = MaterialTheme.colorScheme.error)
        ChannelBar("DN F", acts["DN_F"] ?: 0f, color = MaterialTheme.colorScheme.tertiary)

        DynamicsSection(container)

        AblationSection(container, status.active?.manifest?.groups?.map { it.key }.orEmpty(), ablated)

        InspectorSection(container)

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .height(6.dp)
                .width(14.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
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
private fun DynamicsSection(container: AppContainer) {
    val dynamics by container.settings.dynamics.collectAsStateWithLifecycle(
        DynamicsSettings(0.82f, 1.0f, 1.0f, 0.02f, true),
    )
    val scope = rememberCoroutineScope()

    SectionHeader(
        "Neural dynamics",
        "Computational parameters of the simplified model — they are NOT " +
            "biologically fitted fruit fly physiology.",
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Use brain defaults", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "off = override with the sliders below",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = dynamics.useBrainDefaults,
                    onCheckedChange = { use ->
                        scope.launch {
                            container.settings.setDynamics(dynamics.copy(useBrainDefaults = use))
                            container.brains.applyDynamicsFromSettings()
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                )
            }
            if (!dynamics.useBrainDefaults) {
                LabeledSlider("Threshold", dynamics.threshold, 0.4f..2.0f) { v ->
                    scope.launch {
                        container.settings.setDynamics(dynamics.copy(threshold = v))
                        container.brains.applyDynamicsFromSettings()
                    }
                }
                LabeledSlider("Decay", dynamics.decay, 0.5f..0.98f) { v ->
                    scope.launch {
                        container.settings.setDynamics(dynamics.copy(decay = v))
                        container.brains.applyDynamicsFromSettings()
                    }
                }
                LabeledSlider("Synaptic gain", dynamics.gain, 0.3f..2.5f) { v ->
                    scope.launch {
                        container.settings.setDynamics(dynamics.copy(gain = v))
                        container.brains.applyDynamicsFromSettings()
                    }
                }
                LabeledSlider("Noise", dynamics.noise, 0f..0.1f) { v ->
                    scope.launch {
                        container.settings.setDynamics(dynamics.copy(noise = v))
                        container.brains.applyDynamicsFromSettings()
                    }
                }
            }
        }
    }
}

@Composable
private fun AblationSection(
    container: AppContainer,
    groupKeys: List<String>,
    ablated: Set<String>,
) {
    val scope = rememberCoroutineScope()
    val editable = groupKeys.filter { it in setOf("VIS_L", "VIS_R", "OL_L", "OL_R", "CX_L", "CX_R", "INT", "DN_L", "DN_R", "DN_F") }

    SectionHeader(
        "Neural ablation",
        "Disable groups to see what breaks. Fully reversible.",
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            if (editable.isEmpty()) {
                Text(
                    "This brain does not expose ablatable groups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            editable.chunked(2).forEach { rowKeys ->
                Row(Modifier.fillMaxWidth()) {
                    for (key in rowKeys) {
                        AblationChip(
                            key = key,
                            ablated = key in ablated,
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp),
                        ) { ablate ->
                            scope.launch { container.brains.setGroupAblated(key, ablate) }
                        }
                    }
                    if (rowKeys.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Button(
                onClick = { scope.launch { container.brains.clearAblations() } },
                enabled = ablated.isNotEmpty(),
            ) {
                Text("Reset all ablations")
            }
        }
    }
}

@Composable
private fun AblationChip(
    key: String,
    ablated: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = { onToggle(!ablated) },
        shape = RoundedCornerShape(10.dp),
        color = if (ablated) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                key,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (ablated) "off" else "on",
                style = MaterialTheme.typography.labelSmall,
                color = if (ablated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InspectorSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val status by container.brains.status.collectAsStateWithLifecycle()

    SectionHeader("Neuron inspector", "Enter a neuron id (0-based) to inspect it.")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter { c -> c.isDigit() } },
                label = { Text("Neuron ID") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val id = input.toIntOrNull()
                    if (id == null) {
                        error = "Enter a numeric id"
                        info = null
                    } else {
                        error = null
                        scope.launch {
                            val n = container.brains.neuronInfo(id)
                            if (n == null) {
                                error = "No brain loaded"
                            } else {
                                val manifest = status.active?.manifest
                                info = buildString {
                                    append("ID ${n.id}\n")
                                    append("Group ${manifest?.groupName(n.groupId)}\n")
                                    append("Type ${manifest?.types?.getOrNull(n.typeId) ?: n.typeId}\n")
                                    append("Region ${manifest?.regions?.getOrNull(n.regionId) ?: n.regionId}\n")
                                    append("Side ${when (n.sideId) { 0 -> "left"; 1 -> "right"; else -> "mid" }}\n")
                                    append("In-degree ${n.inDegree} · Out-degree ${n.outDegree}\n")
                                    append("Activity %.3f · Potential %.3f\n".format(n.activity, n.potential))
                                    append(if (n.enabled) "Enabled" else "ABLATED")
                                }
                            }
                        }
                    }
                },
            ) { Text("Inspect") }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (info != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    info.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
