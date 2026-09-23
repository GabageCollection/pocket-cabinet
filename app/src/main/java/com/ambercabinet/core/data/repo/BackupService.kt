package com.ambercabinet.core.data.repo

import android.content.Context
import android.net.Uri
import com.ambercabinet.core.data.db.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份 / 恢复（§12.1，修订为「完整恢复到备份状态」）：
 * - 导出：带版本号与 SHA-256 校验的 ZIP（含 JSON 数据与应用私有目录中的照片文件）
 * - 恢复：展示摘要并确认 → 先生成当前数据快照（失败即中止）→ 校验 → 单事务全量替换 → 重建照片路径
 * - 旧备份恢复后不会残留与备份库存矛盾的较新流水（全量替换，不做 UUID 覆盖式合并）
 */
@Singleton
class BackupService @Inject constructor(
    private val db: AppDatabase,
    @ApplicationContext private val appContext: Context
) {
    private val snapshotDir: File get() = File(appContext.filesDir, "backup-snapshots")
    private val photoDir: File get() = File(appContext.filesDir, "photos")
    private val latestSnapshot: File get() = File(snapshotDir, "pre-restore-latest.acb")

    val hasSnapshot: Boolean get() = latestSnapshot.isFile

    /* ── 导出 ── */

    private suspend fun currentBundle(): BackupCodec.Bundle {
        val bottles = db.bottleDao().getAll()
        val notes = db.noteDao().getAll()
        val photos = mutableMapOf<String, ByteArray>()
        fun collectPhoto(uri: String?, ownerId: String): String? {
            if (uri == null) return null
            val path = if (uri.startsWith("file://")) uri.removePrefix("file://") else uri
            val f = File(path)
            if (!f.isFile || f.length() > 20 * 1024 * 1024) return null   /* 旧设备 URI / 过大文件跳过 */
            if (!f.canonicalPath.startsWith(appContext.filesDir.canonicalPath)) return null  /* 只备份私有目录照片 */
            val entry = "photos/" + ownerId + "." + (f.extension.ifBlank { "jpg" })
            photos[entry] = f.readBytes()
            return "backup-photo:" + entry
        }
        val bottlesWithPhoto = bottles.map { it.copy(photoUri = collectPhoto(it.photoUri, "bottle-" + it.id) ?: it.photoUri) }
        val notesWithPhoto = notes.map { it.copy(photoUri = collectPhoto(it.photoUri, "note-" + it.id) ?: it.photoUri) }
        return BackupCodec.Bundle(
            bottles = bottlesWithPhoto,
            txns = db.txnDao().getAll(),
            sessions = db.sessionDao().getAll(),
            notes = notesWithPhoto,
            favorites = db.favoriteDao().getAll(),
            customRecipes = db.customRecipeDao().getAll(),
            customIngredients = db.customIngredientDao().getAll(),
            kv = db.kvDao().getAll(),
            photos = photos,
            exportedAt = System.currentTimeMillis()
        )
    }

    suspend fun exportTo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = BackupCodec.encode(currentBundle())
            appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        } catch (e: Exception) { false }
    }

    /* ── 恢复 ── */

    suspend fun summarize(uri: Uri): Result<BackupCodec.Summary> = withContext(Dispatchers.IO) {
        runCatching { BackupCodec.summarize(readCapped(uri)) }
    }

    /** 全量替换恢复。恢复前快照失败时不继续覆盖。 */
    suspend fun restore(uri: Uri): Result<BackupCodec.Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val bundle = BackupCodec.decode(readCapped(uri))          /* 全部校验通过才继续 */
            writeSnapshot()                                            /* 快照失败 → 抛错中止 */
            applyBundle(bundle)
            bundle.toSummary()
        }
    }

    /** 回滚到最近一次恢复前的快照 */
    suspend fun rollbackToSnapshot(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val f = latestSnapshot
            if (!f.isFile) error("没有可用的恢复前快照")
            val bundle = BackupCodec.decode(f.readBytes())
            applyBundle(bundle)
        }
    }

    private suspend fun writeSnapshot() {
        val bytes = BackupCodec.encode(currentBundle())
        snapshotDir.mkdirs()
        val tmp = File(snapshotDir, "pre-restore-tmp.acb")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(latestSnapshot)) {
            latestSnapshot.writeBytes(bytes)
            tmp.delete()
        }
        if (!latestSnapshot.isFile || latestSnapshot.length() == 0L) error("无法生成当前数据快照，已取消恢复")
    }

    /** 全量替换：同一事务清空并写入；任一失败整体回滚，保持恢复前状态 */
    private suspend fun applyBundle(bundle: BackupCodec.Bundle) {
        /* 照片先落盘到新的私有路径，DB 里写入新路径（不复制旧设备 URI） */
        val photoPath = mutableMapOf<String, String>()
        if (bundle.photos.isNotEmpty()) {
            photoDir.mkdirs()
            bundle.photos.forEach { (entry, bytes) ->
                val name = entry.removePrefix("photos/").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val f = File(photoDir, name)
                f.writeBytes(bytes)
                photoPath["backup-photo:" + entry] = f.absolutePath
            }
        }
        BackupRestore.apply(db, bundle) { uri ->
            when {
                uri != null && photoPath.containsKey(uri) -> photoPath[uri]
                uri != null && uri.startsWith("backup-photo:") -> null
                else -> uri
            }
        }
    }

    private fun readCapped(uri: Uri): ByteArray {
        /* 上限与 BackupCodec.decode 的文件上限一致（65 MiB），避免先整体读入再被拒 */
        val cap = 65L * 1024 * 1024
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) error("备份文件过大")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
        error("无法读取备份文件")
    }
}
