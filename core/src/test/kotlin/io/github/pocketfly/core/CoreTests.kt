package io.github.pocketfly.core

import io.github.pocketfly.core.engine.BodyCommand
import io.github.pocketfly.core.engine.GameEngine
import io.github.pocketfly.core.engine.NeuralBridge
import io.github.pocketfly.core.engine.WorldEvent
import io.github.pocketfly.core.game.GameJson
import io.github.pocketfly.core.game.GameSpec
import io.github.pocketfly.core.games.BuiltInGames
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deterministic fake: drives are echoed back as group activity, and the
 *  forward descending group aggregates visual drive the way the sample
 *  brain's integrator pathway does. */
private class RelayNeural : NeuralBridge {
    val drives = HashMap<String, Float>()
    var steps = 0L

    override fun driveGroup(groupKey: String, value: Float) {
        drives[groupKey] = value
    }

    override fun clearDrives() = drives.clear()

    override fun step(steps: Int) {
        this.steps += steps
    }

    override fun groupActivity(groupKey: String): Float = when (groupKey) {
        // Ipsilateral relay: left visual drives left descending, right drives
        // right; the forward group aggregates both, like the sample brain's
        // central -> integrator -> DN_F pathway.
        "DN_L" -> (drives["VIS_L"] ?: 0f).coerceIn(0f, 1f)
        "DN_R" -> (drives["VIS_R"] ?: 0f).coerceIn(0f, 1f)
        "DN_F" -> ((drives["VIS_L"] ?: 0f) + (drives["VIS_R"] ?: 0f)).coerceIn(0f, 1f)
        else -> (drives[groupKey] ?: 0f).coerceIn(0f, 1f)
    }
}

class BuiltInGamesTest {

    @Test
    fun allBuiltInsValidate() {
        for (game in BuiltInGames.all) {
            assertEquals(emptyList(), game.validate(), "${game.name} should validate")
        }
    }

    @Test
    fun builtInsSurviveJsonRoundTrip() {
        for (game in BuiltInGames.all) {
            val text = GameJson.encode(game)
            val back = GameJson.decode(text)
            assertEquals(game, back, "${game.name} round trip")
        }
    }
}

class GameJsonTest {

    @Test
    fun rejectsWrongSchemaVersion() {
        val spec = BuiltInGames.lightChase().copy(schemaVersion = 99)
        val text = GameJson.encode(spec)
        val err = runCatching { GameJson.decode(text) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException, "wrong schemaVersion must be rejected")
    }

    @Test
    fun rejectsGarbage() {
        val err = runCatching { GameJson.decode("not json at all") }.exceptionOrNull()
        assertTrue(err != null)
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val text = """
            {"schemaVersion":1,"id":"x","name":"X","brandNewField":42,
             "world":{"width":100,"height":60,"objects":[
               {"id":"s","type":"SPAWN","x":1,"y":1}]}}
        """.trimIndent()
        val spec = GameJson.decode(text)
        assertEquals("x", spec.id)
        assertEquals(1, spec.world.objects.size)
    }
}

class RuleEngineTest {

    private fun neural(vararg pairs: Pair<String, Float>) = object : NeuralBridge {
        override fun driveGroup(groupKey: String, value: Float) = Unit
        override fun clearDrives() = Unit
        override fun step(steps: Int) = Unit
        override fun groupActivity(groupKey: String): Float =
            pairs.toMap()[groupKey] ?: 0f
    }

    private fun rule(
        source: String,
        op: String,
        value: Float,
        action: String,
        source2: String? = null,
    ) = io.github.pocketfly.core.game.Rule(
        source = source, source2 = source2, operator = op, value = value, action = action,
    )

    @Test
    fun greaterThanFires() {
        val cmd = io.github.pocketfly.core.engine.RuleEngine.overlay(
            BodyCommand(),
            listOf(rule("DN_R", ">", 0.3f, "turn_right")),
            neural("DN_R" to 0.5f),
        )
        assertEquals(1f, cmd.steer)
    }

    @Test
    fun lessThanFires() {
        val cmd = io.github.pocketfly.core.engine.RuleEngine.overlay(
            BodyCommand(),
            listOf(rule("DN_F", "<", 0.1f, "flap")),
            neural("DN_F" to 0.05f),
        )
        assertTrue(cmd.flap)
    }

    @Test
    fun signedDifference() {
        val cmd = io.github.pocketfly.core.engine.RuleEngine.overlay(
            BodyCommand(),
            listOf(rule("DN_R", "diff", 0.15f, "turn_right", source2 = "DN_L")),
            neural("DN_R" to 0.6f, "DN_L" to 0.4f),
        )
        assertEquals(1f, cmd.steer)
    }

