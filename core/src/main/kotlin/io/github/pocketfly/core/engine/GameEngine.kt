package io.github.pocketfly.core.engine

import io.github.pocketfly.core.game.ActionMapping
import io.github.pocketfly.core.game.GameSpec
import io.github.pocketfly.core.game.ObjectSpec
import io.github.pocketfly.core.game.WorldObjectType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Abstraction over the neural runtime used by the game engine. The app binds
 * this to the real native runtime (resolving group keys to group ids); tests
 * substitute a deterministic fake.
 */
interface NeuralBridge {
    /** Apply uniform sensory drive to every neuron of the group. */
    fun driveGroup(groupKey: String, value: Float)

    /** Zero all sensory drives (called every frame before re-driving). */
    fun clearDrives()

    /** Advance the simulation by `steps` timesteps. */
    fun step(steps: Int)

    /** Mean activity (0..1 EMA of spiking) of a group. */
    fun groupActivity(groupKey: String): Float
}

/** Output of the action decoding stage for one frame. */
data class BodyCommand(
    val steer: Float = 0f,    // -1 (left) .. 1 (right), applied as heading change
    val forward: Float = 0f,  // 0..1 of max speed
    val flap: Boolean = false,
)

/**
 * A live instance of a world object. Static objects keep their spec position;
 * movers (lights, scrolling obstacles) update each tick.
 */
class WorldObject(val spec: ObjectSpec) {
    var x: Float = spec.x
    var y: Float = spec.y
    var passed: Boolean = false  // for pipe-style scoring

    val id: String get() = spec.id
    val type: WorldObjectType get() = spec.type
    val w: Float get() = spec.w
    val h: Float get() = spec.h
    val params: Map<String, Float> get() = spec.params

    fun overlaps(cx: Float, cy: Float, radius: Float): Boolean {
        val nx = (cx - (x + w / 2)).coerceIn(-w / 2, w / 2)
        val ny = (cy - (y + h / 2)).coerceIn(-h / 2, h / 2)
        val dx = cx - (x + w / 2) - nx
        val dy = cy - (y + h / 2) - ny
        return dx * dx + dy * dy < radius * radius
    }
}

/** One frame's worth of simulation results, for HUD and rendering. */
data class FrameResult(
    val channels: Map<String, Float>,
    val command: BodyCommand,
    val events: List<WorldEvent>,
    val scoreDelta: Float,
    val score: Float,
    val episodeOver: Boolean,
)

sealed interface WorldEvent {
    data class Collision(val withType: WorldObjectType) : WorldEvent
    data class Pickup(val objectId: String) : WorldEvent
    data class GoalReached(val objectId: String) : WorldEvent
    data class Danger(val objectId: String) : WorldEvent
    data object Death : WorldEvent
    data class LightCaught(val objectId: String) : WorldEvent
}

/**
 * The game engine: a deterministic closed loop
 * environment -> sensory -> neural -> action -> physics -> reward.
 *
 * Pure Kotlin; rendering and the real neural runtime live in the app module.
 */
