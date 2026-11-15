package cc.microblock.TGStickerProvider.utils

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around `su -c`. The module used to run inside the Telegram client process (Xposed);
 * that path is dead on this device, so the app now asks root to hand it the client's databases and
 * sticker cache files instead.
 */
object RootShell {

    data class Result(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
        fun summary(): String =
            if (ok) stdout.trim() else "${stderr.trim().ifEmpty { stdout.trim() }} (exit=$code)"
    }

    @Volatile
    private var cachedAvailable: Boolean? = null

    fun isAvailable(force: Boolean = false): Boolean {
        val cached = cachedAvailable
        if (!force && cached != null) return cached
        val result = run("id")
        val available = result.ok && result.stdout.contains("uid=0")
        cachedAvailable = available
        return available
    }

    fun run(command: String, timeoutSeconds: Long = 120): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Result(-1, stdout, "timeout after ${timeoutSeconds}s")
            } else {
                Result(process.exitValue(), stdout, stderr)
            }
        } catch (t: Throwable) {
            Result(-1, "", t.toString())
        }
    }

    /** Runs a script that lives in our own files dir, so the command line stays short. */
    fun runScript(script: File, timeoutSeconds: Long = 180): Result {
        return run("sh '${script.absolutePath}'", timeoutSeconds)
    }

    /** POSIX single-quote escaping. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