    @Test
    fun ratioAndAverage() {
        val cmdRatio = io.github.pocketfly.core.engine.RuleEngine.overlay(
            BodyCommand(),
            listOf(rule("A", "ratio", 2f, "turn_left", source2 = "B")),
            neural("A" to 0.8f, "B" to 0.3f),
        )
        assertEquals(-1f, cmdRatio.steer)

        val cmdAvg = io.github.pocketfly.core.engine.RuleEngine.overlay(
            BodyCommand(),
            listOf(rule("A", "avg", 0.5f, "forward", source2 = "B")),
            neural("A" to 0.6f, "B" to 0.5f),
        )
        assertEquals(1f, cmdAvg.forward)
    }

    @Test
    fun rulesOverrideDecoders() {
        val cmd = io.github.pocketfly.core.engine.RuleEngine.overlay(
            BodyCommand(steer = 0.5f, forward = 0.9f),
            listOf(rule("DN_L", ">", 0.1f, "turn_left")),
            neural("DN_L" to 0.9f),
        )
        assertEquals(-1f, cmd.steer)
    }
}

class GameEngineTest {

    private val dt = 1f / 36f

    @Test
    fun lightChaseCatchesLightInClosedLoop() {
        val neural = RelayNeural()
        val engine = GameEngine(BuiltInGames.lightChase(), neural)
        var caught = 0
        repeat(1500) {
            val frame = engine.tick(dt)
            caught += frame.events.count { it is WorldEvent.LightCaught }
        }
        assertTrue(caught > 0, "relay loop should catch the wandering light; caught=$caught")
        assertTrue(engine.score > 0f)
    }

    @Test
    fun turningRespondsToDecodedActivity() {
        val neural = RelayNeural()
        val engine = GameEngine(BuiltInGames.lightChase(), neural)
        // Put the light on the fly's right side (y-down coords: below-right);
        // the encoder must drive VIS_R harder than VIS_L, and the difference
        // decoder must turn the fly clockwise (right) toward it.
        engine.body.x = 30f
        engine.body.y = 30f
        engine.body.heading = 0f
        val light = engine.objects.first { it.spec.type == io.github.pocketfly.core.game.WorldObjectType.LIGHT }
        light.x = 50f
        light.y = 44f
        val headingBefore = engine.body.heading
        repeat(12) { engine.tick(dt) }
        assertTrue(engine.body.heading > headingBefore, "right-side light must turn the fly right")
    }

    @Test
    fun encoderSplitsLightBySide() {
        val neural = RelayNeural()
        val engine = GameEngine(BuiltInGames.lightChase(), neural)
        engine.body.x = 30f
        engine.body.y = 30f
        engine.body.heading = 0f
        val light = engine.objects.first { it.spec.type == io.github.pocketfly.core.game.WorldObjectType.LIGHT }
        // Dead ahead: both channels ~0. To the left (y-up is -y): left channel.
        light.x = 60f; light.y = 29f
        val ahead = io.github.pocketfly.core.engine.SensoryPipeline.encode(
            engine.spec, engine.objects, engine.body,
        )
        light.x = 45f; light.y = 18f
        val left = io.github.pocketfly.core.engine.SensoryPipeline.encode(
            engine.spec, engine.objects, engine.body,
        )
        assertTrue(left.getOrDefault("visual.left", 0f) > 0.2f, "light to the left excites left channel")
        assertTrue(left.getOrDefault("visual.right", 0f) < 0.05f)
        assertTrue(
            ahead.getOrDefault("visual.left", 0f) < 0.05f &&
                ahead.getOrDefault("visual.right", 0f) < 0.05f,
            "light dead ahead splits evenly (both ~0)",
        )
    }

    @Test
    fun flapFallsAndDiesWithoutFlap() {
        val neural = RelayNeural()
        // No rules -> nothing flaps -> gravity wins.
        val engine = GameEngine(BuiltInGames.flap().copy(rules = emptyList()), neural)
        var death = false
        repeat(600) {
            val frame = engine.tick(dt)
            if (frame.events.any { it is WorldEvent.Death }) death = true
        }
        assertTrue(death, "fly with no flap must eventually fall")
        assertFalse(engine.running)
    }

    @Test
    fun flapRuleProducesOscillation() {
        val neural = RelayNeural()
        val engine = GameEngine(BuiltInGames.flap(), neural)
        var flaps = 0
        repeat(400) {
            val frame = engine.tick(dt)
            if (frame.command.flap) flaps++
        }
        assertTrue(flaps > 10, "altitude-hold loop should flap repeatedly; flaps=$flaps")
        assertTrue(engine.body.alive, "oscillation should keep the fly off the floor")
    }

    @Test
    fun resetRestoresEpisode() {
        val neural = RelayNeural()
        val engine = GameEngine(BuiltInGames.obstacleAvoidance(), neural)
        repeat(120) { engine.tick(dt) }
        engine.reset()
        assertEquals(0f, engine.score)
        assertTrue(engine.running)
        assertTrue(abs(engine.body.x - 9f) < 2f, "fly returns to spawn")
    }
}
