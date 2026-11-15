package cc.microblock.TGStickerProvider.importer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import cc.microblock.TGStickerProvider.*
import cc.microblock.TGStickerProvider.hook.TelegramTLParser.SerializedData
import cc.microblock.TGStickerProvider.hook.TelegramTLParser.TLRPC
import cc.microblock.TGStickerProvider.utils.CachePathHelper.getCachePath
import cc.microblock.TGStickerProvider.utils.L
import cc.microblock.TGStickerProvider.utils.RootShell
import java.io.File

/**
 * Reads a Telegram client's sticker sets with root and materialises them into the export folders.
 *
 * Previously this ran inside the client process as an Xposed hook. Injection is unusable on this
 * device (the framework hangs the client at startup), so the same work is done from our own process:
 * root copies the two cache4.db files out, we parse the stickers_v2 blobs here, then a second root
 * script copies only the sticker files that the parsed sets reference.
 */
class StickerImporter(private val context: Context) {

    data class Outcome(
        val sets: Int,
        val copiedFiles: Int,
        val lowQualityFiles: Int,
        val errors: List<String>
    )

    /** set short_name -> (document id -> mime type) is all we need to pick cache files */
    private val wanted = ArrayList<WantedFile>()
    private val errors = ArrayList<String>()
    private var setCount = 0
    private var lowQualityCount = 0

    private data class WantedFile(val source: String, val dest: String)

    fun import(clientPackage: String, progress: (String) -> Unit = {}): Outcome {
        wanted.clear()
        errors.clear()
        setCount = 0
        lowQualityCount = 0

        if (!RootShell.isAvailable(true)) {
            return Outcome(0, 0, 0, listOf("没有 root 权限：请在 Magisk/KernelSU 里授权本应用"))
        }

        val work = File(context.cacheDir, "tgsp").apply { mkdirs() }
        val cachePath = getCachePath(clientPackage)

        progress("读取数据库")
        val copiedDbs = copyDatabases(clientPackage, work)
        if (copiedDbs.isEmpty()) {
            return Outcome(0, 0, 0, listOf("没有找到 $clientPackage 的 cache4.db（客户端是否安装并登录过？）"))
        }

        progress("解析表情包（${copiedDbs.size} 个账号）")
        for (db in copiedDbs) {
            try {
                parseDb(db, cachePath)
            } catch (t: Throwable) {
                L.error("parse failed: ${db.name}", t)
                errors.add("${db.name}: ${t.message}")
            }
        }

        progress("复制表情文件（${wanted.size}）")
        val copied = copyFiles(work)
        Materialising.writeNomedia()

        return Outcome(setCount, copied, lowQualityCount, errors)
    }

    /** root: copy every cache4.db we can find into our private cache dir */
    private fun copyDatabases(pkg: String, work: File): List<File> {
        val base = "/data/data/$pkg/files"
        val listing = RootShell.run("ls -1 ${RootShell.quote(base)} 2>/dev/null")
        val accountDirs = listing.stdout.split('\n')
            .map { it.trim() }
            .filter { it.startsWith("account") }

        val script = StringBuilder()
        script.appendLine("mkdir -p ${RootShell.quote(work.absolutePath)}")
        script.appendLine(
            "cp -f ${RootShell.quote("$base/cache4.db")} " +
                    "${RootShell.quote(File(work, "main.db").absolutePath)} 2>/dev/null || true"
        )
        accountDirs.forEach { dir ->
            script.appendLine(
                "cp -f ${RootShell.quote("$base/$dir/cache4.db")} " +
                        "${RootShell.quote(File(work, "$dir.db").absolutePath)} 2>/dev/null || true"
            )
        }
        val scriptFile = File(context.cacheDir, "tgsp_copy_db.sh")
        scriptFile.writeText(script.toString())
        val result = RootShell.runScript(scriptFile)
        if (!result.ok) {
            L.warn("db copy script: ${result.summary()}")
        }

        work.listFiles { f -> f.name.endsWith(".db") && f.length() > 0 }?.let { return it.toList() }
        return emptyList()
    }

    /** parse one cache4.db copy and collect the sticker files it references */
    private fun parseDb(dbFile: File, cachePath: String) {
        val dedupSet = HashSet<Int>()
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val rows = ArrayList<IntArray>()
            db.rawQuery("SELECT id, hash, length(data) FROM stickers_v2 WHERE data NOT NULL", null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(intArrayOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)))
                    }
                }

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

            for (set in stickerSets) {
                collectSet(set, cachePath)
            }
        }
    }

    private fun collectSet(set: TLRPC.TL_messages_stickerSet, cachePath: String) {
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
                    val high = File(cachePath, "${sticker.dc_id}_${sticker.id}.webp")
                    val low = File(cachePath, "-${sticker.id}_1109.webp")
                    if (high.exists() || RootShell.isAvailable()) {
                        wanted.add(WantedFile(high.path, File(destDir, "${sticker.id}_high.webp").path))
                        // the low-quality thumb is optional; the copy script ignores misses
                        wanted.add(WantedFile(low.path, File(destDir, "${sticker.id}_low.webp").path))
                        lowQualityCount++
                    }
                }
                "video/webm" -> {
                    val high = File(cachePath, "${sticker.dc_id}_${sticker.id}.webm")
                    wanted.add(WantedFile(high.path, File(destDir, "${sticker.id}_high.webm").path))
                }
            }
        }
    }

    /** root: copy the referenced cache files into the export folder, then drop the empty ones */
    private fun copyFiles(work: File): Int {
        if (wanted.isEmpty()) return 0
        val script = StringBuilder()
        script.appendLine("mkdir -p ${RootShell.quote(destDataPath)}")
        wanted.forEach { w ->
            val destParent = File(w.dest).parent ?: return@forEach
            script.appendLine("mkdir -p ${RootShell.quote(destParent)}")
            script.appendLine(
                "cp -f ${RootShell.quote(w.source)} ${RootShell.quote(w.dest)} 2>/dev/null || true"
            )
        }
        // remove the low-quality placeholders that could not be copied
        script.appendLine("find ${RootShell.quote(destDataPath)} -name '*_low.webp' -size 0 -delete 2>/dev/null || true")

        val scriptFile = File(context.cacheDir, "tgsp_copy_files.sh")
        scriptFile.writeText(script.toString())
        val result = RootShell.runScript(scriptFile, timeoutSeconds = 600)
        if (!result.ok) L.warn("file copy script: ${result.summary()}")

        work.listFiles()?.forEach { it.delete() }

        var copied = 0
        wanted.forEach { if (File(it.dest).exists()) copied++ }
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
