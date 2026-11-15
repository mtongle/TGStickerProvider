package cc.microblock.TGStickerProvider.importer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import cc.microblock.TGStickerProvider.*
import cc.microblock.TGStickerProvider.hook.TelegramTLParser.SerializedData
import cc.microblock.TGStickerProvider.hook.TelegramTLParser.TLRPC
import cc.microblock.TGStickerProvider.utils.L
import java.io.File

/**
 * Reads the sticker sets from the snapshot that `/sdcard/tgsp_pull.sh` produced.
 *
 * Copying the client's databases has to happen from a real root shell (MT/Termux): an app process
 * lives in a mount namespace where other apps' data dirs are invisible even with uid 0, which is why
 * every in-app `find /data` came back empty. So the split is: the script only copies files, this
 * class only parses them. No root needed here, no Xposed.
 *
 * Expected layout:
 *   /sdcard/TGSP/db/main.db, main.db-wal, account1.db, ...    (cache4.db of every account)
 *   /sdcard/TGSP/cache/<dc>_<docId>.webp|.webm, -<docId>_1109.webp
 */
class StickerImporter(private val context: Context) {

    data class Outcome(
        val sets: Int,
        val copiedFiles: Int,
        val errors: List<String>,
        val error: String? = null
    )

    private val wanted = ArrayList<Pair<File, File>>()
    private val errors = ArrayList<String>()
    private var setCount = 0

    fun import(progress: (String) -> Unit = {}): Outcome {
        wanted.clear()
        errors.clear()
        setCount = 0

        val root = dumpRoot()
        val dbDir = File(root, "db")
        val cacheDir = File(root, "cache")

        val dbs = dbDir.listFiles { f -> f.name.endsWith(".db") && f.length() > 0 }?.sortedBy { it.name }
        if (dbs.isNullOrEmpty()) {
            return Outcome(
                0, 0, errors,
                error = "还没有数据：先在 MT/Termux 的 root shell 里跑 sh /sdcard/tgsp_pull.sh"
            )
        }
        if (!cacheDir.isDirectory) {
            return Outcome(
                0, 0, errors,
                error = "缺少 /sdcard/TGSP/cache，请重新跑一遍 tgsp_pull.sh"
            )
        }

        progress("解析 ${dbs.size} 个账号数据库")
        dbs.forEach { db ->
            try {
                parseDb(db, cacheDir)
            } catch (t: Throwable) {
                L.error("parse failed: ${db.name}", t)
                errors.add("${db.name}: ${t.message}")
            }
        }

        progress("复制 ${wanted.size} 个表情文件")
        Materialising.writeNomedia()
        val copied = copyWanted()

        return Outcome(setCount, copied, errors)
    }

