package io.github.pocketfly.app.data

import android.content.Context
import android.net.Uri
import io.github.pocketfly.core.engine.NeuralBridge
import io.github.pocketfly.sim.RuntimeMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Runs scripted closed-loop experiments against the loaded runtime and
 * stores results as JSON + CSV for export.
 *
 * All experiments run on the sim thread; game sessions must be stopped
 * (the UI enforces this by construction: leaving the game screen stops the
 * session before Experiments can run).
 */
class ExperimentsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: RuntimeRepository,
) {
    @Serializable
    data class Row(
        val stimulus: String,
        val intensity: Float,
        val dnLeft: Float,
        val dnRight: Float,
        val dnForward: Float,
        val activeNeurons: Int,
    )

    @Serializable
    data class ExperimentResult(
        val id: String,
        val type: String,
        val title: String,
        val timestamp: Long,
        val runtimeMode: String,
        val brainId: String,
        val brainName: String,
        val stepsPerTrial: Int,
        val ablatedGroups: List<String> = emptyList(),
        val rows: List<Row>,
        val summary: Map<String, Float> = emptyMap(),
        val notes: String = "",
    )

    enum class Type(val title: String, val description: String) {
        VISUAL_BIAS(
            "Visual Bias Test",
            "Stimulate the left and right visual groups separately and " +
                "compare descending activity. A lateralized connectome " +
                "should bias the corresponding descending side.",
        ),
        BILATERAL_SYMMETRY(
            "Bilateral Symmetry Test",
            "Stimulate both sides equally and measure how symmetric the " +
                "descending output is.",
        ),
        ABLATION_COMPARE(
            "Ablation Test",
            "Run the visual bias protocol with an intact brain and again " +
                "with the left central pool ablated, then compare the bias.",
        ),
        STIMULUS_SWEEP(
            "Stimulus Sweep",
            "Sweep unilateral stimulus intensity and record the descending " +
                "response curve.",
        ),
    }

    @Serializable
    data class SavedEntry(
        val id: String,
        val title: String,
        val timestamp: Long,
        val fileName: String,
    )

    val running = MutableStateFlow<Type?>(null)
    val lastResult = MutableStateFlow<ExperimentResult?>(null)
    val progress = MutableStateFlow(0f)

    private val resultsDir: File
        get() = File(context.filesDir, "experiments").apply { mkdirs() }

    private val _saved = MutableStateFlow<List<SavedEntry>>(emptyList())
    val saved: StateFlow<List<SavedEntry>> = _saved.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { reload() }
    }

    fun reload() {
        _saved.value = resultsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching {
                    val r = Json.decodeFromString<ExperimentResult>(f.readText())
                    SavedEntry(r.id, r.title, r.timestamp, f.name)
                }.getOrNull()
            }
            .orEmpty()
            .sortedByDescending { it.timestamp }
    }

    fun load(fileName: String): ExperimentResult? = runCatching {
        Json.decodeFromString<ExperimentResult>(File(resultsDir, fileName).readText())
    }.getOrNull()

    fun run(type: Type) {
        if (running.value != null) return
        running.value = type
        scope.launch {
            try {
                val result: ExperimentResult? = execute(type)
                if (result != null) {
                    lastResult.value = result
                    persist(result)
                }
            } finally {
                running.value = null
                progress.value = 0f
            }
        }
    }

    private suspend fun execute(type: Type): ExperimentResult? = withContext(repo.simDispatcher) {
        repo.onSim { rt, manifest ->
            val steps = 150
            val dynamics = manifest.dynamics
            rt.applyDynamics(
                io.github.pocketfly.sim.DynamicsParams(
                    dynamics.decay, dynamics.threshold, dynamics.gain,
                    0f, dynamics.activationDecay, dynamics.refractory,
                ),
            )
            val rows = mutableListOf<Row>()
            fun drive(groupKey: String, value: Float) {
                val id = repo.groupIdOf(groupKey) ?: return
                val size = rt.groupSize(id)
                if (size > 0) {
                    rt.setInputGroup(id, FloatArray(size) { value })
                }
            }
            fun trial(label: String, intensity: Float, leftDrive: Float, rightDrive: Float): Row {
                rt.clearInputs()
                rt.reset()
                drive("VIS_L", leftDrive)
                drive("VIS_R", rightDrive)
                rt.step(steps)
                val idL = repo.groupIdOf("DN_L") ?: -1
                val idR = repo.groupIdOf("DN_R") ?: -1
                val idF = repo.groupIdOf("DN_F") ?: -1
                rows.add(
                    Row(
                        stimulus = label,
                        intensity = intensity,
                        dnLeft = if (idL >= 0) rt.groupActivity(idL) else 0f,
                        dnRight = if (idR >= 0) rt.groupActivity(idR) else 0f,
                        dnForward = if (idF >= 0) rt.groupActivity(idF) else 0f,
                        activeNeurons = rt.activeCount(),
                    ),
                )
                progress.value = rows.size / 12f
                return rows.last()
            }

            when (type) {
                Type.VISUAL_BIAS -> {
                    val none = trial("none", 0f, 0f, 0f)
                    val left = trial("left", 1f, 1f, 0f)
                    val right = trial("right", 1f, 0f, 1f)
                    val bias = ((left.dnLeft - left.dnRight) + (right.dnRight - right.dnLeft)) / 2f
                    ExperimentResult(
                        id = newId(), type = type.name, title = type.title,
                        timestamp = System.currentTimeMillis(),
                        runtimeMode = manifest.mode, brainId = manifest.id,
                        brainName = manifest.name, stepsPerTrial = steps,
                        rows = rows,
                        summary = mapOf(
                            "lateralBias" to bias,
                            "baseline" to (none.dnLeft + none.dnRight) / 2f,
                        ),
                        notes = "Positive lateralBias = ipsilateral (same-side) output bias.",
                    )
                }
                Type.BILATERAL_SYMMETRY -> {
                    val none = trial("none", 0f, 0f, 0f)
                    val both = trial("bilateral", 0.8f, 0.8f, 0.8f)
                    val asymmetry = kotlin.math.abs(both.dnLeft - both.dnRight)
                    ExperimentResult(
                        id = newId(), type = type.name, title = type.title,
                        timestamp = System.currentTimeMillis(),
                        runtimeMode = manifest.mode, brainId = manifest.id,
                        brainName = manifest.name, stepsPerTrial = steps,
                        rows = rows,
                        summary = mapOf(
                            "asymmetry" to asymmetry,
                            "baseline" to (none.dnLeft + none.dnRight) / 2f,
                        ),
                        notes = "Asymmetry close to 0 means symmetric bilateral output.",
                    )
                }
                Type.ABLATION_COMPARE -> {
                    val control = trial("control-left", 1f, 1f, 0f)
                    val controlBias = control.dnLeft - control.dnRight
                    val ablatedKey = "CX_L"
                    val ablateId = repo.groupIdOf(ablatedKey)
                    if (ablateId != null) rt.setGroupEnabled(ablateId, false)
                    val ablated = trial("ablated-left", 1f, 1f, 0f)
                    val ablatedBias = ablated.dnLeft - ablated.dnRight
                    if (ablateId != null) rt.setGroupEnabled(ablateId, true)
                    rt.clearAblations()
                    ExperimentResult(
                        id = newId(), type = type.name, title = type.title,
                        timestamp = System.currentTimeMillis(),
                        runtimeMode = manifest.mode, brainId = manifest.id,
                        brainName = manifest.name, stepsPerTrial = steps,
                        ablatedGroups = listOf(ablatedKey),
                        rows = rows,
                        summary = mapOf(
                            "controlBias" to controlBias,
                            "ablatedBias" to ablatedBias,
                            "biasLost" to (controlBias - ablatedBias),
                        ),
                        notes = "Bias lost after ablating $ablatedKey indicates how much " +
                            "of the ipsilateral response flows through that pool.",
                    )
                }
                Type.STIMULUS_SWEEP -> {
                    var i = 0
                    while (i <= 10) {
                        val intensity = i / 10f
                        trial("left", intensity, intensity, 0f)
                        i++
                    }
                    val slope = if (rows.size > 2) {
                        val first = rows[1]
                        val last = rows.last()
                        if (last.intensity - first.intensity > 0f) {
                            ((last.dnLeft - first.dnLeft) / (last.intensity - first.intensity))
                        } else 0f
                    } else 0f
                    ExperimentResult(
                        id = newId(), type = type.name, title = type.title,
                        timestamp = System.currentTimeMillis(),
                        runtimeMode = manifest.mode, brainId = manifest.id,
                        brainName = manifest.name, stepsPerTrial = steps,
                        rows = rows,
                        summary = mapOf("responseSlope" to slope),
                        notes = "Response curve of DN_L to increasing left stimulus.",
                    )
                }
            }
        }
    }

    private fun newId(): String = System.currentTimeMillis().toString(36) +
        "-" + (0..999).random()

    private fun persist(result: ExperimentResult) {
        scope.launch(Dispatchers.IO) {
            val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(Date(result.timestamp)) + "-" + result.type.lowercase()
            File(resultsDir, "$base.json").writeText(
                jsonPretty.encodeToString(ExperimentResult.serializer(), result),
            )
            File(resultsDir, "$base.csv").writeText(toCsv(result))
            reload()
        }
    }

    fun toCsv(result: ExperimentResult): String {
        val sb = StringBuilder()
        sb.append("timestamp,type,runtime_mode,brain_id,stimulus,intensity,dn_left,dn_right,dn_forward,active_neurons\n")
        for (row in result.rows) {
            sb.append(result.timestamp).append(',')
                .append(result.type).append(',')
                .append(result.runtimeMode).append(',')
                .append(result.brainId).append(',')
                .append(row.stimulus).append(',')
                .append(row.intensity).append(',')
                .append(row.dnLeft).append(',')
                .append(row.dnRight).append(',')
                .append(row.dnForward).append(',')
                .append(row.activeNeurons).append('\n')
        }
        return sb.toString()
    }

    /** Writes a saved experiment file to a user-chosen location. */
    suspend fun export(uri: Uri, fileName: String): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val src = File(resultsDir, fileName)
            if (!src.isFile) throw java.io.IOException("Result not found: $fileName")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(src.readBytes())
            } ?: throw java.io.IOException("Cannot write to the selected location")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun csvFileNameOf(fileName: String): String = fileName.removeSuffix(".json") + ".csv"

    private val jsonPretty = Json { prettyPrint = true }
}