class GameEngine(
    val spec: GameSpec,
    private val neural: NeuralBridge,
    rngSeed: Long = 20260912L,
) {
    private val rng = java.util.Random(rngSeed)
    val objects: List<WorldObject> = spec.world.objects.map { WorldObject(it) }
    val body = BodyState()

    var score: Float = 0f
        private set
    var running: Boolean = true
        private set
    var elapsed: Float = 0f
        private set

    val flyRadius: Float = 0.8f

    private val pickupRadius = 3f
    private var collisionCooldown = 0f

    init {
        reset()
    }

    fun reset() {
        val spawn = spec.spawn()
        body.x = spawn?.let { it.x + it.w / 2 } ?: spec.world.width / 2f
        body.y = spawn?.let { it.y + it.h / 2 } ?: spec.world.height / 2f
        body.heading = 0f
        body.speed = 0f
        body.vy = 0f
        body.alive = true
        score = 0f
        elapsed = 0f
        running = true
        collisionCooldown = 0f
        objects.forEach {
            it.x = it.spec.x
            it.y = it.spec.y
            it.passed = false
        }
        neural.clearDrives()
        objects.filter { it.type == WorldObjectType.LIGHT }.forEach { relocateLight(it) }
    }

    private fun relocateLight(light: WorldObject) {
        var tries = 0
        do {
            light.x = rng.nextFloat() * (spec.world.width - light.w)
            light.y = rng.nextFloat() * (spec.world.height - light.h)
            tries++
        } while (tries < 16 && distanceTo(light) < spec.world.width * 0.3f)
    }

    private fun distanceTo(o: WorldObject): Float {
        val dx = body.x - (o.x + o.w / 2)
        val dy = body.y - (o.y + o.h / 2)
        return sqrt(dx * dx + dy * dy)
    }

    /** Advances one rendered frame of the closed loop. */
    fun tick(dt: Float): FrameResult {
        val scaledDt = dt * spec.simulation.speed
        val events = mutableListOf<WorldEvent>()
        var scoreDelta = 0f
        var channels: Map<String, Float> = emptyMap()
        var command = BodyCommand()

        if (running && body.alive) {
            moveObjects(scaledDt)

            // 1. Encode the world into sensory channels.
            channels = SensoryPipeline.encode(spec, objects, body)

            // 2. Drive the bound input groups.
            neural.clearDrives()
            for (binding in spec.sensory) {
                val raw = (channels[binding.channel] ?: 0f) * binding.gain + binding.offset
                neural.driveGroup(binding.groupKey, raw.coerceIn(0f, 2f))
            }

            // 3. Advance the neural simulation.
            neural.step(spec.simulation.timestepsPerFrame)

            // 4. Decode actions; rules override decoder output.
            command = RuleEngine.overlay(
                ActionDecoders.decode(spec.actions, neural),
                spec.rules,
                neural,
            )

            // 5. Physics, collisions, rewards.
            scoreDelta += applyPhysics(command, scaledDt, events)
            scoreDelta += spec.rewards.timePenalty * scaledDt
            score = (score + scoreDelta).coerceAtLeast(0f)
            elapsed += scaledDt
            collisionCooldown = (collisionCooldown - scaledDt).coerceAtLeast(0f)
        }

        return FrameResult(
            channels = channels,
            command = command,
            events = events,
            scoreDelta = scoreDelta,
            score = score,
            episodeOver = !running,
        )
    }

    private fun moveObjects(dt: Float) {
        for (o in objects) {
            val vx = o.params["vx"] ?: 0f
            val vy = o.params["vy"] ?: 0f
            if (vx == 0f && vy == 0f) continue
            o.x += vx * dt
            o.y += vy * dt
            if ((o.params["wrap"] ?: 0f) > 0f) {
                if (o.x + o.w < 0f) {
                    o.x = spec.world.width
                    if ((o.params["respawn"] ?: 0f) > 0f) {
                        o.y = rng.nextFloat() * (spec.world.height - o.h)
                    }
                    o.passed = false
                }
            } else {
                if (o.x < 0f) o.x = 0f
                if (o.x + o.w > spec.world.width) o.x = spec.world.width - o.w
                if (o.y < 0f) o.y = 0f
                if (o.y + o.h > spec.world.height) o.y = spec.world.height - o.h
            }
        }
    }

    private fun applyPhysics(
        command: BodyCommand,
        dt: Float,
        events: MutableList<WorldEvent>,
    ): Float {
        var reward = 0f
        val sim = spec.simulation

        if (spec.isSideScroller()) {
            body.vy += sim.gravity * dt
            if (command.flap) body.vy = -sim.flapImpulse
            body.y += body.vy * dt
            if (body.y < flyRadius || body.y > spec.world.height - flyRadius) {
                body.alive = false
                running = false
                events.add(WorldEvent.Death)
            }
            for (o in objects) {
                if (o.type != WorldObjectType.OBSTACLE) continue
                if (o.overlaps(body.x, body.y, flyRadius)) {
                    body.alive = false
                    running = false
                    events.add(WorldEvent.Death)
                } else if (!o.passed && o.x + o.w < body.x) {
                    o.passed = true
                    reward += spec.rewards.goal
                }
            }
            return reward
        }

        body.heading += command.steer * sim.turnRate * dt
        body.speed = command.forward.coerceIn(0f, 1f) * sim.maxSpeed
        body.x += cos(body.heading) * body.speed * dt
        body.y += sin(body.heading) * body.speed * dt
        body.x = body.x.coerceIn(flyRadius, spec.world.width - flyRadius)
        body.y = body.y.coerceIn(flyRadius, spec.world.height - flyRadius)

        for (o in objects) {
            when (o.type) {
                WorldObjectType.WALL, WorldObjectType.OBSTACLE -> {
                    if (collisionCooldown <= 0f && o.overlaps(body.x, body.y, flyRadius)) {
                        events.add(WorldEvent.Collision(o.type))
                        reward += spec.rewards.collision
                        collisionCooldown = 0.5f
                        pushOut(o)
                    }
                }
                WorldObjectType.FOOD -> {
                    if (distanceTo(o) < pickupRadius) {
                        events.add(WorldEvent.Pickup(o.id))
                        reward += spec.rewards.food
                        o.x = -1000f
                        o.y = -1000f
                    }
                }
                WorldObjectType.LIGHT -> {
                    if (distanceTo(o) < pickupRadius) {
                        events.add(WorldEvent.LightCaught(o.id))
                        reward += spec.rewards.food
                        relocateLight(o)
                    }
                }
                WorldObjectType.DANGER -> {
                    if (collisionCooldown <= 0f && o.overlaps(body.x, body.y, flyRadius)) {
                        events.add(WorldEvent.Danger(o.id))
                        reward += spec.rewards.danger
                        collisionCooldown = 0.5f
                    }
                }
                WorldObjectType.GOAL -> {
                    if (distanceTo(o) < pickupRadius + 1f) {
                        events.add(WorldEvent.GoalReached(o.id))
                        reward += spec.rewards.goal
                        running = false
                    }
                }
                WorldObjectType.SPAWN -> Unit
            }
        }
        return reward
    }

    private fun pushOut(o: WorldObject) {
        val cx = o.x + o.w / 2
        val cy = o.y + o.h / 2
        val dx = body.x - cx
        val dy = body.y - cy
        if (abs(dx) / (o.w + flyRadius * 2) > abs(dy) / (o.h + flyRadius * 2)) {
            body.x = if (dx > 0) o.x + o.w + flyRadius else o.x - flyRadius
        } else {
            body.y = if (dy > 0) o.y + o.h + flyRadius else o.y - flyRadius
        }
    }

    private fun GameSpec.isSideScroller(): Boolean = simulation.mode == "sidescroller"
}

