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
 * This used to run inside the client process as an Xposed hook; injection hangs the client on this
 * device, so the work is done from our own process instead. Exactly two superuser calls are made per
 * import - one script that copies every `cache4.db` it can find for the known clients (which doubles
 * as the "is root working / which client is installed" probe) and one that copies the sticker files
 * the parsed sets reference.
 */
class StickerImporter(private val context: Context) {

    data class Outcome(
        val sets: Int,
        val copiedFiles: Int,
        val lowQualityFiles: Int,
        val errors: List<String>,
        val error: String? = null,
        val diagnostics: String = ""
    )

    private data class WantedFile(val source: String, val dest: String)

    private val wanted = ArrayList<WantedFile>()
    private val errors = ArrayList<String>()
    private var setCount = 0
    private var lowQualityCount = 0

    fun import(clientPackage: String? = null, progress: (String) -> Unit = {}): Outcome {
        wanted.clear()
        errors.clear()
        setCount = 0
        lowQualityCount = 0

        val work = File(context.cacheDir, "tgsp").apply {
            mkdirs()
            listFiles()?.forEach { it.deleteRecursively() }
            mkdirs()
        }

        progress("读取数据库（root）")
        val pulled = pullDatabases(work)
        if (!pulled.rootWorked) {
            return Outcome(
                0, 0, 0, errors,
                error = "su 没有真正执行命令：请确认 KernelSU 里已给本应用授权（若刚更新过 APK 需要重新授权）",
                diagnostics = pulled.output.takeLast(1500)
            )
        }
        val dbs = pulled.byPackage.entries.firstOrNull { clientPackage == null || it.key == clientPackage }
        if (dbs == null || dbs.value.isEmpty()) {
            return Outcome(
                0, 0, 0, errors,
                error = "root 正常，但没找到客户端的 cache4.db",
                diagnostics = diagnose(pulled.output)
            )
        }

        val pkg = dbs.key
        val cachePath = getCachePath(pkg)

        progress("解析表情包（$pkg，${dbs.value.size} 个数据库）")
        for (db in dbs.value) {
            try {
                parseDb(db, cachePath)
            } catch (t: Throwable) {
                L.error("parse failed: ${db.name}", t)
                errors.add("${db.name}: ${t.message}")
            }
        }

        progress("复制表情文件（${wanted.size}）")
        val copied = copyFiles()
        Materialising.writeNomedia()
        RootShell.rememberAvailable(true)

        return Outcome(setCount, copied, lowQualityCount, errors, error = null, debug = "")
    }

    private data class PullResult(val rootWorked: Boolean, val byPackage: Map<String, List<File>>, val output: String)

