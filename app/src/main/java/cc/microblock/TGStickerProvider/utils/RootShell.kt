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

    @Volatile
    private var cachedAvailable: Boolean? = null

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
        val viaDashC = exec(listOf("su", "-c", command), null, timeoutSeconds)
        if (viaDashC.ok || atLeastSomethingRan(viaDashC)) {
            lastResult = viaDashC
            return viaDashC
        }
        val viaStdin = exec(listOf("su"), "$command\nexit\n", timeoutSeconds)
        lastResult = viaStdin
        return viaStdin
    }

    /** runs a script file through stdin - what KernelSU/APatch expect */
    fun runScript(script: File, timeoutSeconds: Long = 300): Result {
        val result = exec(listOf("su"), "sh '${script.absolutePath}'\nexit\n", timeoutSeconds)
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
