package io.github.pocketfly.sim

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Installs and validates `.pflybrain` packages.
 *
 * A package is a zip archive containing (canonical order matters for the
 * checksum): manifest.json, row_offsets.bin, targets.bin, weights.bin,
 * neurons.bin. On install, the manifest is parsed and cross-checked against
 * the payload file sizes, and the sha256 of the concatenated payload is
 * verified before anything is written to disk.
 */
object BrainPackage {

    const val FORMAT_VERSION = 1

    /** Payload entries in checksum-canonical order (manifest excluded). */
    val PAYLOAD_ENTRIES = listOf(
        "row_offsets.bin",
        "targets.bin",
        "weights.bin",
        "neurons.bin",
    )

    /** Upper bound for a single package, guarding against zip bombs. */
    const val MAX_PACKAGE_BYTES: Long = 4L * 1024 * 1024 * 1024

    fun parseManifest(json: String): BrainManifest {
        val manifest = try {
            Json.decodeFromString<BrainManifest>(json)
        } catch (e: Exception) {
            throw IOException("Invalid brain manifest: ${e.message}", e)
        }
        if (manifest.formatVersion != FORMAT_VERSION) {
            throw IOException(
                "Unsupported brain format version ${manifest.formatVersion} " +
                    "(expected $FORMAT_VERSION)",
            )
        }
        return manifest
    }

    /**
     * Validates and extracts a `.pflybrain` stream into `targetDir`.
     * Throws [IOException] with a user-presentable message on any problem.
     */
    fun install(input: InputStream, targetDir: File): BrainManifest {
        val entries = readZipEntries(input)
        val manifestJson = entries["manifest.json"]
            ?: throw IOException("Package is missing manifest.json")
        val manifest = parseManifest(manifestJson.toString(Charsets.UTF_8))

        val payload = PAYLOAD_ENTRIES.map { name ->
            entries[name] ?: throw IOException("Package is missing $name")
        }

        verifyPayload(manifest, payload)
        verifyChecksum(manifest, payload)

        val staging = File(targetDir.parentFile, targetDir.name + ".staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            PAYLOAD_ENTRIES.forEach { name ->
                File(staging, name).writeBytes(entries.getValue(name))
            }
            File(staging, "manifest.json").writeBytes(manifestJson)
            targetDir.deleteRecursively()
            if (!staging.renameTo(targetDir)) {
                throw IOException("Could not finalize installation directory")
            }
        } finally {
            staging.deleteRecursively()
        }
        return manifest
    }

    /** Loads a previously installed brain from its directory. */
    fun loadInstalled(dir: File): InstalledBrain {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) {
            throw IOException("No manifest.json in ${dir.name}")
        }
        val manifest = parseManifest(manifestFile.readText(Charsets.UTF_8))
        val payload = PAYLOAD_ENTRIES.map { name ->
            val f = File(dir, name)
            if (!f.isFile) throw IOException("Installed brain is missing $name")
            f.readBytes()
        }
        verifyPayload(manifest, payload)
        return InstalledBrain(manifest, dir.absolutePath)
    }

    private fun verifyPayload(manifest: BrainManifest, payload: List<ByteArray>) {
        val (offsets, targets, weights, neurons) = payload
        val offsetEntry = if (manifest.offsetType == "u64") 8 else 4
        val weightEntry = if (manifest.weightType == "u16") 2 else 4
        val n = manifest.neuronCount
        val e = manifest.edgeCount

        if (offsets.size.toLong() != (n + 1L) * offsetEntry) {
            throw IOException("row_offsets.bin size does not match neuronCount")
        }
        if (targets.size.toLong() != e * 4L) {
            throw IOException("targets.bin size does not match edgeCount")
        }
        if (weights.size.toLong() != e * weightEntry) {
            throw IOException("weights.bin size does not match edgeCount")
        }
        if (neurons.size.toLong() != n * 8L) {
            throw IOException("neurons.bin size does not match neuronCount")
        }
    }

    private fun verifyChecksum(manifest: BrainManifest, payload: List<ByteArray>) {
        val expected = manifest.checksum?.value?.trim()?.lowercase().orEmpty()
        if (expected.isEmpty()) return  // checksum optional in the format
        if (manifest.checksum?.algorithm?.lowercase() != "sha256") {
            throw IOException("Unsupported checksum algorithm: ${manifest.checksum?.algorithm}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        payload.forEach { digest.update(it) }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw IOException("Checksum mismatch: package is corrupted")
        }
    }

    private fun readZipEntries(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val bytes = zip.readBytes()
                total += bytes.size
                if (total > MAX_PACKAGE_BYTES) {
                    throw IOException("Package exceeds the maximum supported size")
                }
                if (out.containsKey(name)) {
                    throw IOException("Duplicate entry in package: $name")
                }
                out[name] = bytes
                zip.closeEntry()
            }
        }
        if (out.isEmpty()) throw IOException("Package is empty or not a zip archive")
        return out
    }
}

private val Json = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
}
