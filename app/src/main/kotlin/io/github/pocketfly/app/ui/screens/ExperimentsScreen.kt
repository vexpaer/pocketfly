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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pocketfly.app.AppContainer
import io.github.pocketfly.app.data.ExperimentsRepository
import io.github.pocketfly.app.ui.components.SectionHeader
import kotlinx.coroutines.launch

/**
 * Scripted stimulus protocols with exported results. All protocols run on
 * the live runtime (game sessions must be closed).
 */
@Composable
fun ExperimentsScreen(container: AppContainer) {
    val running by container.experiments.running.collectAsStateWithLifecycle()
    val progress by container.experiments.progress.collectAsStateWithLifecycle()
    val lastResult by container.experiments.lastResult.collectAsStateWithLifecycle()
    val saved by container.experiments.saved.collectAsStateWithLifecycle()
    val status by container.brains.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<ExperimentsRepository.ExperimentResult?>(null) }

    var exportFileName by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val name = exportFileName
        if (uri != null && name != null) {
            scope.launch { container.experiments.export(uri, name) }
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        item {
            Text(
                "Experiments",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                "Protocols run headlessly on \"${status.active?.manifest?.name ?: "no brain"}\" " +
                    "(${status.active?.mode?.label ?: "—"}). Results are synthetic-model output, " +
                    "not biology.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        items(ExperimentsRepository.Type.entries, key = { it.name }) { type ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(type.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        type.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (running == type) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Button(
                            onClick = { container.experiments.run(type) },
                            enabled = running == null && status.active != null,
                        ) {
                            Text("Run")
                        }
                    }
                }
            }
        }

        if (running != null) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.height(20.dp).padding(end = 10.dp), strokeWidth = 2.dp)
                    Text("Running on the simulation thread…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val result = viewing ?: lastResult
        if (result != null) {
            item {
                SectionHeader("Result", result.title)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        if (result.summary.isNotEmpty()) {
                            result.summary.forEach { (k, v) ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Text(
                                        "%.4f".format(v),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            "stimulus   intensity  DN_L    DN_R    DN_F    active",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        result.rows.forEach { r ->
                            Text(
                                "%-10s %.2f       %.3f   %.3f   %.3f   %d".format(
                                    r.stimulus, r.intensity, r.dnLeft, r.dnRight, r.dnForward, r.activeNeurons,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (result.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                result.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item { SectionHeader("Saved results") }
        if (saved.isEmpty()) {
            item {
                Text(
                    "No saved results yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(saved, key = { it.fileName }) { entry ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            ) {
                Row(
                    Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { viewing = container.experiments.load(entry.fileName) }) {
                        Text("View")
                    }
                    OutlinedButton(onClick = {
                        exportFileName = entry.fileName
                        exportLauncher.launch(entry.fileName.removeSuffix(".json") + "-export.json")
                    }) {
                        Text("Export")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}
