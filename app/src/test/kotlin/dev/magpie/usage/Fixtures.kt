package dev.magpie.usage

import dev.magpie.usage.core.pack.Gzip
import dev.magpie.usage.core.pack.PackParser
import dev.magpie.usage.core.model.SharedDayPack

/** Loads the Python-sealed cross-language fixtures. */
object Fixtures {

    data class FixtureFile(
        val name: String,
        val sealedBase64: String,
        val expected: Map<String, Any?>,
    )

    private fun loadJson(): Map<String, Any?> {
        val text = Fixtures::class.java.getResourceAsStream("/fixtures/fixtures.json")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: throw IllegalStateException("缺少 fixtures.json 测试资源")
        val raw = dev.magpie.usage.testsupport.MiniJson.parse(text)
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    val passphrase: String by lazy { loadJson()["passphrase"] as String }

    val files: List<FixtureFile> by lazy {
        @Suppress("UNCHECKED_CAST")
        (loadJson()["files"] as List<Map<String, Any?>>).map {
            FixtureFile(
                name = it["name"] as String,
                sealedBase64 = it["sealedBase64"] as String,
                expected = (it["expected"] ?: emptyMap<String, Any?>()) as Map<String, Any?>,
            )
        }
    }

    fun byName(name: String): FixtureFile = files.first { it.name == name }

    /** Decode + gunzip + parse with the production pipeline. */
    fun open(file: FixtureFile, passphrase: String = this.passphrase): SharedDayPack {
        val sealed = java.util.Base64.getDecoder().decode(file.sealedBase64)
        val plain = dev.magpie.usage.core.crypto.MagpieEnvelopeDecoder.decode(sealed, passphrase.toCharArray())
        return PackParser.parse(Gzip.gunzip(plain), file.name)
    }
}
