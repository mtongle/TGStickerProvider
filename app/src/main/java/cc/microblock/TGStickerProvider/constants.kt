package cc.microblock.TGStickerProvider

const val exposedPath = "/storage/self/primary/Documents/TGStickersExported"
const val realDataPath = "/storage/self/primary/Android/media/com.tencent.mobileqq/TGStickersExported/v1"
const val destDataPath = "$exposedPath/cache/"
const val tgspDataPath = "$exposedPath/mb_TGSPData"
const val stickerDataPath =  "$tgspDataPath/sticker/"
const val syncFlagsPath = "$tgspDataPath/stickerSyncFlags/"
const val nomediaPath = "$exposedPath/.nomedia"
const val nomediaPath2 = "$realDataPath/.nomedia"

/** clients we know how to read; the first installed one wins */
val CLIENT_PACKAGES = listOf(
    "fork.risin42.nagramx",
    "nu.gpu.nagram",
    "nu.gpu.nagramx",
    "xyz.nextalone.nagram",
    "xyz.nextalone.nnngram",
    "top.qwq2333.nullgram",
    "org.telegram.messenger",
    "org.telegram.mdgram",
)
