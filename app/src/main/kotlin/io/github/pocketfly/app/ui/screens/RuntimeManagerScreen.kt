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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pocketfly.app.AppContainer
import io.github.pocketfly.app.ui.components.SectionHeader
import kotlinx.coroutines.launch

/**
 * Install / switch / import brains. The Sample brain ships with the APK;
 * Lite and Full packages can be imported as `.pflybrain` files.
 */
@Composable
fun RuntimeManagerScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val status by container.brains.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                container.brains.importAndActivate(uri)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
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
                Text("Runtime Manager", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Loaded brain packages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            if (status.loadError != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Text(
                            status.loadError.orEmpty(),
                            Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                SectionHeader(
                    "Installed",
                    "The active package provides the connectome for every " +
                        "game, dashboard and experiment.",
                )
            }
            items(status.entries, key = { it.brain.manifest.id }) { entry ->
                val active = status.active?.manifest?.id == entry.brain.manifest.id
                Card(
                    onClick = { scope.launch { container.brains.activate(entry.brain) } },
                    colors = CardDefaults.cardColors(
                        containerColor = if (active) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Row(
                        Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = active, onClick = {
                            scope.launch { container.brains.activate(entry.brain) }
                        })
                        Column(Modifier.weight(1f)) {
                            Text(entry.brain.manifest.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                entry.brain.mode.label + " · " +
                                    formatCount(entry.brain.manifest.neuronCount.toLong()) + " neurons · " +
                                    formatCount(entry.brain.manifest.edgeCount) + " edges · " +
                                    formatCount(entry.sizeBytes / 1024) + " KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch { container.brains.deleteBrain(entry.brain.manifest.id) }
                            },
                            enabled = !active || status.entries.size > 1,
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                SectionHeader("Get more brains")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Full MaleCNS packages are not bundled. Import a " +
                                ".pflybrain file converted from the original " +
                                "dataset (see docs/connectome-format.md). Data " +
                                "licenses apply.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = {
                            importLauncher.launch(arrayOf("application/octet-stream", "application/zip"))
                        }) {
                            Text("Import .pflybrain")
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
