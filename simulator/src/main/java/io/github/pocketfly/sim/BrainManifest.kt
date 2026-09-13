package io.github.pocketfly.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Dynamics defaults carried by a brain manifest. */
@Serializable
data class DynamicsSpec(
    val decay: Float = 0.82f,
    val threshold: Float = 1.0f,
    val gain: Float = 1.0f,
    val noise: Float = 0.0f,
    @SerialName("activationDecay") val activationDecay: Float = 0.85f,
    val refractory: Boolean = true,
)

@Serializable
data class BrainGroup(
    val id: Int,
    val key: String,
    val name: String,
    val kind: String,  // sensory | central | descending | other
    val side: String = "mid",  // left | right | mid
)

@Serializable
data class BrainChecksum(
    val algorithm: String = "sha256",
    val value: String = "",
)

/**
 * Manifest of a `.pflybrain` package. Describes the binary payload that the
 * native runtime loads; neuron names/labels are intentionally not required.
 */
@Serializable
data class BrainManifest(
    val formatVersion: Int,
    val id: String,
    val name: String,
    val mode: String = "sample",  // sample | lite | full
    val description: String = "",
    val attribution: String = "",
    val neuronCount: Int,
    val edgeCount: Long,
    val offsetType: String = "u32",
    val weightType: String = "f32",
    val weightScale: Float = 1f,
    val groups: List<BrainGroup> = emptyList(),
    val types: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val dynamics: DynamicsSpec = DynamicsSpec(),
    val checksum: BrainChecksum? = null,
) {
    fun groupByKey(key: String): BrainGroup? = groups.firstOrNull { it.key == key }
    fun groupName(id: Int): String = groups.firstOrNull { it.id == id }?.name ?: "Group $id"
}

/** A brain package that has been installed (extracted) on device. */
data class InstalledBrain(
    val manifest: BrainManifest,
    val dir: String,
) {
    val mode: RuntimeMode
        get() = RuntimeMode.fromKey(manifest.mode)
}

enum class RuntimeMode(val key: String, val label: String) {
    SAMPLE("sample", "Sample"),
    LITE("lite", "Lite"),
    FULL("full", "Full");

    companion object {
        fun fromKey(key: String): RuntimeMode =
            entries.firstOrNull { it.key == key } ?: SAMPLE
    }
}
