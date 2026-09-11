package com.ambercabinet.testdb

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.locks.ReentrantLock

/**
 * JVM 单元测试用的 SupportSQLiteOpenHelper：以 sqlite-jdbc 驱动真实 SQLite，
 * 让 Room 在本地跑真实事务（提交/回滚），不依赖 Android 设备或模拟器。
 *
 * 事务由一把可重入锁保护：Room 的 withTransaction 在同一线程执行 begin/语句/end，
 * 其他线程的读写被阻塞，避免两个事务语句交错。
 */
class JdbcSQLiteOpenHelper(
    private val jdbcUrl: String,
    private val callback: SupportSQLiteOpenHelper.Callback
) : SupportSQLiteOpenHelper {

    class Factory(private val file: java.io.File?) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val url = if (file == null) "jdbc:sqlite::memory:" else "jdbc:sqlite:" + file.absolutePath
            return JdbcSQLiteOpenHelper(url, configuration.callback)
        }
    }

    private inner class Db : SupportSQLiteDatabase {
        val conn: Connection = DriverManager.getConnection(jdbcUrl)
        private val lock = ReentrantLock(true)
        private var open = true

        private inline fun <T> guarded(block: () -> T): T {
            lock.lock()
            try { return block() } finally { lock.unlock() }
        }

        /* ── 事务（每层一个成功标志：对齐 Android 嵌套事务语义：内层成功不污染外层；内层失败则外层必回滚）── */
        private val txStack = ArrayDeque<Boolean>()
        override fun beginTransaction() = guarded {
            if (txStack.isEmpty()) conn.autoCommit = false
            txStack.addLast(false)
            Unit
        }
        override fun beginTransactionNonExclusive() = beginTransaction()
        override fun beginTransactionWithListener(l: android.database.sqlite.SQLiteTransactionListener) = beginTransaction()
        override fun beginTransactionWithListenerNonExclusive(l: android.database.sqlite.SQLiteTransactionListener) = beginTransaction()
        override fun setTransactionSuccessful() = guarded {
            if (txStack.isNotEmpty()) txStack[txStack.size - 1] = true
        }
        override fun endTransaction() {
            lock.lock()
            try {
                val success = txStack.removeLast()
                if (txStack.isEmpty()) {
                    if (success) conn.commit() else conn.rollback()
                    conn.autoCommit = true
                } else if (!success) {
                    txStack[txStack.size - 1] = false
                }
            } finally { lock.unlock() }
        }
        override fun inTransaction(): Boolean = guarded { txStack.isNotEmpty() }

        /* ── 版本 ── */
        override var version: Int
            get() = guarded {
                conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }
            set(value) = guarded { conn.createStatement().use { it.execute("PRAGMA user_version = " + value); Unit } }

        /* ── 语句 ── */
        override fun execSQL(sql: String) = guarded { conn.createStatement().use { it.execute(sql) }; Unit }
        override fun execSQL(sql: String, bindArgs: Array<out Any?>) = guarded {
            conn.prepareStatement(sql).use { ps -> bindAll(ps, bindArgs); ps.execute() }
            Unit
        }

        override fun query(sql: String): Cursor = guarded { materialize(conn.prepareStatement(sql)) }
        override fun query(sql: String, bindArgs: Array<out Any?>): Cursor = guarded {
            val ps = conn.prepareStatement(sql); bindAll(ps, bindArgs); materialize(ps)
        }
        override fun query(query: SupportSQLiteQuery): Cursor = guarded {
            val ps = conn.prepareStatement(query.sql)
            query.bindTo(JdbcProgram(ps))
            materialize(ps)
        }
        override fun query(query: SupportSQLiteQuery, signal: android.os.CancellationSignal?): Cursor = this.query(query)

        private fun materialize(ps: PreparedStatement): Cursor {
            val rs = ps.executeQuery()
            val md = rs.metaData
            val names = (1..md.columnCount).map { md.getColumnName(it) }
            val rows = mutableListOf<Array<Any?>>()
            while (rs.next()) {
                rows.add(Array(names.size) { i -> rs.getObject(i + 1) })
            }
            rs.close(); ps.close()
            return JdbcCursor(names, rows)
        }

        override fun compileStatement(sql: String): SupportSQLiteStatement = JdbcStatement(conn.prepareStatement(sql))

        /* ── ContentValues 便捷方法（Room 生成代码不经过这里，未实现）── */
        override fun insert(table: String, conflictAlgorithm: Int, values: android.content.ContentValues): Long =
            throw UnsupportedOperationException("insert(ContentValues) 未在测试支撑中实现")
        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int =
            throw UnsupportedOperationException()
        override fun update(table: String, conflictAlgorithm: Int, values: android.content.ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int =
            throw UnsupportedOperationException()

        /* ── 元信息（Kotlin 属性形式）── */
        override val path: String get() = jdbcUrl
        override val isOpen: Boolean get() = open
        override val isReadOnly: Boolean get() = false
        override val isDbLockedByCurrentThread: Boolean get() = lock.isHeldByCurrentThread
        override val attachedDbs: List<android.util.Pair<String, String>> get() = emptyList()
        override val isDatabaseIntegrityOk: Boolean get() = true
        override val maximumSize: Long get() = Long.MAX_VALUE
        override var pageSize: Long
            get() = 4096
            set(value) {}
        override val isWriteAheadLoggingEnabled: Boolean get() = false

        override fun setMaximumSize(numBytes: Long): Long = numBytes
        override fun needUpgrade(newVersion: Int): Boolean = version < newVersion
        override fun setLocale(locale: java.util.Locale) {}
        override fun setMaxSqlCacheSize(cacheSize: Int) {}
        override fun setForeignKeyConstraintsEnabled(enable: Boolean) {}
        override fun enableWriteAheadLogging(): Boolean = false
        override fun disableWriteAheadLogging() {}
        override fun yieldIfContendedSafely(): Boolean = false
        override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean = false
        override fun close() { guarded { if (open) { conn.close(); open = false } } }
    }

    private var db: Db? = null

    override val databaseName: String? @Synchronized get() = jdbcUrl

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {}

    override val writableDatabase: SupportSQLiteDatabase
        @Synchronized get() {
            db?.let { if (it.isOpen) return it }
            val d = Db()
            /* 驱动 RoomOpenHelper 生命周期：onConfigure → onCreate/onUpgrade → onOpen */
            callback.onConfigure(d)
            val v = d.version
            val target = callback.version
            when {
                v == 0 -> { callback.onCreate(d); d.version = target }
                v < target -> { callback.onUpgrade(d, v, target); d.version = target }
                v > target -> callback.onDowngrade(d, v, target)
            }
            callback.onOpen(d)
            db = d
            return d
        }

    override val readableDatabase: SupportSQLiteDatabase
        @Synchronized get() = writableDatabase

    @Synchronized
    override fun close() { db?.close(); db = null }

    companion object {
        fun bindAll(ps: PreparedStatement, args: Array<out Any?>) {
            args.forEachIndexed { i, v -> bindOne(ps, i + 1, v) }
        }
        fun bindOne(ps: PreparedStatement, index: Int, v: Any?) {
            when (v) {
                null -> ps.setObject(index, null)
                is Double -> ps.setDouble(index, v)
                is Float -> ps.setDouble(index, v.toDouble())
                is Long -> ps.setLong(index, v)
                is Int -> ps.setInt(index, v)
                is Boolean -> ps.setInt(index, if (v) 1 else 0)
                is String -> ps.setString(index, v)
                is ByteArray -> ps.setBytes(index, v)
                else -> ps.setObject(index, v)
            }
        }
    }
}

