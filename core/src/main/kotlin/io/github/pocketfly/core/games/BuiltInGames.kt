package io.github.pocketfly.core.games

import io.github.pocketfly.core.game.ActionMapping
import io.github.pocketfly.core.game.DynamicsOverrides
import io.github.pocketfly.core.game.GameSpec
import io.github.pocketfly.core.game.ObjectSpec
import io.github.pocketfly.core.game.Rule
import io.github.pocketfly.core.game.RewardSpec
import io.github.pocketfly.core.game.SensoryBinding
import io.github.pocketfly.core.game.SimulationSpec
import io.github.pocketfly.core.game.WorldObjectType
import io.github.pocketfly.core.game.WorldSpec

/**
 * Built-in games, written against the same GameSpec schema as user-created
 * games. Group keys (VIS_L/R, OL_L/R, DN_L/R/F) refer to the sample brain's
 * manifest; a brain package must expose these keys for the built-ins to run.
 */
object BuiltInGames {

    /** Flagship demo: the fly steers toward a wandering light. */
    fun lightChase(): GameSpec = GameSpec(
        id = "builtin-light-chase",
        name = "Light Chase",
        description = "A light drifts around the arena. Left/right visual " +
            "intensity drives the left/right visual groups; the difference " +
            "between the descending groups steers the fly toward the light.",
        author = "PocketFly",
        world = WorldSpec(
            width = 100f,
            height = 60f,
            objects = listOf(
                ObjectSpec(
                    id = "spawn",
                    type = WorldObjectType.SPAWN,
                    x = 12f, y = 28f, w = 2f, h = 2f,
                ),
                ObjectSpec(
                    id = "light",
                    type = WorldObjectType.LIGHT,
                    x = 70f, y = 18f, w = 2.4f, h = 2.4f,
                    params = mapOf("vx" to -7f, "vy" to 4f, "range" to 48f),
                ),
            ),
        ),
        simulation = SimulationSpec(
            timestepsPerFrame = 6,
            maxSpeed = 26f,
            turnRate = 3.4f,
            dynamics = DynamicsOverrides(),
        ),
        sensory = listOf(
            SensoryBinding(channel = "visual.left", groupKey = "VIS_L"),
            SensoryBinding(channel = "visual.right", groupKey = "VIS_R"),
        ),
        actions = listOf(
            ActionMapping(
                decoder = "difference",
                action = "steer",
                leftGroup = "DN_L",
                rightGroup = "DN_R",
                gain = 3.0f,
                deadzone = 0.04f,
            ),
            ActionMapping(
                decoder = "continuous",
                action = "forward",
                group = "DN_F",
                gain = 1.4f,
                offset = 0.18f,
            ),
        ),
    )

    /** Single-output closed loop: gravity, pipes, one flap decision. */
    fun flap(): GameSpec = GameSpec(
        id = "builtin-flap",
        name = "Flap",
        description = "A minimal single-output loop: the fly's forward " +
            "channel (DN_F) reflects rising visual input as it sinks; when " +
            "the descending forward group goes quiet, the fly flaps.",
        author = "PocketFly",
        world = WorldSpec(
            width = 100f,
            height = 60f,
            objects = listOf(
                ObjectSpec(id = "spawn", type = WorldObjectType.SPAWN, x = 22f, y = 30f, w = 2f, h = 2f),
                // Pipe pairs scroll left and wrap around. Top pipes are
                // anchored to the ceiling; bottom pipes to the floor.
                pipe("p1-top", 55f, top = true),
                pipe("p1-bottom", 55f, top = false),
                pipe("p2-top", 80f, top = true),
                pipe("p2-bottom", 80f, top = false),
                pipe("p3-top", 105f, top = true),
                pipe("p3-bottom", 105f, top = false),
            ),
        ),
        simulation = SimulationSpec(
            timestepsPerFrame = 6,
            gravity = 60f,
            flapImpulse = 24f,
            mode = "sidescroller",
        ),
        sensory = listOf(
            // Altitude feeds both visual groups symmetrically: the higher the
            // fly, the stronger the drive; when it sinks, forward activity
            // decays and the rule below flaps.
            SensoryBinding(channel = "altitude", groupKey = "VIS_L", gain = 0.9f),
            SensoryBinding(channel = "altitude", groupKey = "VIS_R", gain = 0.9f),
        ),
        rules = listOf(
            Rule(
                id = "flap-when-quiet",
                source = "DN_F",
                operator = "<",
                value = 0.9f,
                action = "flap",
                strength = 1f,
            ),
        ),
        rewards = RewardSpec(goal = 1f),
    )

