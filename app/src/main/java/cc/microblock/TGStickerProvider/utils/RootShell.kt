package cc.microblock.TGStickerProvider.utils

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root access helper.
 *
 * KernelSU's su is not a drop-in clone of Magisk's: on several ksud builds `su -c "cmd"` prints its
 * usage and exits non-zero, so a command that never ran looks like "no root" (and every retry raises
 * another prompt). Feeding the command through stdin - `su`, then `sh script` + `exit` - works on
 * Magisk, KernelSU and APatch alike.
 */
object RootShell {

    data class Result(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
        fun summary(): String =
            if (ok) stdout.trim() else "${stderr.trim().ifEmpty { stdout.trim() }} (exit=$code)"
    }

    @Volatile
    var lastResult: Result? = null
        private set

    /**
     * Where su actually lives differs per root implementation: Magisk keeps it in /debug_ramdisk or
     * /system/bin, KernelSU only ships /data/adb/ksu/bin/su on many devices and does not always
     * create the /system/bin symlink. ProcessBuilder("su", ...) then fails with ENOENT, which used
     * to look like "no root granted".
     */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
        "/system/bin/failsafe/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su",
        "/debug_ramdisk/su", "/data/local/su"
    )

    @Volatile
    private var resolvedSu: String? = null

    @Volatile
    private var cachedAvailable: Boolean? = null

    /** first usable su binary: what PATH offers, then the well-known paths */
    fun suPath(): String? {
        resolvedSu?.let { path -> if (File(path).exists()) return path }
        val script = "command -v su 2>/dev/null; " +
                "for p in " + SU_CANDIDATES.joinToString(" ") + "; do [ -x \"\$p\" ] && echo \"\$p\"; done"
        for (shell in listOf("/system/bin/sh", "sh")) {
            try {
                val process = ProcessBuilder(shell, "-c", script).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                val found = output.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && File(it).exists() }
                if (found != null) {
                    resolvedSu = found
                    return found
                }
            } catch (t: Throwable) {
                L.warn("su probe via $shell failed", t)
            }
        }
        return null
    }

    /** human readable state for the diagnostics dialog */
    fun describeState(): String {
        val su = suPath()
        val existing = SU_CANDIDATES.filter { File(it).exists() }
        return buildString {
            appendLine("su 路径: ${su ?: "未找到"}")
            appendLine("发现的候选: ${existing.ifEmpty { listOf("无") }.joinToString(", ")}")
            appendLine("PATH: ${System.getenv("PATH") ?: ""}")
            appendLine("上次结果: ${lastResult?.summary()?.take(300) ?: "无"}")
        }
    }

    /**
     * Never probes on its own: every `su` invocation can raise a KernelSU prompt, so the UI asks for
     * the cached answer and only [StickerImporter] triggers real work.
     */
    fun isAvailable(force: Boolean = false): Boolean {
        val cached = cachedAvailable
        if (cached != null) return cached
        if (!force) return false
        val result = run("id")
        val available = result.ok && result.stdout.contains("uid=0")
        cachedAvailable = available
        return available
    }

    fun rememberAvailable(available: Boolean) {
        cachedAvailable = available
    }

    /** runs `su -c` when it is supported, falls back to feeding stdin */
    fun run(command: String, timeoutSeconds: Long = 120): Result {
        val su = suPath() ?: return Result(-1, "", "no su binary found").also { lastResult = it }
        val viaDashC = exec(listOf(su, "-c", command), null, timeoutSeconds)
        if (viaDashC.ok || atLeastSomethingRan(viaDashC)) {
            lastResult = viaDashC
            return viaDashC
        }
        val viaStdin = exec(listOf(su), "$command\nexit\n", timeoutSeconds)
        lastResult = viaStdin
        return viaStdin
    }

    /** runs a script file through stdin - what KernelSU/APatch expect */
    fun runScript(script: File, timeoutSeconds: Long = 300): Result {
        val su = suPath() ?: return Result(-1, "", "no su binary found").also { lastResult = it }
        val result = exec(listOf(su), "sh '${script.absolutePath}'\nexit\n", timeoutSeconds)
        lastResult = result
        return result
    }

    /**
     * `su -c` on a ksud that does not know `-c` prints usage and exits non-zero without running
     * anything, so the only signal we can trust is "did the command leave any output".
     */
    private fun atLeastSomethingRan(result: Result): Boolean =
        result.stdout.isNotBlank() && !result.stdout.lowercase().contains("usage")

    private fun exec(argv: List<String>, stdin: String?, timeoutSeconds: Long): Result {
        return try {
            val process = ProcessBuilder(argv).redirectErrorStream(false).start()
            if (stdin != null) {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(stdin)
                    writer.flush()
                }
            } else {
                process.outputStream.close()
            }
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

    /** POSIX single-quote escaping. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
