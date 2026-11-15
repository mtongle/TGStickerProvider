package cc.microblock.TGStickerProvider.hook

import android.annotation.SuppressLint
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import cc.microblock.TGStickerProvider.*
import cc.microblock.TGStickerProvider.hook.TelegramTLParser.SerializedData
import cc.microblock.TGStickerProvider.hook.TelegramTLParser.TLRPC
import cc.microblock.TGStickerProvider.utils.CachePathHelper.getCachePath
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread


@InjectYukiHookWithXposed(entryClassName = "TGStickerProvider", isUsingXposedModuleStatus = true)
class HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs {
        debugLog {
            tag = "TGStickerProvider"
            isEnable = BuildConfig.DEBUG
        }

        // The old "CursorWindow.sCursorWindowSize = 100MB" patch used to live here. It is gone on
        // purpose: it is a hidden API, it changes a process-global default for every cursor the
        // client creates, and with modern clients it makes Telegram's storage thread die with
        // SIGBUS (upstream issue #6). Oversized blobs are now read in chunks, see readStickerBlob().
    }

    @SuppressLint("SdCardPath")
    override fun onHook() = encase {
        if (this.packageName.startsWith("android")) return@encase

        val pkg = this.packageName
        val ctx = this.appContext

        // Telegram forks spawn extra processes (:push, :webview, :account, ...). LSPosed hooks all
        // of them, and every copy would run this worker against the same cache4.db and the same
        // export folder. Only the main process does the work.
        val processName = try {
            Application.getProcessName()
        } catch (t: Throwable) {
            null
        }
        if (processName != null && processName != pkg) return@encase

        // A detached thread that only stats a file until the module UI asks for a sync. Touching the
        // TL parser classes this early is what deadlocks the client: the module dex is verified in
        // the client's own class linker while the client is still defining its startup classes.
        thread(start = true, isDaemon = true) {
            try {
                var lastRequest = 0L
                while (true) {
                    val request = File(syncRequestPath)
                    val stamp = try {
                        if (request.exists()) request.lastModified() else 0L
                    } catch (t: Throwable) {
                        0L
                    }
                    if (stamp > 0L && stamp != lastRequest) {
                        lastRequest = stamp
                        request.delete()
                        runSyncLoop(pkg, ctx) // loops until the process dies
                        return@thread
                    }
                    Thread.sleep(REQUEST_POLL_MS)
                }
            } catch (e: InterruptedException) {
                // module unloaded or process shutting down
            } catch (t: Throwable) {
                YLog.error("worker stopped", t)
            }
        }
    }

    private fun runSyncLoop(pkg: String, ctx: Application?) {
        val ignoreSet = HashSet<Int>()
        val dataPath = "/data/data/$pkg/"
        val tgCachePath = getCachePath(ctx, pkg)

        if (!File(tgspDataPath).exists()) {
            File(tgspDataPath).mkdirs()
            File(stickerDataPath).mkdirs()
            File(syncFlagsPath).mkdirs()
            File(destDataPath).mkdirs()
        }

        if (!File(nomediaPath).exists()) {
            File(nomediaPath).createNewFile()
        }

        while (true) {
            try {
                // Main DB
                checkDb("${dataPath}/files/cache4.db", tgCachePath, ignoreSet)

                // Separate DBs for each account
                File("$dataPath/files/").listFiles()?.filter {
                    it.isDirectory && it.name.startsWith("account")
                }?.forEach {
                    checkDb("${it.path}/cache4.db", tgCachePath, ignoreSet)
                }
            } catch (e: Exception) {
                YLog.debug(e.toString())
            } catch (t: Throwable) {
                YLog.error("sync pass failed", t)
            }

            try {
                File(syncDonePath).writeText(System.currentTimeMillis().toString())
            } catch (t: Throwable) {
                YLog.warn("cannot write sync marker", t)
            }

            Thread.sleep(40 * 1000)
        }
    }

    /**
     * stickers_v2.data holds one serialized TL_messages_stickerSet per row and can reach several MB,
     * which is more than a single CursorWindow can carry (the 2MB limit). substr() pulls the row out
     * in small pieces so the client keeps its default window size and its native sqlite stays intact.
     */
    private fun readStickerBlob(db: SQLiteDatabase, id: Int, length: Int): ByteArray? {
        if (length <= 0) return null
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val size = minOf(BLOB_CHUNK_SIZE, length - offset)
            var chunk: ByteArray? = null
            // integers are inlined on purpose: substr() with TEXT-bound args is rejected as
            // "datatype mismatch" by some bundled SQLite builds, and these are ints we produced.
            db.rawQuery(
                "SELECT substr(data, ${offset + 1}, $size) FROM stickers_v2 WHERE id = $id",
                null
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    chunk = cursor.getBlob(0)
                }
            }
            val bytes = chunk ?: return null
            if (bytes.isEmpty()) return null
            val n = minOf(bytes.size, length - offset)
            System.arraycopy(bytes, 0, out, offset, n)
            offset += n
        }
        return out
    }

    private fun checkDb(dbPath: String, tgCachePath: String, ignoreSet: HashSet<Int>) {
        val cache4DB = File(dbPath)
        if (!cache4DB.exists()) return

        val dedupSet = HashSet<Int>()
        SQLiteDatabase.openDatabase(cache4DB.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            // id, hash, length(data) - never the blob itself, so the cursor stays tiny
            val rows = ArrayList<IntArray>()
            db.rawQuery("SELECT id, hash, length(data) FROM stickers_v2 WHERE data NOT NULL", null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(intArrayOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)))
                    }
                }

            val stickerSets = ArrayList<TLRPC.TL_messages_stickerSet>()
            for (row in rows) {
                val id = row[0]
                val data = try {
                    readStickerBlob(db, id, row[2])
                } catch (t: Throwable) {
                    YLog.error("Failed to read sticker blob id=$id", t)
                    null
                } ?: continue

                val stream = SerializedData(data)
                try {
                    var count = stream.readInt32(true)
                    var constructorId = -1

                    if (TLRPC.TL_messages_stickerSet.isStickerSetConstructor(count)) {
                        // old DB sheet
                        constructorId = count
                        count = stream.readInt32(true)
                    }

                    for (i in 0 until count) {
                        try {
                            if (i != 0 || constructorId == -1)
                                constructorId = stream.readInt32(true)

                            if (!TLRPC.TL_messages_stickerSet.isStickerSetConstructor(constructorId)) {
                                YLog.error("Break on $dbPath::stickers_v2 type $id index $i; $constructorId is not a sticker set")
                                break
                            }

                            val stickerSet =
                                TLRPC.TL_messages_stickerSet.TLdeserialize(stream, constructorId, true)
                            val hash = stickerSet.set.hash
                            if (dedupSet.contains(hash)) continue
                            dedupSet.add(hash)
                            stickerSets.add(stickerSet)
                        } catch (e: Exception) {
                            YLog.error("Failed to deserialize $dbPath::stickers_v2 type $id index $i", e)
                        }
                    }
                } catch (e: Exception) {
                    YLog.warn("", e)
                    continue
                }
            }

            for (stickerSet in stickerSets) {
                // use .txt.jpg to bypass the file type check in android 11+
                val stickerNameFile =
                    File(
                        stickerDataPath,
                        "${stickerSet.set.hash}.stickerData.txt.jpg"
                    )
                try {
                    File(stickerDataPath).mkdirs()
                    stickerNameFile.createNewFile()
                    stickerNameFile.writeText(
                        stickerSet.set.short_name + "\n" +
                                stickerSet.set.title + "\n" +
                                stickerSet.set.count + "\n" +
                                stickerSet.documents[0]?.mime_type
                    )
                } catch (e: Exception) {
                    if (e is FileNotFoundException && e.message?.contains("open failed:") == true)
                        continue // has occupied by another app

                    YLog.error("Failed to handle ${stickerNameFile.absolutePath}", e)
                    continue
                }

                var fullSync = true
                val destDir = "${destDataPath}/tgSync_${stickerSet.set.short_name}"
                File(destDir).mkdirs()

                var lowQualityCount = 0
                var highQualityCount = 0
                var printLog = false // only print log if there is a new sticker

                for (sticker in stickerSet.documents) {
                    when (sticker.mime_type) {
                        "image/webp" -> {
                            val localPath = "${sticker.dc_id}_${sticker.id}.webp"
                            val localPathLowQuality = "-${sticker.id}_1109.webp"
                            val stickerFile = File(tgCachePath, localPath)
                            val destFile = File(destDir, "${sticker.id}_high.webp")
                            val destFileLowQuality = File(destDir, "${sticker.id}_low.webp")

                            if (stickerFile.exists()) {
                                highQualityCount++
                                if (!destFile.exists()) {
                                    printLog = true
                                    stickerFile.copyTo(destFile)
                                    if (destFileLowQuality.exists()) {
                                        destFileLowQuality.delete()
                                    }
                                }
                            } else if (File(tgCachePath, localPathLowQuality).exists()) {
                                lowQualityCount++
                                if (!destFileLowQuality.exists() && !destFile.exists()) {
                                    printLog = true
                                    File(tgCachePath, localPathLowQuality).copyTo(destFileLowQuality)
                                }
                            } else {
                                if (!destFile.exists()) fullSync = false
                            }
                        }

                        "video/webm" -> {
                            val localPath = "${sticker.dc_id}_${sticker.id}.webm"
                            val stickerFile = File(tgCachePath, localPath)
                            val destFile = File(destDir, "${sticker.id}_high.webm")
                            if (stickerFile.exists()) {
                                highQualityCount++
                                if (!destFile.exists()) {
                                    printLog = true
                                    stickerFile.copyTo(destFile)
                                }
                            }
                        }

                        else -> continue
                    }
                }

                if (printLog)
                    YLog.debug("*new* [${lowQualityCount + highQualityCount}(Low$lowQualityCount High${highQualityCount})/${stickerSet.set.count}] ${stickerSet.set.title} ${stickerSet.set.short_name} ${stickerSet.set.count} ${stickerSet.set.hash}")
                if (fullSync) ignoreSet.add(stickerSet.set.hash)
            }
        }
    }

    companion object {
        /** how often the worker looks for a sync request while it is still idle */
        private const val REQUEST_POLL_MS = 1_500L

        /** bytes pulled per substr() query while reading one stickers_v2 blob. */
        private const val BLOB_CHUNK_SIZE = 256 * 1024
    }
}
