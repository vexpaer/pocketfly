package io.github.pocketfly.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pocketfly.app.AppContainer
import io.github.pocketfly.core.game.GameSpec
import io.github.pocketfly.core.games.BuiltInGames
import kotlinx.coroutines.launch

/** Create hub: new games, edit custom games, import/export, duplicate built-ins. */
@Composable
fun CreateScreen(
    container: AppContainer,
    onEdit: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    val custom by container.games.customGames.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = container.games.import(uri)
                snackbar.showSnackbar(
                    result.fold(
                        onSuccess = { "Imported \"${it.name}\"" },
                        onFailure = { "Import failed: ${it.message}" },
                    ),
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            item {
                Text(
                    "Create",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    "Build a world, wire it into the brain, save it as a " +
                        "shareable .pocketfly.json file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionChip(icon = Icons.Outlined.Add, label = "New game") {
                        val id = container.games.newDraft("My Experiment")
                        onEdit(id)
                    }
                    ActionChip(icon = Icons.Outlined.UploadFile, label = "Import") {
                        importLauncher.launch(
                            arrayOf("application/octet-stream", "application/json", "text/*"),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                if (custom.isEmpty()) {
                    Text(
                        "No custom games yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            items(custom, key = { it.id }) { spec ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(spec.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${spec.world.objects.size} objects · ${spec.sensory.size} sensory · " +
                                "${spec.actions.size} actions",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            IconButton(onClick = { onEdit(spec.id) }) {
                                Icon(
                                    Icons.Outlined.Edit, contentDescription = "Edit",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { onPlay(spec.id) }) {
                                Icon(
                                    Icons.Outlined.PlayArrow, contentDescription = "Play",
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            ExportButton(container, spec)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                scope.launch { container.games.delete(spec.id) }
                            }) {
                                Icon(
                                    Icons.Outlined.Delete, contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "START FROM A TEMPLATE",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
                )
            }
            items(BuiltInGames.all, key = { "tpl-" + it.id }) { spec ->
                Card(
                    onClick = {
                        val id = container.games.newDraft(spec.name + " (copy)", template = spec)
                        onEdit(id)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Duplicate: ${spec.name}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Opens a copy in the editor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        SnackbarHost(snackbar)
    }
}

@Composable
private fun ExportButton(container: AppContainer, spec: GameSpec) {
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch { container.games.export(uri, spec) }
        }
    }
    IconButton(
        onClick = {
            exportLauncher.launch(spec.name.ifBlank { "game" } + ".pocketfly.json")
        },
    ) {
        Icon(
            Icons.Outlined.Download, contentDescription = "Export",
            tint = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
