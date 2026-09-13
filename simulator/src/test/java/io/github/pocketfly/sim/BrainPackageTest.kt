package io.github.pocketfly.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BrainPackageTest {

    private fun sampleZipBytes(): ByteArray {
        val dir = File(
            System.getenv("POCKETFLY_SAMPLE_ZIP") ?: return ByteArray(0),
        )
        return if (dir.isFile) dir.readBytes() else ByteArray(0)
    }

    /** Builds a tiny valid package in memory. */
    private fun tinyPackage(
        manifest: String = tinyManifest,
        corrupt: ((MutableMap<String, ByteArray>) -> Unit)? = null,
    ): ByteArray {
        val entries = sortedMapOf(
            "manifest.json" to manifest.toByteArray(),
            "row_offsets.bin" to byteArrayOf(
                0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0,
            ),
            "targets.bin" to byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0),
            "weights.bin" to byteArrayOf(
                0, 0, -128, 63, 0, 0, -128, 63, // 1.0f, 1.0f (little-endian)
            ),
            "neurons.bin" to ByteArray(16),
        )
        // Checksum over the payload as built; entries the corrupt callback
        // later removes are skipped so a valid-checksum zip can miss an entry.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (name in BrainPackage.PAYLOAD_ENTRIES) {
            val bytes = entries[name] ?: continue
            digest.update(bytes)
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        val finalManifest = manifest.replace("__CHECKSUM__", checksum)
        entries["manifest.json"] = finalManifest.toByteArray()
        // Applied after checksumming, so payload corruption is detectable.
        corrupt?.invoke(entries)

        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val tinyManifest = """
        {
          "formatVersion": 1,
          "id": "tiny",
          "name": "Tiny Brain",
          "mode": "sample",
          "neuronCount": 2,
          "edgeCount": 2,
          "weightScale": 1.0,
          "groups": [{"id":0,"key":"VIS_L","name":"Visual Left","kind":"sensory"}],
          "checksum": {"algorithm":"sha256","value":"__CHECKSUM__"}
        }
    """.trimIndent()

    @Test
    fun installExtractsAndLoads() {
        val target = File.createTempFile("pfly", ".brain").let {
            it.delete()
            File(it.parentFile, it.name)
        }
        try {
            val manifest = BrainPackage.install(
                ByteArrayInputStream(tinyPackage()),
                target,
            )
            assertEquals("tiny", manifest.id)
            assertEquals(2, manifest.neuronCount)

            val installed = BrainPackage.loadInstalled(target)
            assertEquals("Tiny Brain", installed.manifest.name)
            assertEquals(RuntimeMode.SAMPLE, installed.mode)
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun installRejectsCorruptedPayload() {
        val err = assertFailsWith<IOException> {
            BrainPackage.install(
                ByteArrayInputStream(
                    tinyPackage(
                        corrupt = { e -> e["weights.bin"] = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0) },
                    ),
                ),
                File("/tmp/never-written-brain"),
            )
        }
        assertTrue(err.message!!.contains("Checksum"), "expected checksum failure, got: $err")
    }

    @Test
    fun installRejectsMissingEntry() {
        val err = assertFailsWith<IOException> {
            BrainPackage.install(
                ByteArrayInputStream(
                    tinyPackage(corrupt = { e -> e.remove("neurons.bin") }),
                ),
                File("/tmp/never-written-brain"),
            )
        }
        assertTrue(err.message!!.contains("neurons.bin"))
    }

    @Test
    fun installRejectsWrongFormatVersion() {
        val manifest = tinyManifest.replace("\"formatVersion\": 1", "\"formatVersion\": 2")
        val err = assertFailsWith<IOException> {
            BrainPackage.install(
                ByteArrayInputStream(tinyPackage(manifest = manifest)),
                File("/tmp/never-written-brain"),
            )
        }
        assertTrue(err.message!!.contains("format version"))
    }

    @Test
    fun installRejectsNonZip() {
        assertFailsWith<IOException> {
            BrainPackage.install(
                ByteArrayInputStream("hello".toByteArray()),
                File("/tmp/never-written-brain"),
            )
        }
    }

    @Test
    fun samplePackageInstallsWhenPresent() {
        val bytes = sampleZipBytes()
        if (bytes.isEmpty()) {
            println("sample .pflybrain not available; skipping")
            return
        }
        val target = File.createTempFile("pfly-sample", ".brain").let {
            it.delete()
            File(it.parentFile, it.name)
        }
        try {
            val manifest = BrainPackage.install(ByteArrayInputStream(bytes), target)
            assertEquals(1024, manifest.neuronCount)
            assertEquals(10, manifest.groups.size)
            assertEquals("VIS_L", manifest.groups[0].key)
            assertEquals("sample-1024", manifest.id)
        } finally {
            target.deleteRecursively()
        }
    }
}
