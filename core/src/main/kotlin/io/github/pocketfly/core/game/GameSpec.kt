package io.github.pocketfly.core.game

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PocketFly custom game format, schemaVersion 1 (`.pocketfly.json`).
 *
 * A game spec describes a small world, how world state is encoded into
 * neural input groups, how neural output groups are decoded into body
 * actions, and how rewards are scored. Pure data - the engine lives in
 * io.github.pocketfly.core.engine.
 */
@Serializable
data class GameSpec(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val world: WorldSpec,
    val simulation: SimulationSpec = SimulationSpec(),
    val sensory: List<SensoryBinding> = emptyList(),
    val actions: List<ActionMapping> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val rewards: RewardSpec = RewardSpec(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }

    fun spawn(): ObjectSpec? = world.objects.firstOrNull { it.type == WorldObjectType.SPAWN }

    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (id.isBlank()) problems.add("id must not be blank")
        if (name.isBlank()) problems.add("name must not be blank")
        if (world.width <= 0f || world.height <= 0f) {
            problems.add("world size must be positive")
        }
        val ids = mutableSetOf<String>()
        for (o in world.objects) {
            if (!ids.add(o.id)) problems.add("duplicate object id: ${o.id}")
            // Movers (scrolling pipes, wandering lights) may start off-world;
            // require only that the object can enter the world by 2x its size.
            if (o.x > world.width + o.w * 2 || o.y > world.height + o.h * 2 ||
                o.x + o.w < -o.w * 2 || o.y + o.h < -o.h * 2
            ) {
                problems.add("object ${o.id} lies outside the world bounds")
            }
        }
        if (world.objects.none { it.type == WorldObjectType.SPAWN }) {
            problems.add("world needs a spawn point")
        }
        if (simulation.timestepsPerFrame !in 1..64) {
            problems.add("simulation.timestepsPerFrame must be in 1..64")
        }
        for (b in sensory) {
            if (b.groupKey.isBlank()) problems.add("sensory binding needs a groupKey")
        }
        return problems
    }
}

@Serializable
data class WorldSpec(
    val width: Float,
    val height: Float,
    val objects: List<ObjectSpec> = emptyList(),
)

enum class WorldObjectType {
    WALL,
    FOOD,
    GOAL,
    LIGHT,
    DANGER,
    OBSTACLE,
    SPAWN,
}

@Serializable
data class ObjectSpec(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val y: Float,
    val w: Float = 2f,
    val h: Float = 2f,
    val rotation: Float = 0f,
    /** Free-form per-type parameters, e.g. vx/vy for movers, range for lights. */
    val params: Map<String, Float> = emptyMap(),
)

@Serializable
data class SimulationSpec(
    /** Neural timesteps per rendered frame (36 fps baseline). */
    val timestepsPerFrame: Int = 6,
    val speed: Float = 1.0f,
    val maxSpeed: Float = 24f,
    val turnRate: Float = 3.0f,
    val gravity: Float = 60f,
    val flapImpulse: Float = 22f,
    /** "topdown" (steering + forward) or "sidescroller" (gravity + flap). */
    val mode: String = "topdown",
    val dynamics: DynamicsOverrides = DynamicsOverrides(),
)

@Serializable
data class DynamicsOverrides(
    val decay: Float? = null,
    val threshold: Float? = null,
    val gain: Float? = null,
    val noise: Float? = null,
)

/**
 * Binds a sensory channel produced by an encoder (e.g. "visual.left") to a
 * neural input group (e.g. "VIS_L"). The group key refers to the brain
 * manifest, so specs are portable across brains that use the same keys.
 */
@Serializable
data class SensoryBinding(
    val channel: String,
    val groupKey: String,
    val gain: Float = 1f,
    val offset: Float = 0f,
)

/**
 * Decodes neural output group activity into a body action.
 *
 * decoder = "difference": steer = gain * (right - left), deadzone applied
 * decoder = "continuous": forward = clamp01(gain * activity + offset)
 * decoder = "threshold":  action fires when activity >= threshold
 */
@Serializable
data class ActionMapping(
    val decoder: String,
    val action: String,  // steer | forward | flap
    val leftGroup: String? = null,
    val rightGroup: String? = null,
    val group: String? = null,
    val gain: Float = 1f,
    val threshold: Float = 0.35f,
    val deadzone: Float = 0.0f,
    val offset: Float = 0f,
)

/** Simple stimulus-conditioned action rule evaluated on group activity. */
@Serializable
data class Rule(
    val id: String = "",
    /** Group key, e.g. "DN_R". */
    val source: String,
    /** Second group key for diff / ratio / avg operators. */
    val source2: String? = null,
    /** > | < | diff | ratio | avg */
    val operator: String,
    val value: Float,
    /** turn_left | turn_right | forward | flap | stop */
    val action: String,
    val strength: Float = 1f,
)

@Serializable
data class RewardSpec(
    val food: Float = 1f,
    val goal: Float = 10f,
    val collision: Float = -1f,
    val danger: Float = -5f,
    val timePenalty: Float = -0.01f,
)
