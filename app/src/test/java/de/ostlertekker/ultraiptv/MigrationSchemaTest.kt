package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.database.Migrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft die handgeschriebenen Migrationen v1→v2→v3 gegen das von Room erzeugte Schema (app/schemas/…/4.json).
 * Verhindert, dass ein Update bestehende Favoriten/Verläufe löscht oder beim Start abstürzt.
 */
class MigrationSchemaTest {
    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private val entities: Map<String, JsonObject> by lazy {
        val file = File("schemas/de.ostlertekker.ultraiptv.core.database.UltraDatabase/4.json")
        assertTrue("Room-Schema fehlt: ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject }
    }

    private fun checkCreates(sqls: List<String>) {
        for (sql in sqls.filter { it.startsWith("CREATE TABLE") }) {
            val table = Regex("`([a-z_]+)`").find(sql)!!.groupValues[1]
            val expected = entities[table]!!["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            assertEquals("CREATE-Statement für $table", norm(expected), norm(sql))
            val indices = entities[table]!!["indices"]?.jsonArray.orEmpty()
            for (idx in indices) {
                val create = idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                assertTrue("Index fehlt in Migration: $create", sqls.any { norm(it) == norm(create) })
            }
        }
    }

    private fun field(table: String, column: String) = entities[table]!!["fields"]!!.jsonArray.map { it.jsonObject }
        .first { it["columnName"]!!.jsonPrimitive.content == column }

    @Test fun migration1to2MatchesRoomSchema() {
        assertEquals(4, Migrations.SQL_1_2.count { it.startsWith("CREATE TABLE") })
        checkCreates(Migrations.SQL_1_2)
        for (sql in Migrations.SQL_1_2.filter { it.startsWith("ALTER TABLE") }) {
            val (table, column) = Regex("`([a-zA-Z_]+)`").findAll(sql).map { it.groupValues[1] }.toList()
            val f = field(table, column)
            assertEquals("Typ von $table.$column", "TEXT", f["affinity"]!!.jsonPrimitive.content)
            assertEquals("$table.$column muss NULL erlauben", false, f["notNull"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun sanitizeStatementsReferenceRealColumns() {
        for (sql in Migrations.SANITIZE) {
            val m = Regex("""UPDATE (\w+) SET (\w+) = .* WHERE length\((\w+)\)""").find(sql)!!
            val (table, col, col2) = m.destructured
            assertEquals(sql, col, col2)
            field(table, col) // wirft, wenn Tabelle/Spalte nicht existiert
        }
    }

    @Test fun migration2to3MatchesRoomSchema() {
        assertEquals(2, Migrations.SQL_2_3.count { it.startsWith("CREATE TABLE") })
        checkCreates(Migrations.SQL_2_3)
        val archive = field("channels", "archiveDays")
        assertEquals("INTEGER", archive["affinity"]!!.jsonPrimitive.content)
        assertEquals(true, archive["notNull"]!!.jsonPrimitive.boolean)
        assertEquals("0", archive["defaultValue"]!!.jsonPrimitive.content)
        for (c in listOf("catchupMode", "catchupSource")) assertEquals(false, field("channels", c)["notNull"]!!.jsonPrimitive.boolean)
    }

    @Test fun migration3to4CreatesRoomIndices() {
        val executed = ArrayList<String>()
        val recorder = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, m, args ->
            if (m.name == "execSQL") executed += args!![0] as String; null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        Migrations.MIGRATION_3_4.migrate(recorder)
        for (table in listOf("movies", "series")) {
            for (idx in entities[table]!!["indices"]!!.jsonArray) {
                val create = idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                val name = idx.jsonObject["name"]!!.jsonPrimitive.content
                if (name.endsWith("_rating") || name == "index_series_addedAt")
                    assertTrue("Index fehlt in Migration 3→4: $create", executed.any { norm(it) == norm(create) })
            }
        }
    }
}
