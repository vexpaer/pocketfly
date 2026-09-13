package io.github.pocketfly.sim

import java.io.Closeable
import java.io.IOException

/** Tunable neural dynamics parameters (computational, not biologically fitted). */
data class DynamicsParams(
    val decay: Float = 0.82f,
    val threshold: Float = 1.0f,
    val gain: Float = 1.0f,
    val noise: Float = 0.0f,
    val activationDecay: Float = 0.85f,
    val refractory: Boolean = true,
)

data class SimulationStats(
    val neuronCount: Long,
    val edgeCount: Long,
    val activeNeurons: Int,
    val ablatedNeurons: Int,
    val stepsPerSecond: Double,
    val lastStepMs: Double,
    val meanStepMs: Double,
    val totalSteps: Long,
    val approxMemoryBytes: Double,
)

data class NeuronInfo(
    val id: Int,
    val groupId: Int,
    val typeId: Int,
    val regionId: Int,
    val sideId: Int,  // 0 left, 1 right, 2 mid
    val flags: Int,   // bit0 input, bit1 output
    val inDegree: Int,
    val outDegree: Int,
    val enabled: Boolean,
    val activity: Float,
    val potential: Float,
) {
    val isInput: Boolean get() = flags and 0x01 != 0
    val isOutput: Boolean get() = flags and 0x02 != 0
}

/**
 * Facade over the native C++ neural runtime.
 *
 * All calls must happen on a single thread (the simulation thread). The
 * runtime owns no locks by design; the Kotlin layer confines it.
 */
class PocketFlyRuntime private constructor() : AutoCloseable {

    private val handle: Long = nativeCreate()
    private var loaded = false

    val isLoaded: Boolean get() = loaded

    /** Loads an installed brain package directory. Throws [IOException] on failure. */
    fun loadBrain(dir: String, manifest: BrainManifest) {
        val ok = nativeLoad(
            handle, dir,
            manifest.neuronCount,
            manifest.edgeCount,
            manifest.groups.size,
            manifest.weightScale,
        )
        if (!ok) {
            val reason = nativeLastError(handle).ifEmpty { "unknown error" }
            throw IOException("Failed to load brain: $reason")
        }
        applyDynamics(manifest.dynamics.toParams())
        loaded = true
    }

    fun DynamicsSpec.toParams() = DynamicsParams(
        decay = decay,
        threshold = threshold,
        gain = gain,
        noise = noise,
        activationDecay = activationDecay,
        refractory = refractory,
    )

    fun applyDynamics(params: DynamicsParams) {
        nativeSetParams(
            handle, params.decay, params.threshold, params.gain,
            params.noise, params.activationDecay, params.refractory,
        )
    }

    fun reset() = nativeReset(handle)

    fun step(steps: Int = 1) = nativeStep(handle, steps)

    /** Persistent sensory drive on a single neuron (applied every step). */
    fun setInput(neuronId: Int, value: Float) = nativeSetInput(handle, neuronId, value)

    /** Values are applied to the group's member neurons in index order. */
    fun setInputGroup(groupId: Int, values: FloatArray) =
        nativeSetInputGroup(handle, groupId, values)

    fun clearInputs() = nativeClearInputs(handle)

    fun activity(neuronId: Int): Float = nativeGetActivity(handle, neuronId)

    fun potential(neuronId: Int): Float = nativeGetPotential(handle, neuronId)

    fun groupActivity(groupId: Int): Float = nativeGetGroupActivity(handle, groupId)

    fun groupSize(groupId: Int): Int = nativeGetGroupSize(handle, groupId)

    fun activeCount(): Int = nativeGetActiveCount(handle)

    fun stats(): SimulationStats {
        val s = nativeStats(handle)
        return SimulationStats(
            neuronCount = s[0].toLong(),
            edgeCount = s[1].toLong(),
            activeNeurons = s[2].toInt(),
            ablatedNeurons = s[3].toInt(),
            stepsPerSecond = s[4],
            lastStepMs = s[5],
            meanStepMs = s[6],
            totalSteps = s[7].toLong(),
            approxMemoryBytes = s[8],
        )
    }

    fun setNeuronEnabled(neuronId: Int, enabled: Boolean): Boolean =
        nativeSetNeuronEnabled(handle, neuronId, enabled)

    fun setGroupEnabled(groupId: Int, enabled: Boolean) =
        nativeSetGroupEnabled(handle, groupId, enabled)

    fun clearAblations() = nativeClearAblations(handle)

    fun neuronInfo(neuronId: Int): NeuronInfo {
        val i = nativeGetNeuronInfoInts(handle, neuronId)
        val f = nativeGetNeuronInfoFloats(handle, neuronId)
        return NeuronInfo(
            id = i[0], groupId = i[1], typeId = i[2], regionId = i[3],
            sideId = i[4], flags = i[5], inDegree = i[6], outDegree = i[7],
            enabled = i[8] == 1, activity = f[0], potential = f[1],
        )
    }

    override fun close() {
        nativeDestroy(handle)
    }

    private external fun nativeLastError(handle: Long): String

    companion object {
        init {
            System.loadLibrary("pocketfly")
        }

        fun create(): PocketFlyRuntime = PocketFlyRuntime()
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoad(
        handle: Long,
        dirPath: String,
        expectedNeurons: Int,
        expectedEdges: Long,
        expectedGroups: Int,
        weightScale: Float,
    ): Boolean
    private external fun nativeReset(handle: Long)
    private external fun nativeStep(handle: Long, steps: Int)
    private external fun nativeSetInput(handle: Long, neuronId: Int, value: Float)
    private external fun nativeSetInputGroup(handle: Long, groupId: Int, values: FloatArray)
    private external fun nativeClearInputs(handle: Long)
    private external fun nativeGetActivity(handle: Long, neuronId: Int): Float
    private external fun nativeGetPotential(handle: Long, neuronId: Int): Float
    private external fun nativeGetGroupActivity(handle: Long, groupId: Int): Float
    private external fun nativeGetGroupSize(handle: Long, groupId: Int): Int
    private external fun nativeGetActiveCount(handle: Long): Int
    private external fun nativeStats(handle: Long): DoubleArray
    private external fun nativeSetNeuronEnabled(handle: Long, neuronId: Int, enabled: Boolean): Boolean
    private external fun nativeSetGroupEnabled(handle: Long, groupId: Int, enabled: Boolean)
    private external fun nativeClearAblations(handle: Long)
    private external fun nativeSetParams(
        handle: Long, decay: Float, threshold: Float, gain: Float,
        noise: Float, activationDecay: Float, refractory: Boolean,
    )
    private external fun nativeGetNeuronInfoInts(handle: Long, neuronId: Int): IntArray
    private external fun nativeGetNeuronInfoFloats(handle: Long, neuronId: Int): FloatArray
}
