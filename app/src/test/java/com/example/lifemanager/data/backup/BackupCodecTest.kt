package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.model.*
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class BackupCodecTest {
    private val codec = BackupJsonCodec()
    private val empty = BackupPayload(BackupFixtures.emptyTables, AppSettings())
    private fun encode(payload: BackupPayload = empty) = codec.encode(BackupDocument("0.1.0", "2026-10-02T08:00:00Z", payload))

    @Test fun emptyBackupContainsEveryTableAndSettingsButNoLocalGeneration() {
        val bytes = encode()
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("life-manager-backup", root["format"]!!.jsonPrimitive.content)
        assertEquals(1, root["formatVersion"]!!.jsonPrimitive.int)
        assertEquals(5, root["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals(BackupFixtures.emptyTables.keys + "settings", root["payload"]!!.jsonObject.keys)
        assertFalse(bytes.decodeToString().contains("generation"))
        assertEquals(empty, codec.decode(bytes).payload)
    }

    @Test fun preservesAllFieldsLongPrecisionRawDaysNullsAndPreStartHistory() {
        val payload = BackupFixtures.fullPayload()
        val encoded = encode(payload)
        assertTrue(encoded.decodeToString().contains("9223372036854775807"))
        val result = codec.decode(encoded).payload
        assertEquals(payload, result)
        assertEquals(BackupValue.Integer(Long.MAX_VALUE), result.tables.getValue("subscriptions").single().fields["amountMinor"])
        assertEquals(BackupValue.Text("7,1,1"), result.tables.getValue("schedules").single().fields["repeatDaysOfWeek"])
        assertEquals(BackupValue.Integer(19999), result.tables.getValue("habit_records").single().fields["date"])
        assertEquals(setOf(1, 7), result.settings.defaultReminderDays)
    }

    @Test fun checksumUsesCanonicalPayloadAndAcceptsWhitespaceAndObjectKeyReordering() {
        val document = Json.parseToJsonElement(encode().decodeToString()).jsonObject
        val canonical = canonical(document.getValue("payload"))
        val expected = hash(canonical.toString().toByteArray())
        assertEquals(expected, document.getValue("payloadSha256").jsonPrimitive.content)
        assertEquals("31ba496ea2969f664d44817f32e809eb70416cf6d660ee4f207a1287f9d77f16", document.getValue("payloadSha256").jsonPrimitive.content)
        val payload = document.getValue("payload").jsonObject
        val settings = payload.getValue("settings").jsonObject
        val reversedPayload = JsonObject((payload + ("settings" to JsonObject(settings.entries.reversed().associate { it.toPair() }))).entries.reversed().associate { it.toPair() })
        val reordered = JsonObject((document + ("payload" to reversedPayload)).entries.reversed().associate { it.toPair() })
        val pretty = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), reordered)
        assertEquals(empty, codec.decode(pretty.toByteArray()).payload)
    }

    @Test fun rejectsModifiedPayloadEvenWhenJsonIsWellFormed() {
        val bytes = encode().decodeToString().replace("\"CNY\"", "\"EUR\"").toByteArray()
        assertFailsWith<BackupValidationException> { codec.decode(bytes) }
    }

    @Test fun rejectsUnknownVersionsFieldsAndMissingTables() {
        for (text in listOf(
            encode().decodeToString().replace("\"formatVersion\":1", "\"formatVersion\":2"),
            encode().decodeToString().replace("\"schemaVersion\":5", "\"schemaVersion\":6"),
            encode().decodeToString().replace("\"format\":", "\"extra\":0,\"format\":"),
        )) assertFailsWith<BackupValidationException> { codec.decode(text.toByteArray()) }
        val root = Json.parseToJsonElement(encode().decodeToString()).jsonObject
        val payload = root.getValue("payload").jsonObject
        assertFailsWith<BackupValidationException> { codec.decode(replacePayload(root, JsonObject(payload - "tags"))) }
    }

    @Test fun rejectsDuplicateJsonMembersIncludingEscapedEquivalentNames() {
        val json = encode().decodeToString()
        for (key in listOf("formatVersion", "format\\u0056ersion")) {
            val duplicate = json.replace("\"formatVersion\":1", "\"formatVersion\":1,\"$key\":1")
            assertFailsWith<BackupValidationException> { codec.decode(duplicate.toByteArray()) }
        }
        val nested = json.replace("\"defaultCurrency\":\"CNY\"", "\"defaultCurrency\":\"CNY\",\"defaultCurrency\":\"CNY\"")
        assertFailsWith<BackupValidationException> { codec.decode(nested.toByteArray()) }
    }

    @Test fun rejectsTruncatedTrailingMalformedUtf8AndExcessiveNesting() {
        val bytes = encode()
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes + " true".toByteArray(), byteArrayOf(0xC3.toByte(), 0x28), "[".repeat(100).toByteArray())) {
            assertFailsWith<BackupValidationException> { codec.decode(bad) }
        }
    }

    @Test fun rejectsWrongTypesOverflowUnknownBusinessFieldsAndMissingNullableField() {
        val root = Json.parseToJsonElement(encode(BackupFixtures.fullPayload()).decodeToString()).jsonObject
        val payload = root.getValue("payload").jsonObject
        val todo = payload.getValue("todos").jsonArray.single().jsonObject
        for (bad in listOf(
            JsonObject(todo + ("id" to JsonPrimitive("41"))),
            JsonObject(todo + ("id" to JsonPrimitive("9223372036854775808"))),
            JsonObject(todo + ("isCompleted" to JsonPrimitive(0))),
            JsonObject(todo + ("priority" to JsonPrimitive("URGENT"))),
            JsonObject(todo + ("color" to JsonPrimitive(1))),
            JsonObject(todo - "description"),
        )) {
            val changed = JsonObject(payload + ("todos" to JsonArray(listOf(bad))))
            assertFailsWith<BackupValidationException> { codec.decode(replacePayload(root, changed)) }
        }
        val numericOverflow = root.toString().replace("\"id\":41", "\"id\":9223372036854775808")
        assertFailsWith<BackupValidationException> { codec.decode(numericOverflow.toByteArray()) }
        val decimal = root.toString().replace("\"id\":41", "\"id\":41.0")
        assertFailsWith<BackupValidationException> { codec.decode(decimal.toByteArray()) }
    }

    @Test fun rejectsDuplicateKeysUniqueConstraintsOrphansAndTodoParentCycles() {
        val full = BackupFixtures.fullPayload()
        val todo = full.tables.getValue("todos").single()
        val tag = full.tables.getValue("tags").single()
        val record = full.tables.getValue("habit_records").single()
        val invalid = listOf(
            full.withTable("todos", listOf(todo, todo)),
            full.withTable("tags", listOf(tag, tag.with("id", BackupValue.Integer(99)))),
            full.withTable("todo_tag_cross_ref", full.tables.getValue("todo_tag_cross_ref") + full.tables.getValue("todo_tag_cross_ref")),
            full.withTable("tags", emptyList()),
            full.withTable("habits", emptyList()),
            full.withTable("schedules", emptyList()),
            full.withTable("subscriptions", emptyList()),
            full.withTable("todos", listOf(todo.with("parentId", BackupValue.Integer(41)))),
            full.withTable("todos", listOf(todo.with("parentId", BackupValue.Integer(42)), todo.with("id", BackupValue.Integer(42)).with("parentId", BackupValue.Integer(41)))),
            full.withTable("habit_records", listOf(record, record.with("id", BackupValue.Integer(100)))),
        )
        invalid.forEach { assertFailsWith<BackupValidationException> { encode(it) } }
    }

    @Test fun rejectsInvalidDatesZonesDayListsAndSettingsWithoutFormRewriting() {
        val full = BackupFixtures.fullPayload()
        val schedule = full.tables.getValue("schedules").single()
        for (bad in listOf(
            schedule.with("timeZone", BackupValue.Text("Mars/Nowhere")),
            schedule.with("allDayStartDate", BackupValue.Integer(Long.MAX_VALUE)),
            schedule.with("repeatDaysOfWeek", BackupValue.Text("0,8")),
            schedule.with("repeatDaysOfWeek", BackupValue.Text("x,1")),
        )) assertFailsWith<BackupValidationException> { encode(full.withTable("schedules", listOf(bad))) }
        assertFailsWith<BackupValidationException> { encode(full.copy(settings = full.settings.copy(defaultCurrency = "NOT"))) }
        assertFailsWith<BackupValidationException> { encode(full.copy(settings = full.settings.copy(defaultReminderDays = setOf(2)))) }
    }

    @Test fun enforcesByteAndAggregateRowLimitsWithoutTruncating() {
        val smallCodec = BackupJsonCodec(BackupLimits(maxBytes = 128, maxRows = 100000))
        assertFailsWith<BackupValidationException> { smallCodec.encode(BackupDocument("0.1.0", "2026-10-02T08:00:00Z", empty)) }
        assertFailsWith<BackupValidationException> { smallCodec.decode(ByteArray(129)) }
        val rowCodec = BackupJsonCodec(BackupLimits(maxRows = 9))
        assertFailsWith<BackupValidationException> { rowCodec.encode(BackupDocument("0.1.0", "2026-10-02T08:00:00Z", BackupFixtures.fullPayload())) }
        assertFailsWith<BackupValidationException> { rowCodec.decode(encode(BackupFixtures.fullPayload())) }
    }

    @Test fun permitsCaseDistinctTagsZeroAndNegativeIdsAndDifferentPaymentCurrency() {
        val full = BackupFixtures.fullPayload()
        val tag = full.tables.getValue("tags").single()
        val changed = full.withTable("tags", listOf(tag, tag.with("id", BackupValue.Integer(0)).with("name", BackupValue.Text("Tag")), tag.with("id", BackupValue.Integer(-1)).with("name", BackupValue.Text("tag"))))
        assertEquals(changed, codec.decode(encode(changed)).payload)
    }

    @Test fun rejectsMalformedUnicodeOnExportRatherThanReplacingStoredCharacters() {
        val full = BackupFixtures.fullPayload()
        val row = full.tables.getValue("todos").single().with("title", BackupValue.Text("坏\uD800字符"))
        assertFailsWith<BackupValidationException> { encode(full.withTable("todos", listOf(row))) }
    }

    @Test fun rejectsMalformedStringEscapesAndNonJsonSyntax() {
        val json = encode().decodeToString()
        for (bad in listOf(
            json.replace("CNY", "\\uD800"), json.replace("CNY", "\\x41"),
            json.replace("CNY", "C\nNY"), json.replace("\"formatVersion\":1", "\"formatVersion\":01"),
            json.replace("\"formatVersion\":1", "\"formatVersion\":1e0"), json.dropLast(1) + ",}",
        )) assertFailsWith<BackupValidationException> { codec.decode(bad.toByteArray()) }
    }

    @Test fun rejectsSemanticErrorsEvenIfChecksumIsRecomputed() {
        val root = Json.parseToJsonElement(encode(BackupFixtures.fullPayload()).decodeToString()).jsonObject
        val payload = root.getValue("payload").jsonObject
        fun changedRow(table: String, column: String, value: JsonElement): JsonObject {
            val row = payload.getValue(table).jsonArray.single().jsonObject
            return JsonObject(payload + (table to JsonArray(listOf(JsonObject(row + (column to value))))))
        }
        val badPayloads = listOf(
            JsonObject(payload + ("tags" to JsonArray(emptyList()))),
            JsonObject(payload + ("schedules" to JsonArray(emptyList()))),
            JsonObject(payload + ("habits" to JsonArray(emptyList()))),
            JsonObject(payload + ("subscriptions" to JsonArray(emptyList()))),
            changedRow("todos", "parentId", JsonPrimitive(41)),
            changedRow("todos", "sortOrder", JsonPrimitive(2147483648L)),
            changedRow("todos", "title", JsonNull),
            changedRow("schedules", "timeZone", JsonPrimitive("Mars/Nowhere")),
            changedRow("habit_records", "date", JsonPrimitive(Long.MAX_VALUE)),
            changedRow("subscription_reminders", "daysBefore", JsonPrimitive(2)),
        )
        badPayloads.forEach { assertFailsWith<BackupValidationException> { codec.decode(replacePayload(root, it)) } }
        val settings = payload.getValue("settings").jsonObject
        for (bad in listOf(JsonObject(settings + ("theme" to JsonPrimitive("UNKNOWN"))),
            JsonObject(settings + ("todoReminders" to JsonPrimitive("true"))),
            JsonObject(settings + ("defaultReminderDays" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1))))))) {
            assertFailsWith<BackupValidationException> { codec.decode(replacePayload(root, JsonObject(payload + ("settings" to bad)))) }
        }
    }

    @Test fun acceptsExactlyTheByteAndRowLimitAndRejectsTheNextByte() {
        val bytes = encode(BackupFixtures.fullPayload())
        val exact = BackupJsonCodec(BackupLimits(maxBytes = bytes.size, maxRows = 10))
        assertContentEquals(bytes, exact.encode(BackupDocument("0.1.0", "2026-10-02T08:00:00Z", BackupFixtures.fullPayload())))
        assertEquals(BackupFixtures.fullPayload(), exact.decode(bytes).payload)
        assertFailsWith<BackupValidationException> { exact.decode(bytes + byteArrayOf(32)) }
    }

    @Test fun longAcyclicTodoParentChainDoesNotOverflowTheStack() {
        val full = BackupFixtures.fullPayload()
        val row = full.tables.getValue("todos").single()
        val chain = (1L..10_000L).map { id -> row.with("id", BackupValue.Integer(id)).with("parentId", if (id == 1L) BackupValue.Null else BackupValue.Integer(id - 1)) }
        BackupValidator.validate(full.withTable("todos", chain))
    }

    private fun replacePayload(root: JsonObject, payload: JsonObject): ByteArray =
        JsonObject(root + ("payload" to payload) + ("payloadSha256" to JsonPrimitive(hash(canonical(payload).toString().toByteArray())))).toString().toByteArray()
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
