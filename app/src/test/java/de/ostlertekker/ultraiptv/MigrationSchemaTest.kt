package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.database.Migrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft die handgeschriebene Migration v1→v2 gegen das von Room erzeugte Schema (app/schemas/…/2.json).
 * Verhindert, dass ein Update bestehende Favoriten/Verläufe löscht oder beim Start abstürzt.
 */
class MigrationSchemaTest {
    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    @Test fun migrationMatchesRoomSchema() {
        val file = File("schemas/de.ostlertekker.ultraiptv.core.database.UltraDatabase/2.json")
        assertTrue("Room-Schema fehlt: ${file.absolutePath}", file.exists())
        val entities = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .associateBy { it.jsonObject["tableName"]!!.jsonPrimitive.content }

        val creates = Migrations.SQL_1_2.filter { it.startsWith("CREATE TABLE") }
        assertEquals(4, creates.size)
        for (sql in creates) {
            val table = Regex("`([a-z_]+)`").find(sql)!!.groupValues[1]
            val expected = entities[table]!!.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            assertEquals("CREATE-Statement für $table", norm(expected), norm(sql))
            val indices = entities[table]!!.jsonObject["indices"]?.jsonArray
            assertTrue("Unerwartete Indizes in $table", indices == null || indices.isEmpty())
        }

        val alters = Migrations.SQL_1_2.filter { it.startsWith("ALTER TABLE") }
        for (sql in alters) {
            val (table, column) = Regex("`([a-zA-Z_]+)`").findAll(sql).map { it.groupValues[1] }.toList()
            val field = entities[table]!!.jsonObject["fields"]!!.jsonArray.map { it.jsonObject }
                .first { it["columnName"]!!.jsonPrimitive.content == column }
            assertEquals("Typ von $table.$column", "TEXT", field["affinity"]!!.jsonPrimitive.content)
            assertEquals("$table.$column muss NULL erlauben", false, field["notNull"]!!.jsonPrimitive.boolean)
        }
    }
}
