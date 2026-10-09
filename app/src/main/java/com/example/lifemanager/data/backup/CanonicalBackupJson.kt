package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.checkBackup
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Same escaping as JSON 1.8.1, but no full escaped String/UTF-8 copy to count. */
internal object CanonicalBackupJson {
    fun byteCount(value: JsonElement, limit: Int): Int {
        var count = 0
        write(value, object : Sink {
            override fun byte(value: Int) {
                checkBackup(count < limit, "备份超过 $limit 字节限制")
                count++
            }
        })
        return count
    }

    fun encode(value: JsonElement, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var position = 0
        write(value, object : Sink {
            override fun byte(value: Int) { checkBackup(position < bytes.size, "备份输出大小变化"); bytes[position++] = value.toByte() }
        })
        checkBackup(position == bytes.size, "备份输出大小变化")
        return bytes
    }

    fun sha256(value: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var position = 0
        write(value, object : Sink {
            override fun byte(value: Int) {
                buffer[position++] = value.toByte()
                if (position == buffer.size) { digest.update(buffer); position = 0 }
            }
        })
        if (position > 0) digest.update(buffer, 0, position)
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    private interface Sink { fun byte(value: Int) }
    private fun Sink.ascii(value: String) { value.forEach { byte(it.code) } }

    private fun write(value: JsonElement, sink: Sink) {
        when (value) {
            is JsonObject -> {
                sink.byte('{'.code)
                value.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
                    if (index > 0) sink.byte(','.code)
                    quote(entry.key, sink); sink.byte(':'.code); write(entry.value, sink)
                }
                sink.byte('}'.code)
            }
            is JsonArray -> {
                sink.byte('['.code)
                value.forEachIndexed { index, entry -> if (index > 0) sink.byte(','.code); write(entry, sink) }
                sink.byte(']'.code)
            }
            is JsonPrimitive -> if (value.isString) quote(value.content, sink) else sink.ascii(value.content)
        }
    }

    private fun quote(text: String, sink: Sink) {
        sink.byte('"'.code)
        var position = 0
        while (position < text.length) {
            val char = text[position++]
            when (char) {
                '"' -> sink.ascii("\\\"")
                '\\' -> sink.ascii("\\\\")
                '\n' -> sink.ascii("\\n")
                '\r' -> sink.ascii("\\r")
                '\t' -> sink.ascii("\\t")
                '\b' -> sink.ascii("\\b")
                '\u000c' -> sink.ascii("\\f")
                else -> {
                    val code = char.code
                    when {
                        code < 32 -> {
                            sink.ascii("\\u00")
                            sink.byte("0123456789abcdef"[code ushr 4].code)
                            sink.byte("0123456789abcdef"[code and 15].code)
                        }
                        code < 128 -> sink.byte(code)
                        code < 2048 -> { sink.byte(0xc0 or (code ushr 6)); sink.byte(0x80 or (code and 63)) }
                        char.isHighSurrogate() -> {
                            checkBackup(position < text.length && text[position].isLowSurrogate(), "无效 Unicode")
                            val point = Character.toCodePoint(char, text[position++])
                            sink.byte(0xf0 or (point ushr 18)); sink.byte(0x80 or ((point ushr 12) and 63))
                            sink.byte(0x80 or ((point ushr 6) and 63)); sink.byte(0x80 or (point and 63))
                        }
                        else -> {
                            checkBackup(!char.isLowSurrogate(), "无效 Unicode")
                            sink.byte(0xe0 or (code ushr 12)); sink.byte(0x80 or ((code ushr 6) and 63)); sink.byte(0x80 or (code and 63))
                        }
                    }
                }
            }
        }
        sink.byte('"'.code)
    }
}
