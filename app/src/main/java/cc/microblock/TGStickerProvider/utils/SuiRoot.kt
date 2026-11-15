package cc.microblock.TGStickerProvider.utils

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Runs commands through Sui / Shizuku.
 *
 * This is the only way an app can read another app's data on this device: the command runs in the
 * Sui server process (uid 0 when Sui is installed as a KernelSU/Magisk module), which lives in the
 * global mount namespace. Spawning `su` from here instead would inherit this app's namespace, where
 * /data/user/0/<client> does not exist at all - even with uid 0.
 */
object SuiRoot {

    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** uid the commands run as: 0 for Sui in root mode, 2000 when it is adb/shell only */
    fun uid(): Int = try {
        if (isRunning()) Shizuku.getUid() else -1
    } catch (t: Throwable) {
        -1
    }

    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            L.warn("cannot request shizuku permission", t)
        }
    }

    fun runScript(script: File, timeoutMs: Long = 180_000L): Result {
        val command = arrayOf("sh", script.absolutePath)
        return try {
            val process = Shizuku.newProcess(command, null, null)
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            val code = if (process.isAlive) {
                process.destroy()
                -1
            } else {
                process.exitValue()
            }
            Result(code, (out + err).trim())
        } catch (t: Throwable) {
            Result(-1, t.toString())
        }
    }
}