/** Body kinematic state, shared by engine and renderer. */
class BodyState {
    var x: Float = 0f
    var y: Float = 0f
    var heading: Float = 0f
    var speed: Float = 0f
    var vy: Float = 0f
    var alive: Boolean = true
}

/** Sensory encoders: world state -> named channels. */
object SensoryPipeline {

    fun encode(
        spec: GameSpec,
        objects: List<WorldObject>,
        body: BodyState,
    ): Map<String, Float> {
        val needed = spec.sensory.map { it.channel }.toSet()
        if (needed.isEmpty()) return emptyMap()
        val out = HashMap<String, Float>()
        for (channel in needed) {
            out[channel] = when {
                channel.startsWith("visual.") -> directionalChannel(channel, objects, body, WorldObjectType.LIGHT)
                channel.startsWith("smell.") -> directionalChannel(
                    channel, objects, body, WorldObjectType.FOOD, WorldObjectType.GOAL,
                )
                channel == "altitude" -> (1f - body.y / spec.world.height).coerceIn(0f, 1f)
                channel.startsWith("obstacle.") -> directionalChannel(
                    channel, objects, body,
                    WorldObjectType.WALL, WorldObjectType.OBSTACLE, WorldObjectType.DANGER,
                )
                else -> 0f
            }
        }
        return out
    }

