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
            val suMissing = RootShell.suPath() == null
            return Outcome(
                0, 0, 0, errors,
                error = if (suMissing)
                    "找不到 su：root 授权是在进程启动时注入的，授权后请「强制停止」本应用再重新打开；" +
                            "若仍找不到，说明 root 管理器没给应用提供 su（KernelSU 请检查 su 兼容/隐藏设置）"
                else
                    "su 存在但命令没有执行：确认 KernelSU 已给本应用授权（更新过 APK 可能要重新授权）",
                diagnostics = RootShell.describeState() + "\npull:\n" + pulled.output.takeLast(1200)
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

        return Outcome(setCount, copied, lowQualityCount, errors, error = null, diagnostics = "")
    }

    private data class PullResult(
        val rootWorked: Boolean,
        val byPackage: Map<String, List<File>>,
        val output: String,
        val hits: Int
    )

    /**
     * One su call for everything: prove root ran (the sentinel line), then copy the main and per-account
     * databases of every client we know. Writing files into our own cache dir is enough to tell us
     * which client exists, so no separate package detection is needed.
     */
    private fun pullDatabases(work: File): PullResult {
        // One global search is the truth: it covers /data/data (symlink to /data/user/0), every other
        // user profile (/data/user/<n>) and any layout a fork invented. No package names needed.
        val findScript = StringBuilder()
        findScript.appendLine("echo TGSP_ROOT_OK")
        findScript.appendLine("id")
        findScript.appendLine("find /data -maxdepth 7 -type f -name 'cache4.db' 2>/dev/null | head -40")
        findScript.appendLine("echo TGSP_DONE")
        val findFile = File(context.cacheDir, "tgsp_find_db.sh")
        findFile.writeText(findScript.toString())
        val found = RootShell.runScript(findFile, timeoutSeconds = 300)
        val rootWorked = found.stdout.contains("TGSP_ROOT_OK")

        val hits = found.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith("/cache4.db") }
            .distinct()
            .toList()

        if (hits.isNotEmpty()) {
            val copyScript = StringBuilder()
            copyScript.appendLine("mkdir -p ${RootShell.quote(work.absolutePath)}")
            var index = 0
            hits.forEach { src ->
                val pkg = packageOfPath(src)
                val pkgDir = File(work, pkg)
                copyScript.appendLine("mkdir -p ${RootShell.quote(pkgDir.absolutePath)}")
                copyScript.appendLine(
                    "cp -f ${RootShell.quote(src)} ${RootShell.quote(File(pkgDir, "%02d".format(index++) + ".db").absolutePath)}"
                )
            }
            val copyFile = File(context.cacheDir, "tgsp_copy_db.sh")
            copyFile.writeText(copyScript.toString())
            RootShell.runScript(copyFile, timeoutSeconds = 300)
        }

        val output = (found.stdout + found.stderr).trim()
        val byPackage = group(work)
        L.info("root=$rootWorked hits=${hits.size} clients=${byPackage.keys}")
        return PullResult(rootWorked, byPackage, output, hits.size)
    }

    private fun group(work: File): Map<String, List<File>> {
        val byPackage = HashMap<String, List<File>>()
        val dirs = work.listFiles { f -> f.isDirectory } ?: return byPackage
        dirs.forEach { dir ->
            val dbs = dir.listFiles { f -> f.name.endsWith(".db") && f.length() > 0 }?.toList()
            if (!dbs.isNullOrEmpty()) byPackage[dir.name] = dbs
        }
        return byPackage
    }

    /** /data/data/<pkg>/... or /data/user/0/<pkg>/... */
    private fun packageOfPath(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return when {
            parts.size > 3 && parts[0] == "data" && parts[1] == "data" -> parts[2]
            parts.size > 4 && parts[0] == "data" && parts[1] == "user" -> parts[3]
            else -> "unknown"
        }
    }

    /** nothing found: a one line verdict plus the lines that identify why */
    private fun diagnose(pullOutput: String): String {
        val script = StringBuilder()
        script.appendLine("echo DIAG-BEGIN")
        script.appendLine("id")
        script.appendLine("echo '--- installed'")
        script.appendLine("pm list packages 2>/dev/null | grep -iE 'telegram|nagram|messenger' | head -5")
        script.appendLine("echo '--- data dir'")
        script.appendLine("ls -la /data/user/0/fork.risin42.nagramx 2>&1 | head -20")
        script.appendLine("echo '--- files dir'")
        script.appendLine("ls -la /data/user/0/fork.risin42.nagramx/files 2>&1 | head -25")
        script.appendLine("echo '--- dbs'")
        script.appendLine("find /data/user/0/fork.risin42.nagramx -maxdepth 4 -type f \\( -name '*.db' -o -name '*.db-wal' -o -name '*.sqlite' \\) 2>/dev/null | head -20")
        script.appendLine("echo DIAG-END")

        val scriptFile = File(context.cacheDir, "tgsp_diag.sh")
        scriptFile.writeText(script.toString())
        val result = RootShell.runScript(scriptFile, timeoutSeconds = 120)
        val diag = ("pull:\n" + pullOutput.takeLast(1500) + "\n" + result.stdout + result.stderr).trim()
        val dump = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics.txt")
        try {
            dump.writeText(diag)
            L.warn("diagnostics written to ${dump.absolutePath}")
        } catch (t: Throwable) {
            L.warn("cannot write diagnostics", t)
        }
        // The dialog only shows the head: id + the top-level listings are what identify the problem,
        // and they must fit one screenshot. The whole log is in diagnostics.txt.
        val uidLine = diag.lineSequence().firstOrNull { it.startsWith("uid=") }?.trim() ?: "uid=?"
        val users = diag.lineSequence().count { it.startsWith("/data/user/") }
        return "结论: $uidLine · 找到 cache4.db 0 个 · /data/user 可见 $users 个\n" + diag.take(1200)
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
