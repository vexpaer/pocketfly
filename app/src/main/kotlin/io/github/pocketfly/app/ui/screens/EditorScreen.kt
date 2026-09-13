package io.github.pocketfly.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.pocketfly.app.AppContainer
import io.github.pocketfly.app.data.GameSession
import io.github.pocketfly.app.ui.components.LabeledSlider
import io.github.pocketfly.app.ui.components.SectionHeader
import io.github.pocketfly.core.engine.WorldObject
import io.github.pocketfly.core.game.ActionMapping
import io.github.pocketfly.core.game.GameSpec
import io.github.pocketfly.core.game.ObjectSpec
import io.github.pocketfly.core.game.Rule
import io.github.pocketfly.core.game.SensoryBinding
import io.github.pocketfly.core.game.WorldObjectType
import kotlinx.coroutines.launch

private val EDITOR_CHANNELS = listOf(
    "visual.left", "visual.right", "smell.left", "smell.right",
    "altitude", "obstacle.left", "obstacle.right",
)

private val PLACEABLE = listOf(
    WorldObjectType.WALL, WorldObjectType.FOOD, WorldObjectType.GOAL,
    WorldObjectType.LIGHT, WorldObjectType.DANGER, WorldObjectType.OBSTACLE,
    WorldObjectType.SPAWN,
)

/**
 * World + wiring editor. Edits an in-memory [GameSpec]; Save persists it as
 * a `.pocketfly.json` game in app storage.
 */
