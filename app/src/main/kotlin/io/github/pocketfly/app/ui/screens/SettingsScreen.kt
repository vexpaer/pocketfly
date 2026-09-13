package io.github.pocketfly.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pocketfly.app.AppContainer
import io.github.pocketfly.app.data.PerformanceMode
import io.github.pocketfly.app.data.ThemeMode
import io.github.pocketfly.app.ui.components.SectionHeader

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onRuntime: () -> Unit,
) {
    val themeMode by container.settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val performance by container.settings.performanceMode.collectAsStateWithLifecycle(PerformanceMode.BALANCED)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.titleMedium)
        }

        SectionHeader("Appearance")
        RadioRow("Follow system", themeMode == ThemeMode.SYSTEM) {
            container.settings.setThemeMode(ThemeMode.SYSTEM)
        }
        RadioRow("Dark", themeMode == ThemeMode.DARK) { container.settings.setThemeMode(ThemeMode.DARK) }
        RadioRow("Light", themeMode == ThemeMode.LIGHT) { container.settings.setThemeMode(ThemeMode.LIGHT) }

        SectionHeader(
            "Performance",
            "Caps the game frame rate and neural steps per frame to keep " +
                "the device cool during long sessions.",
        )
        PerformanceMode.entries.forEach { mode ->
            RadioRow(
                mode.label,
                performance == mode,
                "${mode.frameHz} fps · ${mode.stepsPerFrame} steps/frame",
            ) { container.settings.setPerformanceMode(mode) }
        }

        SectionHeader("Simulation")
        Text(
            "Per-game neural dynamics live on the Brain dashboard. The " +
                "values there are computational parameters of a simplified " +
                "model, not fruit fly physiology.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onRuntime) { Text("Manage brain packages") }

        SectionHeader("Data")
        Text(
            "Games and experiment results are stored locally in app " +
                "storage. Export anything you want to keep from the Create " +
                "and Experiments screens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("Experimental features")
        Text(
            "Compound-eye encoders, olfactory channels and plasticity are " +
                "on the roadmap; the current encoder/decoder interfaces are " +
                "built to accept them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onAbout) { Text("About PocketFly") }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun RadioRow(
    title: String,
    selected: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
