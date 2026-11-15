package cc.microblock.TGStickerProvider.utils

import android.util.Log

/**
 * Minimal logger. The module used to log through YukiHookAPI's YLog; the framework is gone now
 * (see HookEntry), so everything goes to logcat under one tag.
 */
object L {
    private const val TAG = "TGStickerProvider"

    fun debug(msg: String) {
        Log.d(TAG, msg)
    }

    fun info(msg: String) {
        Log.i(TAG, msg)
    }

    fun warn(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
    }

    fun error(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
    }
}
