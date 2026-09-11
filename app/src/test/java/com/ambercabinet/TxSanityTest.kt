package com.ambercabinet

import androidx.room.withTransaction
import com.ambercabinet.core.data.db.BottleEntity
import com.ambercabinet.testdb.TestRoom
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TxSanityTest {
    private fun bottle(id: String) = BottleEntity(id, "gin", "b" + id, "", "spirit", "", 750.0, 300.0, "ml", 43.0, null, 20, null, 1L, 1L, false)

    @Test fun roomWithTransactionRollsBack() = runTest {
        val db = TestRoom.inMemory()
        db.bottleDao().upsert(bottle("a"))
        try {
            db.withTransaction {
                db.bottleDao().upsert(bottle("a").copy(remaining = 240.0))
                db.bottleDao().upsert(bottle("b"))
                throw RuntimeException("boom")
            }
            fail("应当抛出")
        } catch (e: RuntimeException) {}
        assertEquals(300.0, db.bottleDao().getById("a")!!.remaining, 0.001)
        assertNull(db.bottleDao().getById("b"))
    }
}
