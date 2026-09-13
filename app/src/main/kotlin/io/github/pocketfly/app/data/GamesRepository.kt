package io.github.pocketfly.app.data

import android.content.Context
import android.net.Uri
import io.github.pocketfly.core.game.GameJson
import io.github.pocketfly.core.game.GameSpec
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persists user-created games as `.pocketfly.json` files and handles IO. */
class GamesRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val gamesDir: File
        get() = File(context.filesDir, "games").apply { mkdirs() }

    private val _customGames = MutableStateFlow<List<GameSpec>>(emptyList())
    val customGames: StateFlow<List<GameSpec>> = _customGames.asStateFlow()

    /** In-memory spec being edited; set before opening the editor. */
    var draft: GameSpec? = null

    /**
     * Prepares a new draft spec (blank or from a template) and returns its id.
     * The draft is only persisted when the editor saves it.
     */
    fun newDraft(name: String, template: GameSpec? = null): String {
        val id = "custom-" + System.currentTimeMillis().toString(36)
        draft = template?.copy(id = id, name = name) ?: GameSpec(
            id = id,
            name = name,
            description = "Custom experiment",
            world = io.github.pocketfly.core.game.WorldSpec(
                width = 100f,
                height = 60f,
                objects = listOf(
                    io.github.pocketfly.core.game.ObjectSpec(
                        id = "spawn",
                        type = io.github.pocketfly.core.game.WorldObjectType.SPAWN,
                        x = 48f, y = 28f, w = 2f, h = 2f,
                    ),
                ),
            ),
            sensory = listOf(
                io.github.pocketfly.core.game.SensoryBinding("visual.left", "VIS_L"),
                io.github.pocketfly.core.game.SensoryBinding("visual.right", "VIS_R"),
            ),
            actions = listOf(
                io.github.pocketfly.core.game.ActionMapping(
                    decoder = "difference", action = "steer",
                    leftGroup = "DN_L", rightGroup = "DN_R",
                    gain = 3f, deadzone = 0.04f,
                ),
                io.github.pocketfly.core.game.ActionMapping(
                    decoder = "continuous", action = "forward",
                    group = "DN_F", gain = 1.4f, offset = 0.2f,
                ),
            ),
        )
        return id
    }

    init {
        scope.launch(Dispatchers.IO) { reload() }
    }

    fun reload() {
        val specs = gamesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    GameJson.decode(file.readText())
                } catch (e: Exception) {
                    null
                }
            }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
        _customGames.value = specs
    }

    suspend fun save(spec: GameSpec): Result<GameSpec> = withContext(Dispatchers.IO) {
        try {
            val problems = spec.validate()
            if (problems.isNotEmpty()) {
                return@withContext Result.failure(IOException(problems.first()))
            }
            val id = spec.id.ifBlank { UUID.randomUUID().toString() }
            val fixed = spec.copy(id = id, schemaVersion = GameSpec.SCHEMA_VERSION)
            File(gamesDir, "$id.json").writeText(GameJson.encode(fixed))
            reload()
            Result.success(fixed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(gamesDir, "$id.json").delete()
        reload()
    }

    /** Imports a `.pocketfly.json` from a content URI and saves a copy. */
    suspend fun import(uri: Uri): Result<GameSpec> = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: throw IOException("Cannot read the selected file")
            val spec = GameJson.decode(text)
            val problems = spec.validate()
            if (problems.isNotEmpty()) {
                throw IOException(problems.first())
            }
            val withNewId = spec.copy(id = UUID.randomUUID().toString())
            File(gamesDir, "${withNewId.id}.json").writeText(GameJson.encode(withNewId))
            reload()
            Result.success(withNewId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Writes a game spec to a user-chosen location. */
    suspend fun export(uri: Uri, spec: GameSpec): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(GameJson.encode(spec).toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Cannot write to the selected location")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
