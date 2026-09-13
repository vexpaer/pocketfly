package io.github.pocketfly.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.pocketfly.app.data.ThemeMode
import io.github.pocketfly.app.ui.screens.AboutScreen
import io.github.pocketfly.app.ui.screens.BrainScreen
import io.github.pocketfly.app.ui.screens.CreateScreen
import io.github.pocketfly.app.ui.screens.EditorScreen
import io.github.pocketfly.app.ui.screens.ExperimentsScreen
import io.github.pocketfly.app.ui.screens.GameScreen
import io.github.pocketfly.app.ui.screens.HomeScreen
import io.github.pocketfly.app.ui.screens.PlayScreen
import io.github.pocketfly.app.ui.screens.RuntimeManagerScreen
import io.github.pocketfly.app.ui.screens.SettingsScreen
import io.github.pocketfly.app.ui.screens.showBottomBar
import io.github.pocketfly.app.ui.theme.PocketFlyTheme

object Routes {
    const val HOME = "home"
    const val PLAY = "play"
    const val GAME = "game/{gameId}"
    const val CREATE = "create"
    const val EDITOR = "editor/{gameId}"
    const val BRAIN = "brain"
    const val EXPERIMENTS = "experiments"
    const val RUNTIME = "runtime"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun game(gameId: String) = "game/$gameId"
    fun editor(gameId: String) = "editor/$gameId"
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.PLAY, "Play", Icons.Outlined.SportsEsports),
    BottomDestination(Routes.CREATE, "Create", Icons.Outlined.Tune),
    BottomDestination(Routes.BRAIN, "Brain", Icons.Outlined.Psychology),
    BottomDestination(Routes.EXPERIMENTS, "Experiments", Icons.Outlined.Biotech),
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PocketFlyApp).container
        setContent {
            val themeMode by container.settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            PocketFlyTheme(dark = themeMode != ThemeMode.LIGHT) {
                PocketFlyNavHost(container)
            }
        }
    }
}

@Composable
private fun PocketFlyNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar(currentRoute)) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    bottomDestinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    onPlay = { navController.navigate(Routes.PLAY) },
                    onCreate = { navController.navigate(Routes.CREATE) },
                    onBrain = { navController.navigate(Routes.BRAIN) },
                    onExperiments = { navController.navigate(Routes.EXPERIMENTS) },
                    onRuntime = { navController.navigate(Routes.RUNTIME) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onAbout = { navController.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.PLAY) {
                PlayScreen(
                    container = container,
                    onOpenGame = { navController.navigate(Routes.game(it)) },
                    onImport = { navController.navigate(Routes.CREATE) },
                )
            }
            composable(Routes.GAME) { entry ->
                GameScreen(
                    container = container,
                    gameId = entry.arguments?.getString("gameId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CREATE) {
                CreateScreen(
                    container = container,
                    onEdit = { navController.navigate(Routes.editor(it)) },
                    onPlay = { navController.navigate(Routes.game(it)) },
                )
            }
            composable(Routes.EDITOR) { entry ->
                EditorScreen(
                    container = container,
                    gameId = entry.arguments?.getString("gameId").orEmpty(),
                    onDone = { navController.popBackStack() },
                    onPlay = { id ->
                        navController.popBackStack()
                        navController.navigate(Routes.game(id))
                    },
                )
            }
            composable(Routes.BRAIN) {
                BrainScreen(container = container)
            }
            composable(Routes.EXPERIMENTS) {
                ExperimentsScreen(container = container)
            }
            composable(Routes.RUNTIME) {
                RuntimeManagerScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onAbout = { navController.navigate(Routes.ABOUT) },
                    onRuntime = { navController.navigate(Routes.RUNTIME) },
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
