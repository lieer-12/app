package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Pure data-layer codec; it never opens a file or modifies the database. */
class BackupJsonCodec(private val limits: BackupLimits = BackupLimits()) : BackupCodec {
    override fun encode(document: BackupDocument): ByteArray {
        BackupValidator.validate(document, limits)
        val payload = BackupJsonMapping.toJson(document.payload)
        val root = buildJsonObject {
            put("format", "life-manager-backup")
            put("formatVersion", 1)
            put("schemaVersion", 5)
            put("appVersion", document.appVersion)
            put("exportedAt", document.exportedAt)
            put("payload", payload)
            put("payloadSha256", "0".repeat(64))
        }
        // Includes UTF-8/escaping/keys/syntax/metadata before allocating output.
        val size = CanonicalBackupJson.byteCount(root, limits.maxBytes)
        val checkedRoot = JsonObject(root + ("payloadSha256" to JsonPrimitive(CanonicalBackupJson.sha256(payload))))
        return CanonicalBackupJson.encode(checkedRoot, size)
    }

    override fun decode(bytes: ByteArray): BackupDocument {
        checkBackup(bytes.size <= limits.maxBytes, "备份超过 ${limits.maxBytes} 字节限制")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) { throw BackupValidationException("备份不是有效 UTF-8 文件", error) }
        val root = StrictBackupJson(text, limits).parse().objectWithKeys(setOf("format", "formatVersion", "schemaVersion", "appVersion", "exportedAt", "payload", "payloadSha256"))
        checkBackup(root.getValue("format").textValue() == "life-manager-backup", "未知备份格式")
        checkBackup(root.getValue("formatVersion").integerValue() == 1L, "不支持的备份格式版本")
        checkBackup(root.getValue("schemaVersion").integerValue() == 5L, "不支持的数据库版本")
        val payloadJson = root.getValue("payload")
        val storedHash = root.getValue("payloadSha256").textValue()
        checkBackup(storedHash.matches(Regex("[0-9a-f]{64}")), "备份校验值格式无效")
        checkBackup(MessageDigest.isEqual(storedHash.toByteArray(Charsets.US_ASCII), CanonicalBackupJson.sha256(payloadJson).toByteArray(Charsets.US_ASCII)), "备份校验不通过，文件可能已损坏")
        return BackupDocument(root.getValue("appVersion").textValue(), root.getValue("exportedAt").textValue(), BackupJsonMapping.fromJson(payloadJson))
            .also { BackupValidator.validate(it, limits) }
    }
}
