package io.github.pocketfly.app.data

import io.github.pocketfly.core.engine.BodyCommand
import io.github.pocketfly.core.engine.GameEngine
import io.github.pocketfly.core.engine.WorldEvent
import io.github.pocketfly.core.engine.WorldObject
import io.github.pocketfly.core.game.GameSpec
import io.github.pocketfly.core.game.WorldObjectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives one game's closed loop on the simulation thread and publishes
 * immutable view state for rendering on the UI thread.
 */
class GameSession(
    private val repo: RuntimeRepository,
    val spec: GameSpec,
    private val frameHz: Int = 36,
) {
    private val engine = GameEngine(spec, repo.engineBridge, rngSeed = System.nanoTime())

    data class ObjectView(
        val id: String,
        val type: WorldObjectType,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
    )

    data class View(
        val bodyX: Float,
        val bodyY: Float,
        val heading: Float,
        val speed: Float,
        val objects: List<ObjectView>,
        val channels: Map<String, Float>,
        val command: BodyCommand,
        val events: List<WorldEvent>,
        val score: Float,
        val episodeOver: Boolean,
        val stepsPerSecond: Double,
        val activeNeurons: Int,
        val totalSteps: Long,
        val groupActivity: Map<String, Float>,
        val elapsed: Float,
    )

    val view = kotlinx.coroutines.flow.MutableStateFlow<View?>(null)
    val paused = kotlinx.coroutines.flow.MutableStateFlow(false)

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        repo.setFreeRun(false)
        job = scope.launch(repo.simDispatcher) {
            val frameNs = 1_000_000_000L / frameHz
            var next = System.nanoTime()
            while (isActive) {
                if (!paused.value) {
                    tick()
                }
                next += frameNs
                val wait = (next - System.nanoTime()) / 1_000_000L
                if (wait > 0) delay(wait) else next = System.nanoTime()
            }
        }
    }

    private fun tick() {
        val frame = engine.tick(1f / frameHz)
        val snap = repo.snapshot.value
        view.value = View(
            bodyX = engine.body.x,
            bodyY = engine.body.y,
            heading = engine.body.heading,
            speed = engine.body.speed,
            objects = engine.objects.map { ObjectView(it.spec.id, it.type, it.x, it.y, it.w, it.h) },
            channels = frame.channels,
            command = frame.command,
            events = frame.events,
            score = frame.score,
            episodeOver = frame.episodeOver,
            stepsPerSecond = snap.stepsPerSecond,
            activeNeurons = snap.activeNeurons,
            totalSteps = snap.totalSteps,
            groupActivity = snap.groupActivity,
            elapsed = engine.elapsed,
        )
    }

    fun pause() {
        paused.value = true
    }

    fun resume() {
        paused.value = false
    }

    /** Single-step while paused: advance one rendered frame's worth of loop. */
    fun stepFrames(scope: CoroutineScope, frames: Int = 1) {
        scope.launch(repo.simDispatcher) {
            repeat(frames) { if (isActive) tick() }
        }
    }

    fun reset(scope: CoroutineScope) {
        scope.launch(repo.simDispatcher) {
            engine.reset()
            tick()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        fun objectColor(type: WorldObjectType): Int = when (type) {
            WorldObjectType.WALL -> 0xFF3A4150.toInt()
            WorldObjectType.FOOD -> 0xFF7FB4A8.toInt()
            WorldObjectType.GOAL -> 0xFFE8B44A.toInt()
            WorldObjectType.LIGHT -> 0xFFF3D28A.toInt()
            WorldObjectType.DANGER -> 0xFFE5735F.toInt()
            WorldObjectType.OBSTACLE -> 0xFF545E70.toInt()
            WorldObjectType.SPAWN -> 0xFF8FA3C4.toInt()
        }

        fun worldObjectsOf(spec: GameSpec): List<WorldObject> = spec.world.objects.map { WorldObject(it) }
    }
}
