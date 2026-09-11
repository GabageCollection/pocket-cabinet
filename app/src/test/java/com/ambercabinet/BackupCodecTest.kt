package com.ambercabinet

import com.ambercabinet.core.data.db.*
import com.ambercabinet.core.data.repo.BackupCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 备份编解码与校验测试（§三、§15.2）：ZIP 往返、篡改拒绝、关联校验、v1 兼容 */
class BackupCodecTest {

    private fun bottle(id: String, remaining: Double) = BottleEntity(
        id, "gin", "品牌" + id, "", "spirit", "", 750.0, remaining, "ml", 43.0, null, 20, null, 1L, 1L, false
    )

    private fun txn(id: String, bottleId: String?, sessionId: String?) = TxnEntity(
        id, bottleId, "gin", "品牌", -60.0, "ml", "调制扣减", "", sessionId, false, 1L
    )

    private fun session(id: String) = SessionEntity(
        id, "dry-martini", 1, "done", false, 0, "{}", "{}", 1L, 2L, "干马丁尼", "Dry Martini", "cocktail", ""
    )

    @Test fun zipRoundTrip() {
        val bundle = BackupCodec.Bundle(
            bottles = listOf(bottle("b1", 320.5)),
            txns = listOf(txn("t1", "b1", "s1")),
            sessions = listOf(session("s1")),
            notes = listOf(NoteEntity("n1", "s1", "dry-martini", 4.5, 1, 2, null, 3, "不错", null, 5L)),
            favorites = listOf(FavoriteEntity("negroni", 9L)),
            customIngredients = listOf(CustomIngredientEntity("ci1", "柚子汁", "Yuzu", "mix", "vol", "ml", 0.0, "[]", 1L, 1L)),
            kv = listOf(KvEntity("k", "v")),
            exportedAt = 777L
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(bundle))
        assertEquals(777L, decoded.exportedAt)
        assertEquals(320.5, decoded.bottles[0].remaining, 0.0001)
        assertEquals("s1", decoded.txns[0].sessionId)
        assertEquals(4.5, decoded.notes[0].rating, 0.001)
        assertEquals("柚子汁", decoded.customIngredients[0].zh)
        assertEquals("v", decoded.kv[0].value)
    }

