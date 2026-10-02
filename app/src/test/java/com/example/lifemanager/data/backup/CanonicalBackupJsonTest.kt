package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.BackupValidationException
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class CanonicalBackupJsonTest {
    @Test fun byteCounterIncludesUtf8QuotesEscapesAndControlCharacters() {
        val text = JsonPrimitive("a\n\u0000\"\\中🏠")
        // quotes2 + a1 + newline2 + NUL6 + quote2 + slash2 + Chinese3 + emoji4
        assertEquals(22, CanonicalBackupJson.byteCount(text, 22))
        assertFailsWith<BackupValidationException> { CanonicalBackupJson.byteCount(text, 21) }
    }

    @Test fun byteCounterRejectsEscapeExpansionBeforeAllocatingOutput() {
        assertFailsWith<BackupValidationException> { CanonicalBackupJson.byteCount(JsonPrimitive("\u0000".repeat(10_000)), 1000) }
    }

    @Test fun streamedOutputMatchesJson18EscapingAndUtf8ForAllValidCharacterClasses() {
        val text = (0..0xffff).filter { it !in 0xd800..0xdfff }.map { it.toChar() }.joinToString("") + "🏠😀"
        val json = JsonPrimitive(text)
        val expected = json.toString().toByteArray(Charsets.UTF_8)
        val size = CanonicalBackupJson.byteCount(json, expected.size)
        assertContentEquals(expected, CanonicalBackupJson.encode(json, size))
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(hash, CanonicalBackupJson.sha256(json))
    }
}
