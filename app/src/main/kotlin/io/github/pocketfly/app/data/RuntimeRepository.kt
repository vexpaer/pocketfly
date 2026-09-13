package io.github.pocketfly.app.data

import android.content.Context
import io.github.pocketfly.sim.BrainManifest
import io.github.pocketfly.sim.BrainPackage
import io.github.pocketfly.sim.InstalledBrain
import io.github.pocketfly.sim.PocketFlyRuntime
import io.github.pocketfly.sim.RuntimeMode
import io.github.pocketfly.sim.SimulationStats
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the single native runtime instance and the simulation thread.
 *
 * Threading contract: every call into [PocketFlyRuntime] happens on the
 * dedicated sim dispatcher. UI reads immutable [Snapshot]/[History] values.
 */
class RuntimeRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private val simExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pocketfly-sim").apply { isDaemon = false }
    }
    val simDispatcher: CoroutineDispatcher = simExecutor.asCoroutineDispatcher()

    private var runtime: PocketFlyRuntime? = null

    val brainsDir: File
        get() = File(context.filesDir, "brains").apply { mkdirs() }

    // ------------------------------------------------------------------ status

    data class BrainEntry(
        val brain: InstalledBrain,
        val sizeBytes: Long,
        val bundled: Boolean,
    )

    data class Status(
        val entries: List<BrainEntry> = emptyList(),
        val active: InstalledBrain? = null,
        val loadError: String? = null,
        val initializing: Boolean = true,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // ---------------------------------------------------------------- snapshot

    data class Snapshot(
        val stepsPerSecond: Double = 0.0,
        val meanStepMs: Double = 0.0,
        val totalSteps: Long = 0,
        val activeNeurons: Int = 0,
        val ablatedNeurons: Int = 0,
        val memoryBytes: Double = 0.0,
        val groupActivity: Map<String, Float> = emptyMap(),
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Ring buffers of recent group activity, sampled at publish time. */
    class History(
        val keys: List<String>,
        val capacity: Int = 180,
        val buffers: Map<String, FloatArray> = emptyMap(),
        val size: Int = 0,
    ) {
        fun push(samples: Map<String, Float>): History {
            val next = buffers.mapValues { (k, buf) ->
                val out = buf.copyOf()
                if (samples.containsKey(k)) {
                    System.arraycopy(out, 1, out, 0, out.size - 1)
                    out[out.size - 1] = samples.getValue(k)
                }
                out
            }
            return History(keys, capacity, next, (size + 1).coerceAtMost(capacity))
        }
    }

    private val _history = MutableStateFlow(History(HISTORY_KEYS))
    val history: StateFlow<History> = _history.asStateFlow()

    private var lastHistoryPushMs = 0L

    private val _ablatedGroups = MutableStateFlow<Set<String>>(emptySet())
    val ablatedGroups: StateFlow<Set<String>> = _ablatedGroups.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val groupIds = HashMap<String, Int>()

    // ----------------------------------------------------------------- startup

    suspend fun initialize() = withContext(simDispatcher) {
        installBundledSample()
        refreshEntries()
        val savedId = settings.activeBrainId.first()
        val entries = _status.value.entries
        val target = entries.firstOrNull { it.brain.manifest.id == savedId }
            ?: entries.firstOrNull { it.brain.mode == RuntimeMode.SAMPLE }
            ?: entries.firstOrNull()
        if (target != null) {
            activate(target.brain)
        } else {
            _status.value = _status.value.copy(initializing = false, loadError = "No brain packages installed")
        }
    }

    private fun installBundledSample() {
        val sampleDir = File(brainsDir, "sample-1024")
        if (File(sampleDir, "manifest.json").isFile) return
        try {
            context.assets.open("brains/sample_1024.pflybrain").use { input ->
                BrainPackage.install(input, sampleDir)
            }
        } catch (e: IOException) {
            _status.value = _status.value.copy(loadError = "Sample brain install failed: ${e.message}")
        }
    }

    private fun refreshEntries() {
        val entries = brainsDir.listFiles()
            ?.filter { File(it, "manifest.json").isFile }
            ?.mapNotNull { dir ->
                try {
                    val manifest = BrainPackage.parseManifest(File(dir, "manifest.json").readText())
                    val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    BrainEntry(InstalledBrain(manifest, dir.absolutePath), size, false)
                } catch (e: IOException) {
                    null
                }
            }
            .orEmpty()
            .sortedBy { it.brain.manifest.id }
        _status.value = _status.value.copy(entries = entries)
    }

    // ------------------------------------------------------------------ switch

    suspend fun activate(brain: InstalledBrain) = withContext(simDispatcher) {
        _status.value = _status.value.copy(initializing = true)
        try {
            runtime?.close()
            val rt = PocketFlyRuntime.create()
            rt.loadBrain(brain.dir, brain.manifest)
            runtime = rt
            groupIds.clear()
            brain.manifest.groups.forEach { groupIds[it.key] = it.id }
            _ablatedGroups.value = emptySet()
            applyDynamicsFromSettings()
            _history.value = History(HISTORY_KEYS)
            _status.value = _status.value.copy(
                entries = _status.value.entries,
                active = brain,
                loadError = null,
                initializing = false,
            )
            settings.setActiveBrainId(brain.manifest.id)
        } catch (e: IOException) {
            runtime = null
            _status.value = _status.value.copy(
                active = null,
                loadError = e.message,
                initializing = false,
            )
        }
    }

    /** Installs a `.pflybrain` from a content URI and activates it. */
    suspend fun importAndActivate(uri: android.net.Uri): Result<InstalledBrain> =
        withContext(simDispatcher) {
            try {
                val id = "imported-" + System.currentTimeMillis().toString(36)
                val target = File(brainsDir, id)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BrainPackage.install(input, target)
                } ?: throw IOException("Cannot read the selected file")
                val manifest = BrainPackage.parseManifest(File(target, "manifest.json").readText())
                val brain = InstalledBrain(manifest, target.absolutePath)
                refreshEntries()
                activate(brain)
                Result.success(brain)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteBrain(id: String) = withContext(simDispatcher) {
        val active = _status.value.active
        File(brainsDir, id).deleteRecursively()
        refreshEntries()
        if (active?.manifest?.id == id) {
            runtime?.close()
            runtime = null
            _status.value = _status.value.copy(active = null)
            val next = _status.value.entries.firstOrNull()
            if (next != null) activate(next.brain)
        }
    }

    // ---------------------------------------------------------------- dynamics

    suspend fun applyDynamicsFromSettings() = withContext(simDispatcher) {
        val dyn = settings.dynamics.first()
        val manifest = _status.value.active?.manifest ?: return@withContext
        val d = manifest.dynamics
        runtime?.applyDynamics(
            io.github.pocketfly.sim.DynamicsParams(
                decay = if (dyn.useBrainDefaults) d.decay else dyn.decay,
                threshold = if (dyn.useBrainDefaults) d.threshold else dyn.threshold,
                gain = if (dyn.useBrainDefaults) d.gain else dyn.gain,
                noise = if (dyn.useBrainDefaults) d.noise else dyn.noise,
                activationDecay = d.activationDecay,
                refractory = d.refractory,
            ),
        )
    }

    // --------------------------------------------------------------- free run

    private var freeRunJob: Job? = null

    /**
     * Free-running simulation for the Brain dashboard. Mutually exclusive
     * with game sessions by convention: screens stop the other mode.
     */
    fun setFreeRun(enabled: Boolean) {
        _running.value = enabled
        if (!enabled) {
            freeRunJob?.cancel()
            freeRunJob = null
        } else if (freeRunJob == null) {
            freeRunJob = scope.launch(simDispatcher) {
                var next = System.nanoTime()
                while (isActive && _running.value) {
                    runtime?.step(FREE_RUN_STEPS)
                    publishSnapshot()
                    next += FRAME_NS
                    val wait = (next - System.nanoTime()) / 1_000_000L
                    if (wait > 0) kotlinx.coroutines.delay(wait) else next = System.nanoTime()
                }
            }.also { job ->
                job.invokeOnCompletion { freeRunJob = null }
            }
        }
    }

    // ------------------------------------------------------------------ access

    /**
     * Runs `block` on the sim thread with the loaded runtime. Returns null if
     * no runtime is currently loaded. Used by game sessions and experiments.
     */
    suspend fun <T> onSim(block: (PocketFlyRuntime, BrainManifest) -> T): T? =
        withContext(simDispatcher) {
            val rt = runtime ?: return@withContext null
            val manifest = _status.value.active?.manifest ?: return@withContext null
            block(rt, manifest)
        }

    fun groupIdOf(key: String): Int? = groupIds[key]

    /** Publishes a fresh snapshot (call on the sim thread after stepping). */
    fun publishSnapshot() {
        val rt = runtime ?: return
        val s = rt.stats()
        val activities = HashMap<String, Float>()
        for ((key, id) in groupIds) {
            activities[key] = rt.groupActivity(id)
        }
        _snapshot.value = Snapshot(
            stepsPerSecond = s.stepsPerSecond,
            meanStepMs = s.meanStepMs,
            totalSteps = s.totalSteps,
            activeNeurons = s.activeNeurons,
            ablatedNeurons = s.ablatedNeurons,
            memoryBytes = s.approxMemoryBytes,
            groupActivity = activities,
        )
        val now = System.currentTimeMillis()
        if (now - lastHistoryPushMs >= HISTORY_INTERVAL_MS) {
            lastHistoryPushMs = now
            _history.value = _history.value.push(activities)
        }
    }

    // --------------------------------------------------------------- ablation

    suspend fun setGroupAblated(key: String, ablated: Boolean) = withContext(simDispatcher) {
        val id = groupIds[key] ?: return@withContext
        runtime?.setGroupEnabled(id, !ablated)
        _ablatedGroups.value = if (ablated) {
            _ablatedGroups.value + key
        } else {
            _ablatedGroups.value - key
        }
        publishSnapshot()
    }

    suspend fun clearAblations() = withContext(simDispatcher) {
        runtime?.clearAblations()
        _ablatedGroups.value = emptySet()
        publishSnapshot()
    }

    suspend fun neuronInfo(id: Int) = withContext(simDispatcher) {
        runtime?.neuronInfo(id)
    }

    suspend fun setNeuronEnabled(id: Int, enabled: Boolean) = withContext(simDispatcher) {
        runtime?.setNeuronEnabled(id, enabled)
        publishSnapshot()
    }

    suspend fun benchmark(steps: Int = 1000): BenchmarkResult? = withContext(simDispatcher) {
        val rt = runtime ?: return@withContext null
        rt.step(steps)
        publishSnapshot()
        val s = rt.stats()
        BenchmarkResult(
            steps = steps,
            stepsPerSecond = s.stepsPerSecond,
            meanStepMs = s.meanStepMs,
            activeNeurons = s.activeNeurons,
            memoryBytes = s.approxMemoryBytes,
            neuronCount = s.neuronCount,
            edgeCount = s.edgeCount,
        )
    }

    data class BenchmarkResult(
        val steps: Int,
        val stepsPerSecond: Double,
        val meanStepMs: Double,
        val activeNeurons: Int,
        val memoryBytes: Double,
        val neuronCount: Long,
        val edgeCount: Long,
    )

    /**
     * Bridge between the pure-Kotlin game engine and the native runtime.
     * Must only be called from the sim thread.
     */
    inner class EngineBridge : io.github.pocketfly.core.engine.NeuralBridge {
        private val buffer = FloatArray(4096)

        override fun driveGroup(groupKey: String, value: Float) {
            val rt = runtime ?: return
            val id = groupIds[groupKey] ?: return
            val size = rt.groupSize(id).coerceAtMost(buffer.size)
            if (size > 0) {
                java.util.Arrays.fill(buffer, 0, size, value)
                rt.setInputGroup(id, buffer.copyOf(size))
            }
        }

        override fun clearDrives() {
            runtime?.clearInputs()
        }

        override fun step(steps: Int) {
            runtime?.step(steps)
            publishSnapshot()
        }

        override fun groupActivity(groupKey: String): Float {
            val rt = runtime ?: return 0f
            val id = groupIds[groupKey] ?: return 0f
            return rt.groupActivity(id)
        }
    }

    val engineBridge = EngineBridge()

    companion object {
        val HISTORY_KEYS = listOf("VIS_L", "VIS_R", "DN_L", "DN_R", "DN_F")
        private const val HISTORY_INTERVAL_MS = 60L
        private const val FRAME_NS = 1_000_000_000L / 36L
        private const val FREE_RUN_STEPS = 6
    }
}