    private fun parseDb(dbFile: File, cacheDir: File) {
        val dedupSet = HashSet<Int>()
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val rows = ArrayList<IntArray>()
            db.rawQuery("SELECT id, hash, length(data) FROM stickers_v2 WHERE data NOT NULL", null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(intArrayOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)))
                    }
                }
            L.info("${dbFile.name}: ${rows.size} sticker rows")

            val stickerSets = ArrayList<TLRPC.TL_messages_stickerSet>()
            for (row in rows) {
                val data = try {
                    readStickerBlob(db, row[0], row[2])
                } catch (t: Throwable) {
                    L.error("read blob ${row[0]} failed", t)
                    null
                } ?: continue

                val stream = SerializedData(data)
                try {
                    var count = stream.readInt32(true)
                    var constructorId = -1
                    if (TLRPC.TL_messages_stickerSet.isStickerSetConstructor(count)) {
                        constructorId = count
                        count = stream.readInt32(true)
                    }
                    for (i in 0 until count) {
                        try {
                            if (i != 0 || constructorId == -1) constructorId = stream.readInt32(true)
                            if (!TLRPC.TL_messages_stickerSet.isStickerSetConstructor(constructorId)) break
                            val set = TLRPC.TL_messages_stickerSet.TLdeserialize(stream, constructorId, true)
                            if (!dedupSet.add(set.set.hash)) continue
                            stickerSets.add(set)
                        } catch (e: Exception) {
                            L.error("deserialize failed ${dbFile.name}#${row[0]}/$i", e)
                        }
                    }
                } catch (e: Exception) {
                    L.warn("blob ${row[0]} unreadable", e)
                }
            }

            stickerSets.forEach { collectSet(it, cacheDir) }
        }
    }

    private fun collectSet(set: TLRPC.TL_messages_stickerSet, cacheDir: File) {
        val shortName = set.set.short_name
        if (shortName.isNullOrEmpty()) return

        val destDir = File(destDataPath, "tgSync_$shortName")
        val metaFile = File(stickerDataPath, "${set.set.hash}.stickerData.txt.jpg")
        try {
            File(stickerDataPath).mkdirs()
            metaFile.writeText(
                shortName + "\n" + set.set.title + "\n" + set.set.count + "\n" +
                        set.documents[0]?.mime_type
            )
            setCount++
        } catch (t: Throwable) {
            errors.add("$shortName: 写元数据失败 ${t.message}")
            return
        }

        for (sticker in set.documents) {
            when (sticker.mime_type) {
                "image/webp" -> {
                    wanted.add(
                        File(cacheDir, "${sticker.dc_id}_${sticker.id}.webp") to
                                File(destDir, "${sticker.id}_high.webp")
                    )
                    wanted.add(
                        File(cacheDir, "-${sticker.id}_1109.webp") to
                                File(destDir, "${sticker.id}_low.webp")
                    )
                }
                "video/webm" -> {
                    wanted.add(
                        File(cacheDir, "${sticker.dc_id}_${sticker.id}.webm") to
                                File(destDir, "${sticker.id}_high.webm")
                    )
                }
            }
        }
    }

    private fun copyWanted(): Int {
        var copied = 0
        wanted.forEach { (src, dest) ->
            try {
                if (!src.exists()) return@forEach
                dest.parentFile?.mkdirs()
                if (dest.exists() && dest.length() == src.length()) {
                    copied++
                    return@forEach
                }
                src.copyTo(dest, overwrite = true)
                // a high-quality file makes its low-quality sibling redundant
                if (dest.name.endsWith("_high.webp") || dest.name.endsWith("_high.webm")) {
                    File(dest.parentFile, dest.name.replace("_high.", "_low.")).delete()
                }
                copied++
            } catch (t: Throwable) {
                errors.add("${src.name}: ${t.message}")
            }
        }
        return copied
    }

    private fun readStickerBlob(db: SQLiteDatabase, id: Int, length: Int): ByteArray? {
        if (length <= 0) return null
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val size = minOf(BLOB_CHUNK_SIZE, length - offset)
            var chunk: ByteArray? = null
            db.rawQuery(
                "SELECT substr(data, ${offset + 1}, $size) FROM stickers_v2 WHERE id = $id",
                null
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) chunk = cursor.getBlob(0)
            }
            val bytes = chunk ?: return null
            if (bytes.isEmpty()) return null
            val n = minOf(bytes.size, length - offset)
            System.arraycopy(bytes, 0, out, offset, n)
            offset += n
        }
        return out
    }

    companion object {
        private const val BLOB_CHUNK_SIZE = 256 * 1024

        fun dumpRoot(): File = File(Environment.getExternalStorageDirectory(), "TGSP")

        fun hasDump(): Boolean {
            val dbs = File(dumpRoot(), "db").listFiles { f -> f.name.endsWith(".db") }
            return !dbs.isNullOrEmpty()
        }
    }
}

/** small helpers shared with the UI */
object Materialising {
    fun writeNomedia() {
        try {
            File(tgspDataPath).mkdirs()
            File(stickerDataPath).mkdirs()
            File(destDataPath).mkdirs()
            if (!File(nomediaPath).exists()) File(nomediaPath).createNewFile()
        } catch (t: Throwable) {
            L.warn("cannot prepare export folders", t)
        }
    }
}
