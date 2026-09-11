package com.ambercabinet

import androidx.room.withTransaction
import com.ambercabinet.core.data.db.*
import com.ambercabinet.core.data.repo.*
import com.ambercabinet.core.data.seed.SeedCatalog
import com.ambercabinet.core.domain.*
import com.ambercabinet.testdb.JdbcSQLiteOpenHelper
import com.ambercabinet.testdb.TestRoom
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 真实 Room + SQLite（sqlite-jdbc）的 JVM 数据库测试（§八、§15.2）：
 * 事务原子性、幂等提交/撤销、迁移保留数据、全量恢复语义、草稿持久化。
 */
class RoomDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CabinetRepository
    private lateinit var service: InventoryService
    private lateinit var ingredients: Map<String, com.ambercabinet.core.model.Ingredient>
    private lateinit var martini: com.ambercabinet.core.model.Recipe

    @Before
    fun setup() {
        db = TestRoom.inMemory()
        val dir = File("src/main/assets/seed")
        val catalog = SeedCatalog.parse(
            File(dir, "ingredients.json").readText(),
            File(dir, "substitutions.json").readText(),
            File(dir, "brands.json").readText(),
            File(dir, "recipes.json").readText()
        )
        ingredients = catalog.ingredients
        martini = catalog.recipes.first { it.id == "dry-martini" }
        repo = CabinetRepository(db, catalog)
        val engine = MatchEngine(repo, catalog.substitutions)
        service = InventoryService(repo, repo, engine)
    }

    private suspend fun seedBottles() {
        repo.addBottle(com.ambercabinet.core.model.Bottle(id = "gin-a", ingredientId = "gin", brand = "添加利", initQty = 750.0, remaining = 300.0, unit = "ml", openedAt = 1L), "手动新增")
        repo.addBottle(com.ambercabinet.core.model.Bottle(id = "gin-b", ingredientId = "gin", brand = "必富达", initQty = 700.0, remaining = 700.0, unit = "ml"), "手动新增")
        repo.addBottle(com.ambercabinet.core.model.Bottle(id = "vermouth", ingredientId = "dry_vermouth", brand = "马天尼干", initQty = 1000.0, remaining = 500.0, unit = "ml", openedAt = 1L), "手动新增")
    }

    private suspend fun ginRemaining(id: String) = repo.getBottle(id)!!.remaining

    /** 扣减中途抛异常：真实数据库事务完整回滚，库存/流水/记录均不产生 */
    @Test fun midCommitExceptionRollsBackEverything() = runTest {
        seedBottles()
        /* 包装 writer：第 2 次扣减（第二种材料）时模拟磁盘异常 */
        val fault = object : InventoryWriter by repo {
            var count = 0
            override suspend fun deduct(bottleId: String, delta: Double): Double {
                count++
                if (count >= 2) throw RuntimeException("模拟磁盘写入失败")
                return repo.deduct(bottleId, delta)
            }
        }
        val svc = InventoryService(repo, fault, MatchEngine(repo, emptyList()))
        val draft = repo.createDraft(martini.id, 1, emptyMap(), emptyMap())
        try {
            db.withTransaction {
                svc.commitMix(draft.sessionId, martini, 1, ingredients, emptyMap(), emptyMap())
            }
            fail("应当抛出异常")
        } catch (e: RuntimeException) {
            assertEquals("模拟磁盘写入失败", e.message)
        }
        /* 完整回滚 */
        assertEquals(300.0, ginRemaining("gin-a"), 0.001)
        assertEquals(700.0, ginRemaining("gin-b"), 0.001)
        assertEquals(500.0, repo.getBottle("vermouth")!!.remaining, 0.001)
        assertEquals(3, repo.txnCount())          /* 只有 3 条入库流水，扣减流水已回滚 */
        assertNull(db.sessionDao().getById(draft.sessionId))
    }

    private suspend fun CabinetRepository.txnCount(): Int = db.txnDao().getAll().size

    /** 连续提交同一草稿：真实库只扣减一次 */
    @Test fun doubleCommitRealDbDeductsOnce() = runTest {
        seedBottles()
        val draft = repo.createDraft(martini.id, 1, emptyMap(), emptyMap())
        val r1 = repo.commitMixAtomic(service, draft, martini, 1, ingredients, null)
        val r2 = repo.commitMixAtomic(service, draft, martini, 1, ingredients, null)
        assertTrue(r1 is CommitResult.Success)
        assertTrue(r2 is CommitResult.AlreadyCommitted)
        assertEquals(240.0, ginRemaining("gin-a"), 0.001)
        assertNull(repo.getDraft(draft.id))   /* 草稿已删除 */
        assertEquals(1, db.sessionDao().getAll().size)
    }

    /** 连续撤销：真实库只恢复一次 */
    @Test fun doubleUndoRealDbRestoresOnce() = runTest {
        seedBottles()
        val draft = repo.createDraft(martini.id, 1, emptyMap(), emptyMap())
        repo.commitMixAtomic(service, draft, martini, 1, ingredients, null)
        val sessionId = draft.sessionId
        val u1 = repo.undoSessionAtomic(service, sessionId)
        val u2 = repo.undoSessionAtomic(service, sessionId)
        assertTrue(u1 is UndoResult.Done)
        assertTrue(u2 is UndoResult.AlreadyUndone)
        assertEquals(300.0, ginRemaining("gin-a"), 0.001)
        assertTrue(db.sessionDao().getById(sessionId)!!.undone)
    }

    /** 草稿跨「进程重建」保留：关闭数据库重新打开后恢复步骤与计时 */
    @Test fun draftSurvivesReopen() = runTest {
        val f = File.createTempFile("amber-test", ".db")
        f.deleteOnExit()
        val db1 = TestRoom.file(f)
        val catalogRepoDb1 = db1
        val draft = DraftEntity("d1", "s1", "dry-martini", 2, 3, "{}", "{}", System.currentTimeMillis() + 60000, 45, true, 100L, 200L)
        catalogRepoDb1.draftDao().upsert(draft)
        catalogRepoDb1.close()

        val db2 = TestRoom.file(f)
        val loaded = db2.draftDao().getById("d1")
        assertNotNull(loaded)
        assertEquals(3, loaded!!.currentStep)
        assertEquals(2, loaded.servings)
        assertTrue(loaded.timerRunning)
        assertEquals(45, loaded.timerRemainingSec)
        db2.close()
    }

    /** 迁移 1→2 保留全部用户数据，并新增草稿/自定义材料表 */
    @Test fun migration12PreservesData() = runTest {
        val f = File.createTempFile("amber-mig", ".db")
        f.deleteOnExit()
        TestRoom.createV1File(f,
            "INSERT INTO bottles (id, ingredientId, brand, label, shape, liquid, initQty, remaining, unit, abv, openedAt, lowPct, photoUri, createdAt, updatedAt, deleted) VALUES ('b1', 'gin', '添加利', '', 'spirit', '', 750, 320.5, 'ml', 43, NULL, 20, NULL, 1, 1, 0)",
            "INSERT INTO mix_sessions (id, recipeId, servings, status, undone, currentStep, chosenSubsJson, overridesJson, startedAt, finishedAt) VALUES ('s1', 'dry-martini', 2, 'done', 0, 0, '{\"gin\":true}', '{}', 1, 2)",
            "INSERT INTO favorites (recipeId, time) VALUES ('negroni', 99)"
        )
        val migrated = TestRoom.file(f)   /* Room 打开时执行 MIGRATION_1_2 */
        val b = migrated.bottleDao().getById("b1")
        assertNotNull(b)
        assertEquals(320.5, b!!.remaining, 0.001)
        val s = migrated.sessionDao().getById("s1")
        assertNotNull(s)
        assertEquals("", s!!.recipeZh)          /* 旧记录快照列默认为空 */
        assertEquals("{\"gin\":true}", s.chosenSubsJson)  /* 历史数据原样保留 */
        assertEquals(1, migrated.favoriteDao().getAll().size)
        /* 新表可用 */
        assertEquals(0, migrated.draftDao().getAll().size)
        assertEquals(0, migrated.customIngredientDao().getAll().size)
        migrated.close()
    }

    /** 旧备份全量恢复：不残留与备份库存矛盾的较新流水（§三.1/三.2） */
    @Test fun fullRestoreRemovesNewerTxns() = runTest {
        seedBottles()
        /* 备份点：当前 3 瓶 + 3 条入库流水 */
        val bundle = BackupCodec.Bundle(
            bottles = db.bottleDao().getAll(),
            txns = db.txnDao().getAll(),
            sessions = emptyList(), notes = emptyList(), favorites = emptyList(),
            customRecipes = emptyList(), customIngredients = emptyList(), kv = emptyList(),
            exportedAt = 12345L
        )
        val bytes = BackupCodec.encode(bundle)
        /* 之后发生新调制：库存减少 + 新流水 */
        val draft = repo.createDraft(martini.id, 1, emptyMap(), emptyMap())
        repo.commitMixAtomic(service, draft, martini, 1, ingredients, null)
        assertTrue(db.txnDao().getAll().size > bundle.txns.size)
        assertEquals(240.0, ginRemaining("gin-a"), 0.001)

        /* 恢复旧备份 → 回到备份时刻，较新流水不存在 */
        val decoded = BackupCodec.decode(bytes)
        BackupRestore.apply(db, decoded)
        assertEquals(300.0, ginRemaining("gin-a"), 0.001)
        assertEquals(bundle.txns.size, db.txnDao().getAll().size)
        assertEquals(0, db.sessionDao().getAll().size)
        assertEquals(bundle.exportedAt, decoded.exportedAt)
    }

    /** 损坏/不兼容备份：校验即失败，现有数据不变（§三.6） */
    @Test fun corruptBackupLeavesDataIntact() = runTest {
        seedBottles()
        val before = db.bottleDao().getAll().map { it.id to it.remaining }
        val good = BackupCodec.encode(BackupCodec.Bundle(bottles = db.bottleDao().getAll(), txns = db.txnDao().getAll()))
        val corrupt = good.copyOf().also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
        try { BackupCodec.decode(corrupt) } catch (e: Exception) { /* 期望失败 */ }
        val after = db.bottleDao().getAll().map { it.id to it.remaining }
        assertEquals(before, after)
    }

    /** 自定义材料 + 私人配方 + 品鉴记录：保存、重开、备份恢复一致（§八） */
    @Test fun customIngredientRecipeNoteRoundTrip() = runTest {
        seedBottles()
        val catalog = SeedCatalog.parse(
            File("src/main/assets/seed/ingredients.json").readText(),
            File("src/main/assets/seed/substitutions.json").readText(),
            File("src/main/assets/seed/brands.json").readText(),
            File("src/main/assets/seed/recipes.json").readText()
        )
        val catalogRepo = CatalogRepository(catalog, db)
        val records = RecordsRepository(db)
        val customIng = com.ambercabinet.core.model.Ingredient(
            id = "custom_yuzu", zh = "柚子汁", en = "Yuzu Juice", category = "mix",
            dimension = com.ambercabinet.core.model.UnitDimension.VOLUME, unit = "ml", isCustom = true
        )
        catalogRepo.saveCustomIngredient(customIng)

        val draft = repo.createDraft(martini.id, 1, emptyMap(), emptyMap())
        val r = repo.commitMixAtomic(service, draft, martini, 1, ingredients, null)
        val session = (r as CommitResult.Success).session
        records.saveNote(com.ambercabinet.core.model.TastingNote(sessionId = session.id, recipeId = martini.id, rating = 4.5, sweet = 1, sour = 3, bitter = 2, body = 4, text = "很干，喜欢"))
        val privateRecipe = martini.copy(id = "my-martini", zh = "我的马丁尼", isUser = true)
        records.saveCustomRecipe(privateRecipe, baseRecipeId = martini.id)

        /* 备份 → 清空 → 恢复 */
        val bundle = BackupCodec.Bundle(
            bottles = db.bottleDao().getAll(), txns = db.txnDao().getAll(),
            sessions = db.sessionDao().getAll(), notes = db.noteDao().getAll(),
            favorites = db.favoriteDao().getAll(), customRecipes = db.customRecipeDao().getAll(),
            customIngredients = db.customIngredientDao().getAll(), kv = db.kvDao().getAll()
        )
        val bytes = BackupCodec.encode(bundle)
        val decoded = BackupCodec.decode(bytes)
        assertEquals(1, decoded.notes.size)
        assertEquals(1, decoded.customRecipes.size)
        assertEquals(1, decoded.customIngredients.size)
        BackupRestore.apply(db, decoded)

        assertNotNull(db.noteDao().forSession(session.id))
        assertEquals(4.5, db.noteDao().forSession(session.id)!!.rating, 0.001)
        assertEquals("我的马丁尼", records.customRecipeById("my-martini")!!.zh)
        assertEquals(1, db.customIngredientDao().getAll().size)
    }
}