/** 绑定容器（RoomSQLiteQuery.bindTo） */
class JdbcProgram(private val ps: PreparedStatement) : SupportSQLiteProgram {
    override fun bindNull(index: Int) = JdbcSQLiteOpenHelper.bindOne(ps, index, null)
    override fun bindLong(index: Int, value: Long) = JdbcSQLiteOpenHelper.bindOne(ps, index, value)
    override fun bindDouble(index: Int, value: Double) = JdbcSQLiteOpenHelper.bindOne(ps, index, value)
    override fun bindString(index: Int, value: String) = JdbcSQLiteOpenHelper.bindOne(ps, index, value)
    override fun bindBlob(index: Int, value: ByteArray) = JdbcSQLiteOpenHelper.bindOne(ps, index, value)
    override fun clearBindings() {}
    override fun close() {}
}

class JdbcStatement(private val ps: PreparedStatement) : SupportSQLiteStatement {
    override fun execute() { ps.execute() }
    override fun executeInsert(): Long {
        ps.executeUpdate()
        /* sqlite-jdbc 不支持 getGeneratedKeys，用同一连接的 last_insert_rowid() */
        ps.connection.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                return if (rs.next()) rs.getLong(1) else -1L
            }
        }
    }
    override fun executeUpdateDelete(): Int = ps.executeUpdate()
    override fun simpleQueryForLong(): Long { val rs = ps.executeQuery(); return if (rs.next()) rs.getLong(1) else 0L }
    override fun simpleQueryForString(): String? { val rs = ps.executeQuery(); return if (rs.next()) rs.getString(1) else null }
    override fun bindNull(index: Int) = JdbcSQLiteOpenHelper.bindOne(ps, index, null)
    override fun bindLong(index: Int, value: Long) = JdbcSQLiteOpenHelper.bindOne(ps, index, value)
    override fun bindDouble(index: Int, value: Double) = JdbcSQLiteOpenHelper.bindOne(ps, index, value)
    override fun bindString(index: Int, value: String) = JdbcSQLiteOpenHelper.bindOne(ps, index, value)
    override fun bindBlob(index: Int, value: ByteArray) = JdbcSQLiteOpenHelper.bindOne(ps, index, value)
    override fun clearBindings() { ps.clearParameters() }
    override fun close() { ps.close() }
}