    /**
     * One su call for everything: prove root ran (the sentinel line), then copy the main and per-account
     * databases of every client we know. Writing files into our own cache dir is enough to tell us
     * which client exists, so no separate package detection is needed.
     */
    private fun pullDatabases(work: File): PullResult {
        // Literal-only scripts: every path is baked in from Kotlin, so there are no shell variables
        // to escape wrongly and `su` on any root implementation can run them verbatim.

        // 1. ask where the databases are
        val listScript = StringBuilder()
        listScript.appendLine("echo TGSP_ROOT_OK")
        CLIENT_PACKAGES.forEach { pkg ->
            listScript.appendLine("echo '###$pkg'")
            listScript.appendLine(
                "find ${RootShell.quote("/data/data/$pkg")} -maxdepth 5 -type f -name 'cache4.db' 2>/dev/null"
            )
        }
        listScript.appendLine("echo TGSP_DONE")
        val listFile = File(context.cacheDir, "tgsp_list_db.sh")
        listFile.writeText(listScript.toString())
        val listed = RootShell.runScript(listFile, timeoutSeconds = 180)
        val rootWorked = listed.stdout.contains("TGSP_ROOT_OK")

        val found = LinkedHashMap<String, MutableList<String>>()
        var current: String? = null
        listed.stdout.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("###")) {
                current = line.removePrefix("###")
                found.getOrPut(current!!) { ArrayList() }
            } else if (line.endsWith("cache4.db") && current != null) {
                found[current!!]?.add(line)
            }
        }

        // 2. copy what was found into our own cache dir
        val copyScript = StringBuilder()
        copyScript.appendLine("mkdir -p ${RootShell.quote(work.absolutePath)}")
        var index = 0
        found.forEach { (pkg, paths) ->
            if (paths.isEmpty()) return@forEach
            val pkgDir = File(work, pkg)
            copyScript.appendLine("mkdir -p ${RootShell.quote(pkgDir.absolutePath)}")
            paths.forEach { src ->
                val name = "%02d".format(index++) + ".db"
                copyScript.appendLine(
                    "cp -f ${RootShell.quote(src)} ${RootShell.quote(File(pkgDir, name).absolutePath)}"
                )
            }
        }
        val copyFile = File(context.cacheDir, "tgsp_copy_db.sh")
        copyFile.writeText(copyScript.toString())
        val copied = RootShell.runScript(copyFile, timeoutSeconds = 240)
        val output = (listed.stdout + listed.stderr + copied.stderr).trim()

        val byPackage = HashMap<String, List<File>>()
        CLIENT_PACKAGES.forEach { pkg ->
            val dbs = File(work, pkg).listFiles { f -> f.name.endsWith(".db") && f.length() > 0 }?.toList()
            if (!dbs.isNullOrEmpty()) byPackage[pkg] = dbs
        }
        L.info("root=$rootWorked clients=${byPackage.keys} out=${output.take(200)}")
        return PullResult(rootWorked, byPackage, output)
    }

    /** nothing found: show the user (and us) what the client's data dir actually looks like */
    private fun diagnose(pullOutput: String): String {
        val script = StringBuilder()
        script.appendLine("echo DIAG-BEGIN")
        CLIENT_PACKAGES.forEach { pkg ->
            script.appendLine("echo '## $pkg'")
            script.appendLine("ls -la ${RootShell.quote("/data/data/$pkg")} 2>&1 | head -25")
            script.appendLine("ls -la ${RootShell.quote("/data/data/$pkg/files")} 2>&1 | head -40")
            script.appendLine(
                "find ${RootShell.quote("/data/data/$pkg")} -maxdepth 4 -name '*.db*' 2>/dev/null | head -40"
            )
        }
        script.appendLine("echo DIAG-END")

        val scriptFile = File(context.cacheDir, "tgsp_diag.sh")
        scriptFile.writeText(script.toString())
        val result = RootShell.runScript(scriptFile, timeoutSeconds = 120)
        val diag = (result.stdout + result.stderr).trim()
        return ("pull:\n" + pullOutput.takeLast(600) + "\n" + diag).takeLast(6000)
    }

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

            stickerSets.forEach { collectSet(it, cachePath) }
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
                    wanted.add(
                        WantedFile(
                            File(cachePath, "${sticker.dc_id}_${sticker.id}.webp").path,
                            File(destDir, "${sticker.id}_high.webp").path
                        )
                    )
                    wanted.add(
                        WantedFile(
                            File(cachePath, "-${sticker.id}_1109.webp").path,
                            File(destDir, "${sticker.id}_low.webp").path
                        )
                    )
                    lowQualityCount++
                }
                "video/webm" -> {
                    wanted.add(
                        WantedFile(
                            File(cachePath, "${sticker.dc_id}_${sticker.id}.webm").path,
                            File(destDir, "${sticker.id}_high.webm").path
                        )
                    )
                }
            }
        }
    }

    /** second su call: copy the referenced cache files into the export folder */
    private fun copyFiles(): Int {
        if (wanted.isEmpty()) return 0
        val script = StringBuilder()
        script.appendLine("echo TGSP_ROOT_OK")
        script.appendLine("mkdir -p ${RootShell.quote(destDataPath)}")
        var lastParent: String? = null
        wanted.forEach { w ->
            val parent = File(w.dest).parent
            if (parent != null && parent != lastParent) {
                script.appendLine("mkdir -p ${RootShell.quote(parent)}")
                lastParent = parent
            }
            script.appendLine(
                "cp -f ${RootShell.quote(w.source)} ${RootShell.quote(w.dest)} 2>/dev/null || true"
            )
        }
        // a sticker that was never downloaded leaves a zero-byte target behind
        script.appendLine("find ${RootShell.quote(destDataPath)} -name '*.webp' -size 0 -delete 2>/dev/null || true")
        script.appendLine("find ${RootShell.quote(destDataPath)} -name '*.webm' -size 0 -delete 2>/dev/null || true")
        script.appendLine("echo TGSP_DONE")

        val scriptFile = File(context.cacheDir, "tgsp_copy_files.sh")
        scriptFile.writeText(script.toString())
        val result = RootShell.runScript(scriptFile, timeoutSeconds = 600)
        if (!result.ok) L.warn("file copy script: ${result.summary()}")

        var copied = 0
        wanted.forEach { if (File(it.dest).exists() && File(it.dest).length() > 0) copied++ }
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