    /**
     * Generic bilateral direction sensor: intensity of the nearest matching
     * object, split into left/right channels by its bearing relative to the
     * fly's heading. The side signal is linear in bearing and saturates past
     * ±90°, so targets behind the fly still steer a turn toward the short
     * way around (flies see nearly all the way around). This is the
     * "SimpleLeftRightEncoder" family; a compound eye encoder can replace it
     * behind the same channel contract later.
     */
    private fun directionalChannel(
        channel: String,
        objects: List<WorldObject>,
        body: BodyState,
        vararg types: WorldObjectType,
    ): Float {
        var intensity = 0f
        for (o in objects) {
            if (o.type !in types) continue
            val range = o.params["range"] ?: 40f
            val dx = (o.x + o.w / 2) - body.x
            val dy = (o.y + o.h / 2) - body.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > range) continue
            val base = (1f - dist / range).coerceIn(0f, 1f)
            val angleDiff = normalizeAngle(kotlin.math.atan2(dy, dx) - body.heading)
            val sig = (angleDiff / (Math.PI.toFloat() / 2f)).coerceIn(-1f, 1f)
            intensity += when (channel.substringAfterLast('.')) {
                "left" -> base * (-sig).coerceIn(0f, 1f)
                "right" -> base * sig.coerceIn(0f, 1f)
                else -> 0f
            }
        }
        return intensity.coerceIn(0f, 1f)
    }

    private fun normalizeAngle(a: Float): Float {
        var x = a
        while (x > Math.PI.toFloat()) x -= 2f * Math.PI.toFloat()
        while (x < -Math.PI.toFloat()) x += 2f * Math.PI.toFloat()
        return x
    }
}

/** Action decoders: output group activity -> body command. */
object ActionDecoders {

    fun decode(mappings: List<ActionMapping>, neural: NeuralBridge): BodyCommand {
        var steer = 0f
        var forward = 0f
        var flap = false
        for (m in mappings) {
            when (m.decoder) {
                "difference" -> {
                    val left = neural.groupActivity(m.leftGroup.orEmpty())
                    val right = neural.groupActivity(m.rightGroup.orEmpty())
                    val raw = m.gain * (right - left)
                    steer = if (abs(raw) < m.deadzone) 0f else raw
                }
                "continuous" -> {
                    val a = neural.groupActivity(m.group.orEmpty())
                    forward = (m.gain * a + m.offset).coerceIn(0f, 1f)
                }
                "threshold" -> {
                    val a = neural.groupActivity(m.group.orEmpty())
                    if (a >= m.threshold) flap = true
                }
            }
        }
        return BodyCommand(steer = steer.coerceIn(-1f, 1f), forward = forward, flap = flap)
    }
}

/** Rule engine: IF activity OP value THEN action. Rules override decoders. */
object RuleEngine {

    fun overlay(
        base: BodyCommand,
        rules: List<io.github.pocketfly.core.game.Rule>,
        neural: NeuralBridge,
    ): BodyCommand {
        var steer = base.steer
        var forward = base.forward
        var flap = base.flap
        for (rule in rules) {
            val lhs = neural.groupActivity(rule.source)
            val rhs = rule.source2?.let { neural.groupActivity(it) }
            val fires = when (rule.operator) {
                ">" -> lhs > rule.value
                "<" -> lhs < rule.value
                "diff" -> rhs != null && (lhs - rhs) > rule.value
                "ratio" -> rhs != null && rhs != 0f && (lhs / rhs) > rule.value
                "avg" -> rhs != null && (lhs + rhs) / 2f > rule.value
                else -> false
            }
            if (fires) {
                when (rule.action) {
                    "turn_left" -> steer = -rule.strength
                    "turn_right" -> steer = rule.strength
                    "forward" -> forward = rule.strength.coerceIn(0f, 1f)
                    "flap" -> flap = true
                    "stop" -> forward = 0f
                }
            }
        }
        return BodyCommand(steer = steer, forward = forward, flap = flap)
    }
}