@Composable
fun EditorScreen(
    container: AppContainer,
    gameId: String,
    onDone: () -> Unit,
    onPlay: (String) -> Unit,
) {
    val initial = remember(gameId) {
        container.games.draft?.takeIf { it.id == gameId }
            ?: container.games.customGames.value.firstOrNull { it.id == gameId }
    }
    if (initial == null) {
        Column(Modifier.padding(24.dp)) {
            Text("Game not found")
            TextButton(onClick = onDone) { Text("Back") }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var spec by remember(gameId) { mutableStateOf(initial) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf<WorldObjectType?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Mapping/rule dialog state.
    var showSensoryDialog by remember { mutableStateOf(false) }
    var showActionDialog by remember { mutableStateOf(false) }
    var showRuleDialog by remember { mutableStateOf(false) }

    val manifestGroups = container.brains.status.value.active?.manifest?.groups
        ?.map { it.key } ?: listOf("VIS_L", "VIS_R", "OL_L", "OL_R", "DN_L", "DN_R", "DN_F")

    fun updateObject(o: ObjectSpec) {
        spec = spec.copy(
            world = spec.world.copy(
                objects = spec.world.objects.map { if (it.id == o.id) o else it },
            ),
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Header.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val id = container.games.newDraft(spec.name + " (copy)", template = spec)
                val next = container.games.draft
                if (next != null) {
                    spec = next
                }
            }) { Text("Duplicate") }
            Button(onClick = {
                scope.launch {
                    val result = container.games.save(spec)
                    result.fold(
                        onSuccess = { saved ->
                            spec = saved
                            container.games.draft = null
                            snackbar.showSnackbar("Saved")
                            onPlay(saved.id)
                        },
                        onFailure = { saveError = it.message },
                    )
                }
            }) { Text("Save & play") }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = spec.name,
                onValueChange = { spec = spec.copy(name = it) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Tools", "Pick a tool and tap the map to place; drag objects to move; tap to select.")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolChip(null, "Select", tool) { tool = null }
                PLACEABLE.forEach { t ->
                    ToolChip(t, t.name.lowercase().replaceFirstChar { it.uppercase() }, tool) {
                        tool = if (tool == t) null else t
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            EditorCanvas(
                spec = spec,
                selectedId = selectedId,
                tool = tool,
                onTap = { wx, wy ->
                    val hit = spec.world.objects.lastOrNull {
                        wx >= it.x && wx <= it.x + it.w && wy >= it.y && wy <= it.y + it.h
                    }
                    if (tool != null && (hit == null || hit.type == WorldObjectType.SPAWN)) {
                        val n = spec.world.objects.count { it.type == tool }
                        val id = "${tool!!.name.lowercase()}-$n"
                        val size = if (tool == WorldObjectType.SPAWN) 2f else 4f
                        updateObject(
                            ObjectSpec(
                                id = id, type = tool!!,
                                x = (wx - size / 2).coerceIn(0f, spec.world.width - size),
                                y = (wy - size / 2).coerceIn(0f, spec.world.height - size),
                                w = size, h = size,
                                params = if (tool == WorldObjectType.LIGHT) {
                                    mapOf("vx" to -7f, "vy" to 4f, "range" to 48f)
                                } else {
                                    emptyMap()
                                },
                            ),
                        )
                        selectedId = id
                    } else {
                        selectedId = hit?.id
                    }
                },
                onDrag = { wx, wy ->
                    val id = selectedId ?: return@EditorCanvas
                    val cur = spec.world.objects.firstOrNull { it.id == id } ?: return@EditorCanvas
                    updateObject(
                        cur.copy(
                            x = (wx - cur.w / 2).coerceIn(0f, spec.world.width - cur.w),
                            y = (wy - cur.h / 2).coerceIn(0f, spec.world.height - cur.h),
                        ),
                    )
                },
            )

            // Selected object properties.
            val selected = spec.world.objects.firstOrNull { it.id == selectedId }
            if (selected != null) {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(
                                "Selected: ${selected.type.name.lowercase()}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                if (selected.type != WorldObjectType.SPAWN) {
                                    spec = spec.copy(
                                        world = spec.world.copy(
                                            objects = spec.world.objects.filter { it.id != selected.id },
                                        ),
                                    )
                                    selectedId = null
                                }
                            }) { Text("Delete") }
                        }
                        LabeledSlider("X", selected.x, 0f..(spec.world.width - selected.w), steps = 0) { v ->
                            updateObject(selected.copy(x = v))
                        }
                        LabeledSlider("Y", selected.y, 0f..(spec.world.height - selected.h)) { v ->
                            updateObject(selected.copy(y = v))
                        }
                        if (selected.type != WorldObjectType.SPAWN) {
                            LabeledSlider("Width", selected.w, 1f..40f) { v ->
                                updateObject(selected.copy(w = v))
                            }
                            LabeledSlider("Height", selected.h, 1f..40f) { v ->
                                updateObject(selected.copy(h = v))
                            }
                        }
                        if (selected.type == WorldObjectType.LIGHT || selected.type == WorldObjectType.FOOD ||
                            selected.type == WorldObjectType.GOAL || selected.type == WorldObjectType.DANGER
                        ) {
                            val range = selected.params["range"] ?: 40f
                            LabeledSlider("Sensor range", range, 10f..90f) { v ->
                                updateObject(selected.copy(params = selected.params + ("range" to v)))
                            }
                        }
                        if (selected.type == WorldObjectType.LIGHT) {
                            val vx = selected.params["vx"] ?: 0f
                            val vy = selected.params["vy"] ?: 0f
                            LabeledSlider("Velocity X", vx, -15f..15f) { v ->
                                updateObject(selected.copy(params = selected.params + ("vx" to v)))
                            }
                            LabeledSlider("Velocity Y", vy, -15f..15f) { v ->
                                updateObject(selected.copy(params = selected.params + ("vy" to v)))
                            }
                        }
                    }
                }
            }

            // Sensory mappings.
            SectionHeader("Sensory mapping", "World signals -> neural input groups.")
            spec.sensory.forEachIndexed { i, b ->
                MappingRow(
                    title = "${b.channel} → ${b.groupKey}",
                    sub = "gain %.1f  offset %.1f".format(b.gain, b.offset),
                    onRemove = {
                        spec = spec.copy(sensory = spec.sensory.filterIndexed { j, _ -> j != i })
                    },
                )
            }
            TextButton(onClick = { showSensoryDialog = true }) { Text("+ Add sensory mapping") }

            // Action mappings.
            SectionHeader("Action mapping", "Descending group activity -> body actions.")
            spec.actions.forEachIndexed { i, a ->
                MappingRow(
                    title = a.action + " ← " + when (a.decoder) {
                        "difference" -> "${a.rightGroup} − ${a.leftGroup}"
                        else -> a.group
                    },
                    sub = "${a.decoder} · gain %.1f · thr %.2f".format(a.gain, a.threshold),
                    onRemove = {
                        spec = spec.copy(actions = spec.actions.filterIndexed { j, _ -> j != i })
                    },
                )
            }
            TextButton(onClick = { showActionDialog = true }) { Text("+ Add action mapping") }

            // Rules.
            SectionHeader("Rules", "IF activity OP value THEN action. Rules override decoders.")
            spec.rules.forEachIndexed { i, r ->
                MappingRow(
                    title = "IF ${r.source} ${r.operator} ${r.value} THEN ${r.action}",
                    sub = if (r.source2 != null) "vs ${r.source2} · strength %.1f".format(r.strength) else "strength %.1f".format(r.strength),
                    onRemove = {
                        spec = spec.copy(rules = spec.rules.filterIndexed { j, _ -> j != i })
                    },
                )
            }
            TextButton(onClick = { showRuleDialog = true }) { Text("+ Add rule") }

            Spacer(Modifier.height(20.dp))
        }
        if (saveError != null) {
            Text(
                saveError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        SnackbarHost(snackbar)
    }

    if (showSensoryDialog) {
        SensoryDialog(
            groupKeys = manifestGroups,
            onDismiss = { showSensoryDialog = false },
            onAdd = {
                spec = spec.copy(sensory = spec.sensory + it)
                showSensoryDialog = false
            },
        )
    }
    if (showActionDialog) {
        ActionDialog(
            groupKeys = manifestGroups,
            onDismiss = { showActionDialog = false },
            onAdd = {
                spec = spec.copy(actions = spec.actions + it)
                showActionDialog = false
            },
        )
    }
    if (showRuleDialog) {
        RuleDialog(
            groupKeys = manifestGroups,
            onDismiss = { showRuleDialog = false },
            onAdd = {
                spec = spec.copy(rules = spec.rules + it)
                showRuleDialog = false
            },
        )
    }
}

@Composable
private fun ToolChip(
    type: WorldObjectType?,
    label: String,
    current: WorldObjectType?,
    onClick: () -> Unit,
) {
    val active = (type == null && current == null) || (type != null && current == type)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MappingRow(title: String, sub: String, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EditorCanvas(
    spec: GameSpec,
    selectedId: String?,
    tool: WorldObjectType?,
    onTap: (Float, Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(spec.world.width / spec.world.height)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (tool != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(14.dp),
            )
            .pointerInput(spec.world.objects.size, tool) {
                detectTapGestures { offset ->
                    onTap(offset.x / size.width * spec.world.width, offset.y / size.height * spec.world.height)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        onTap(offset.x / size.width * spec.world.width, offset.y / size.height * spec.world.height)
                    },
                    onDrag = { change, _ ->
                        onDrag(
                            change.position.x / size.width * spec.world.width,
                            change.position.y / size.height * spec.world.height,
                        )
                    },
                    onDragEnd = { dragging = false },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / spec.world.width
            val sy = size.height / spec.world.height
            val gridColor = gridLineColor
            // grid
            val gridStep = 10f * sx
            var gx = gridStep
            while (gx < size.width) {
                drawLine(
                    gridColor,
                    Offset(gx, 0f), Offset(gx, size.height), 1f,
                )
                gx += gridStep
            }
            var gy = gridStep
            while (gy < size.height) {
                drawLine(
                    gridColor,
                    Offset(0f, gy), Offset(size.width, gy), 1f,
                )
                gy += gridStep
            }
            for (o in spec.world.objects) {
                if (o.type == WorldObjectType.SPAWN) {
                    drawCircle(
                        Color(0xFF8FA3C4),
                        radius = 6f,
                        center = Offset((o.x + o.w / 2) * sx, (o.y + o.h / 2) * sy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3f),
                    )
                    continue
                }
                drawRoundRect(
                    color = Color(GameSession.objectColor(o.type)),
                    topLeft = Offset(o.x * sx, o.y * sy),
                    size = Size(o.w * sx, o.h * sy),
                    cornerRadius = CornerRadius(6f, 6f),
                )
                if (o.id == selectedId) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(o.x * sx - 3f, o.y * sy - 3f),
                        size = Size(o.w * sx + 6f, o.h * sy + 6f),
                        cornerRadius = CornerRadius(8f, 8f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SensoryDialog(
    groupKeys: List<String>,
    onDismiss: () -> Unit,
    onAdd: (SensoryBinding) -> Unit,
) {
    var channel by remember { mutableStateOf(EDITOR_CHANNELS.first()) }
    var group by remember { mutableStateOf(groupKeys.first()) }
    var gain by remember { mutableStateOf(1f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sensory mapping") },
        text = {
            Column {
                DropdownField("Channel", EDITOR_CHANNELS, channel) { channel = it }
                Spacer(Modifier.height(8.dp))
                DropdownField("Input group", groupKeys, group) { group = it }
                LabeledSlider("Gain", gain, 0f..2f) { gain = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(SensoryBinding(channel, group, gain)) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionDialog(
    groupKeys: List<String>,
    onDismiss: () -> Unit,
    onAdd: (ActionMapping) -> Unit,
) {
    var decoder by remember { mutableStateOf("difference") }
    val actions = listOf("steer", "forward", "flap")
    var action by remember { mutableStateOf("steer") }
    var left by remember { mutableStateOf("DN_L") }
    var right by remember { mutableStateOf("DN_R") }
    var group by remember { mutableStateOf("DN_F") }
    var gain by remember { mutableStateOf(3f) }
    var threshold by remember { mutableStateOf(0.35f) }
    val decoders = listOf("difference", "continuous", "threshold")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Action mapping") },
        text = {
            Column {
                DropdownField("Decoder", decoders, decoder) {
                    decoder = it
                    action = when (it) {
                        "difference" -> "steer"
                        "continuous" -> "forward"
                        else -> "flap"
                    }
                }
                Spacer(Modifier.height(8.dp))
                DropdownField("Action", actions, action) { action = it }
                if (decoder == "difference") {
                    DropdownField("Left group", groupKeys, left) { left = it }
                    Spacer(Modifier.height(8.dp))
                    DropdownField("Right group", groupKeys, right) { right = it }
                } else {
                    Spacer(Modifier.height(8.dp))
                    DropdownField("Group", groupKeys, group) { group = it }
                }
                LabeledSlider("Gain", gain, 0f..5f) { gain = it }
                if (decoder == "threshold" || decoder == "difference") {
                    LabeledSlider("Threshold", threshold, 0f..1f) { threshold = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(
                    ActionMapping(
                        decoder = decoder, action = action,
                        leftGroup = if (decoder == "difference") left else null,
                        rightGroup = if (decoder == "difference") right else null,
                        group = if (decoder != "difference") group else null,
                        gain = gain, threshold = threshold, deadzone = 0.04f,
                    ),
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RuleDialog(
    groupKeys: List<String>,
    onDismiss: () -> Unit,
    onAdd: (Rule) -> Unit,
) {
    val operators = listOf(">", "<", "diff", "ratio", "avg")
    val actions = listOf("turn_left", "turn_right", "forward", "flap", "stop")
    var source by remember { mutableStateOf("DN_R") }
    var source2 by remember { mutableStateOf("DN_L") }
    var op by remember { mutableStateOf(">") }
    var value by remember { mutableStateOf(0.3f) }
    var action by remember { mutableStateOf("turn_right") }
    var strength by remember { mutableStateOf(1f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rule") },
        text = {
            Column {
                DropdownField("IF group", groupKeys, source) { source = it }
                Spacer(Modifier.height(8.dp))
                DropdownField("Operator", operators, op) { op = it }
                if (op == "diff" || op == "ratio" || op == "avg") {
                    Spacer(Modifier.height(8.dp))
                    DropdownField("…vs group", groupKeys, source2) { source2 = it }
                }
                LabeledSlider("Value", value, -1f..2f) { value = it }
                Spacer(Modifier.height(8.dp))
                DropdownField("THEN action", actions, action) { action = it }
                LabeledSlider("Strength", strength, 0f..1f) { strength = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(
                    Rule(
                        source = source,
                        source2 = if (op == "diff" || op == "ratio" || op == "avg") source2 else null,
                        operator = op, value = value, action = action, strength = strength,
                    ),
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
