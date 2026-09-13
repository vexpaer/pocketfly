package io.github.pocketfly.core.game

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON (de)serialization for `.pocketfly.json` game files. */
object GameJson {

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    @Throws(SerializationException::class, IllegalArgumentException::class)
    fun decode(text: String): GameSpec {
        val spec = json.decodeFromString<GameSpec>(text)
        require(spec.schemaVersion == GameSpec.SCHEMA_VERSION) {
            "Unsupported schemaVersion ${spec.schemaVersion} (expected ${GameSpec.SCHEMA_VERSION})"
        }
        return spec
    }

    fun encode(spec: GameSpec): String = json.encodeToString(spec)
}