    private fun pipe(id: String, x: Float, top: Boolean): ObjectSpec {
        val gap = 16f
        return if (top) {
            ObjectSpec(
                id = id, type = WorldObjectType.OBSTACLE,
                x = x, y = 0f, w = 4.5f, h = 30f - gap / 2,
                params = mapOf("vx" to -16f, "wrap" to 1f),
            )
        } else {
            ObjectSpec(
                id = id, type = WorldObjectType.OBSTACLE,
                x = x, y = 30f + gap / 2, w = 4.5f, h = 30f - gap / 2,
                params = mapOf("vx" to -16f, "wrap" to 1f),
            )
        }
    }

    /** Walls and obstacles loom; looming on one side steers away from it. */
    fun obstacleAvoidance(): GameSpec = GameSpec(
        id = "builtin-obstacle-avoidance",
        name = "Obstacle Avoidance",
        description = "The fly pushes forward through a cluttered corridor. " +
            "Looming obstacles are crossed onto the opposite visual channel, " +
            "so structure plus the difference decoder deflect the fly away " +
            "from walls - imperfectly, which is the point.",
        author = "PocketFly",
        world = WorldSpec(
            width = 120f,
            height = 60f,
            objects = listOf(
                ObjectSpec(id = "spawn", type = WorldObjectType.SPAWN, x = 8f, y = 30f, w = 2f, h = 2f),
                ObjectSpec(id = "goal", type = WorldObjectType.GOAL, x = 112f, y = 29f, w = 3f, h = 3f),
                wall("wall-top", 0f, 0f, 120f, 2f),
                wall("wall-bottom", 0f, 58f, 120f, 2f),
                wall("obs-1", 30f, 12f, 3f, 26f),
                wall("obs-2", 30f, 46f, 3f, 12f),
                wall("obs-3", 55f, 0f, 3f, 30f),
                wall("obs-4", 55f, 42f, 3f, 16f),
                wall("obs-5", 80f, 18f, 3f, 24f),
                ObjectSpec(id = "food-1", type = WorldObjectType.FOOD, x = 42f, y = 50f, w = 1.6f, h = 1.6f, params = mapOf("range" to 50f)),
                ObjectSpec(id = "food-2", type = WorldObjectType.FOOD, x = 68f, y = 8f, w = 1.6f, h = 1.6f, params = mapOf("range" to 50f)),
                ObjectSpec(id = "danger-1", type = WorldObjectType.DANGER, x = 95f, y = 40f, w = 3f, h = 3f, params = mapOf("range" to 20f)),
            ),
        ),
        simulation = SimulationSpec(
            timestepsPerFrame = 6,
            maxSpeed = 22f,
            turnRate = 3.8f,
        ),
        sensory = listOf(
            // Crossed: a looming wall on the right excites the LEFT visual
            // group, so the difference decoder steers away from it.
            SensoryBinding(channel = "obstacle.left", groupKey = "VIS_R"),
            SensoryBinding(channel = "obstacle.right", groupKey = "VIS_L"),
            SensoryBinding(channel = "smell.left", groupKey = "OL_L"),
            SensoryBinding(channel = "smell.right", groupKey = "OL_R"),
        ),
        actions = listOf(
            ActionMapping(
                decoder = "difference",
                action = "steer",
                leftGroup = "DN_L",
                rightGroup = "DN_R",
                gain = 2.6f,
                deadzone = 0.03f,
            ),
            ActionMapping(
                decoder = "continuous",
                action = "forward",
                group = "DN_F",
                gain = 1.0f,
                offset = 0.45f,
            ),
        ),
        rewards = RewardSpec(goal = 10f, food = 1f, collision = -1f, danger = -5f),
    )

    private fun wall(id: String, x: Float, y: Float, w: Float, h: Float) =
        ObjectSpec(id = id, type = WorldObjectType.WALL, x = x, y = y, w = w, h = h)

    val all: List<GameSpec> = listOf(lightChase(), flap(), obstacleAvoidance())

    fun byId(id: String): GameSpec? = all.firstOrNull { it.id == id }
}