/** 物化 ResultSet 的内存 Cursor（实现 Room 实际使用的全部方法） */
class JdbcCursor(private val columns: List<String>, private val rows: List<Array<Any?>>) : Cursor {
    private var pos = -1
    private var closed = false

    override fun getCount(): Int = rows.size
    override fun getPosition(): Int = pos
    override fun moveToPosition(position: Int): Boolean {
        return if (position in -1..rows.size) { pos = position; position in rows.indices } else false
    }
    override fun moveToFirst(): Boolean = moveToPosition(0)
    override fun moveToLast(): Boolean = moveToPosition(rows.size - 1)
    override fun moveToNext(): Boolean = moveToPosition(pos + 1)
    override fun moveToPrevious(): Boolean = moveToPosition(pos - 1)
    override fun move(offset: Int): Boolean = moveToPosition(pos + offset)
    override fun isFirst(): Boolean = pos == 0 && rows.isNotEmpty()
    override fun isLast(): Boolean = pos == rows.size - 1 && rows.isNotEmpty()
    override fun isBeforeFirst(): Boolean = pos < 0
    override fun isAfterLast(): Boolean = pos >= rows.size
    override fun getColumnIndex(columnName: String?): Int = columns.indexOf(columnName)
    override fun getColumnIndexOrThrow(columnName: String?): Int {
        val i = getColumnIndex(columnName)
        if (i < 0) throw IllegalArgumentException("column '$columnName' does not exist")
        return i
    }
    override fun getColumnName(columnIndex: Int): String = columns[columnIndex]
    override fun getColumnNames(): Array<String> = columns.toTypedArray()
    override fun getColumnCount(): Int = columns.size

    private fun v(i: Int): Any? = rows[pos][i]
    override fun getString(i: Int): String? = v(i)?.toString()
    override fun getLong(i: Int): Long = (v(i) as? Number)?.toLong() ?: 0L
    override fun getInt(i: Int): Int = (v(i) as? Number)?.toInt() ?: 0
    override fun getShort(i: Int): Short = (v(i) as? Number)?.toShort() ?: 0
    override fun getFloat(i: Int): Float = (v(i) as? Number)?.toFloat() ?: 0f
    override fun getDouble(i: Int): Double = (v(i) as? Number)?.toDouble() ?: 0.0
    override fun getBlob(i: Int): ByteArray = v(i) as? ByteArray ?: ByteArray(0)
    override fun isNull(i: Int): Boolean = v(i) == null
    override fun getType(i: Int): Int = when (val x = v(i)) {
        null -> Cursor.FIELD_TYPE_NULL
        is Int, is Long, is Short -> Cursor.FIELD_TYPE_INTEGER
        is Double, is Float -> Cursor.FIELD_TYPE_FLOAT
        is ByteArray -> Cursor.FIELD_TYPE_BLOB
        else -> Cursor.FIELD_TYPE_STRING
    }

    override fun close() { closed = true }
    override fun isClosed(): Boolean = closed
    override fun copyStringToBuffer(columnIndex: Int, buffer: android.database.CharArrayBuffer?) {}
    override fun deactivate() {}
    override fun requery(): Boolean = false
    override fun registerContentObserver(observer: android.database.ContentObserver?) {}
    override fun unregisterContentObserver(observer: android.database.ContentObserver?) {}
    override fun registerDataSetObserver(observer: android.database.DataSetObserver?) {}
    override fun unregisterDataSetObserver(observer: android.database.DataSetObserver?) {}
    override fun setNotificationUri(cr: android.content.ContentResolver?, uri: android.net.Uri?) {}
    override fun getNotificationUri(): android.net.Uri? = null
    override fun getWantsAllOnMoveCalls(): Boolean = false
    override fun setExtras(extras: android.os.Bundle?) {}
    override fun getExtras(): android.os.Bundle? = null
    override fun respond(extras: android.os.Bundle?): android.os.Bundle? = null
}