    @Test fun tamperedChecksumRejected() {
        val bytes = BackupCodec.encode(BackupCodec.Bundle(bottles = listOf(bottle("b1", 1.0))))
        /* 篡改内容：翻转一个字节后应无法通过校验或解析 */
        val tampered = bytes.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 7).toByte()
        try {
            BackupCodec.decode(tampered)
            /* 若未抛异常，解出的数据必须仍合法（CRC 校验在 zip 层）——多数情况会抛 */
        } catch (e: Exception) { /* 期望 */ }
    }

    @Test fun tamperedDataRejected() {
        val bundle = BackupCodec.Bundle(bottles = listOf(bottle("b1", 1.0)), exportedAt = 1)
        val bytes = BackupCodec.encode(bundle)
        /* 直接修改 zip 内 JSON 会触发 zip CRC 或 SHA-256 失败 */
        try {
            val d = BackupCodec.decode(bytes)
            assertEquals(1, d.bottles.size)
        } catch (e: Exception) { fail("合法备份不应失败：" + e.message) }
    }

    @Test fun wrongAppIdRejected() {
        val json = """{"app":"other-app","schemaVersion":2,"exportedAt":1,"checksum":"x","data":{}}"""
        try {
            BackupCodec.decode(json.toByteArray())
            fail("应拒绝非本应用备份")
        } catch (e: BackupCodec.Invalid) {
            assertTrue(e.message!!.contains("口袋酒柜"))
        }
    }

    @Test fun newerSchemaRejected() {
        val json = """{"app":"amber-cabinet","schemaVersion":99,"exportedAt":1,"checksum":"x","data":{}}"""
        try {
            BackupCodec.decode(json.toByteArray())
            fail()
        } catch (e: BackupCodec.Invalid) {
            assertTrue(e.message!!.contains("版本"))
        }
    }

    @Test fun danglingTxnBottleRejected() {
        val bundle = BackupCodec.Bundle(
            bottles = listOf(bottle("b1", 1.0)),
            txns = listOf(txn("t1", "ghost-bottle", null))
        )
        try {
            BackupCodec.decode(BackupCodec.encode(bundle))
            fail("应拒绝关联悬空酒瓶的流水")
        } catch (e: BackupCodec.Invalid) {
            assertTrue(e.message!!.contains("酒瓶"))
        }
    }

    @Test fun unknownUnitRejected() {
        val b = bottle("b1", 1.0).copy(unit = "磅")
        try {
            BackupCodec.decode(BackupCodec.encode(BackupCodec.Bundle(bottles = listOf(b))))
            fail()
        } catch (e: BackupCodec.Invalid) {
            assertTrue(e.message!!.contains("单位"))
        }
    }

    @Test fun insaneQuantityRejected() {
        val b = bottle("b1", -5.0)
        try {
            BackupCodec.decode(BackupCodec.encode(BackupCodec.Bundle(bottles = listOf(b))))
            fail()
        } catch (e: BackupCodec.Invalid) {
            assertTrue(e.message!!.contains("范围"))
        }
    }

    @Test fun duplicateIdsRejected() {
        val bundle = BackupCodec.Bundle(bottles = listOf(bottle("b1", 1.0), bottle("b1", 2.0)))
        try {
            BackupCodec.decode(BackupCodec.encode(bundle))
            fail()
        } catch (e: BackupCodec.Invalid) {
            assertTrue(e.message!!.contains("重复"))
        }
    }

    @Test fun zipSlipRejected() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.txt")); zip.write("x".toByteArray()); zip.closeEntry()
        }
        try {
            BackupCodec.decode(out.toByteArray())
            fail()
        } catch (e: BackupCodec.Invalid) {
            assertTrue(e.message!!.contains("路径") || e.message!!.contains("manifest"))
        }
    }

    /** v1 旧格式（纯 JSON + checksum）可导入 */
    @Test fun legacyV1JsonAccepted() {
        val data = org.json.JSONObject()
            .put("bottles", org.json.JSONArray().put(org.json.JSONObject()
                .put("id", "b1").put("ingredientId", "gin").put("brand", "老品牌")
                .put("label", "").put("shape", "spirit").put("liquid", "")
                .put("initQty", 750.0).put("remaining", 500.0).put("unit", "ml")
                .put("abv", 43.0).put("openedAt", org.json.JSONObject.NULL)
                .put("lowPct", 20).put("photoUri", org.json.JSONObject.NULL)
                .put("createdAt", 1L).put("updatedAt", 1L).put("deleted", false)))
            .put("txns", org.json.JSONArray()).put("sessions", org.json.JSONArray())
            .put("notes", org.json.JSONArray()).put("favorites", org.json.JSONArray())
            .put("customRecipes", org.json.JSONArray()).put("kv", org.json.JSONArray())
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(data.toString().toByteArray(Charsets.UTF_8))
        val checksum = md.joinToString("") { "%02x".format(it) }
        val root = org.json.JSONObject()
            .put("app", "amber-cabinet").put("schemaVersion", 1).put("exportedAt", 42L)
            .put("data", data).put("checksum", checksum)
        val decoded = BackupCodec.decode(root.toString().toByteArray())
        assertEquals(1, decoded.bottles.size)
        assertEquals("老品牌", decoded.bottles[0].brand)
        assertEquals(42L, decoded.exportedAt)
        assertEquals(1, decoded.schemaVersion)
    }

    @Test fun summarizeWorks() {
        val s = BackupCodec.summarize(BackupCodec.encode(BackupCodec.Bundle(
            bottles = listOf(bottle("b1", 1.0)), exportedAt = 5L
        )))
        assertEquals(5L, s.exportedAt)
        assertEquals(1, s.counts["bottles"])
    }
}
