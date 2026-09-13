package io.github.pocketfly.app

import android.app.Application
import io.github.pocketfly.app.data.ExperimentsRepository
import io.github.pocketfly.app.data.GamesRepository
import io.github.pocketfly.app.data.RuntimeRepository
import io.github.pocketfly.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PocketFlyApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.initialize()
    }
}

class AppContainer(private val app: PocketFlyApp) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app, appScope)
    val brains = RuntimeRepository(app, appScope, settings)
    val games = GamesRepository(app, appScope)
    val experiments = ExperimentsRepository(app, appScope, brains)

    fun initialize() {
        appScope.launch { brains.initialize() }
    }
}
