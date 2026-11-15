package cc.microblock.TGStickerProvider.utils

import java.io.File


object CachePathHelper {
    /**
     * Cache root of the client. Telegram forks keep attachment caches in
     * `Android/data/<pkg>/cache` (AndroidUtilities.getCacheDir() = getExternalCacheDirs()[0]).
     * NekoX-family forks additionally have a `cache_path` preference in `nkmrcfg`; it may only be
     * honoured when it is actually set, otherwise the empty default resolves to "/caches".
     *
     * The preference is read straight out of the XML file: the hook runs before the client has an
     * Application or a Context, and pulling in Context handling for one string is not worth it.
     */
    @JvmStatic
    fun getCachePath(packageName: String): String {
        val defaultCachePath = "/storage/self/primary/Android/data/$packageName/cache"
        val prefFile = File("/data/data/$packageName/shared_prefs/nkmrcfg.xml")
        if (!prefFile.exists()) return defaultCachePath

        val custom = try {
            Regex("name=\"cache_path\"[^>]*>([^<]*)<")
                .find(prefFile.readText())
                ?.groupValues
                ?.getOrNull(1)
        } catch (t: Throwable) {
            null
        }
        return if (custom.isNullOrEmpty()) defaultCachePath else "$custom/caches"
    }
}
