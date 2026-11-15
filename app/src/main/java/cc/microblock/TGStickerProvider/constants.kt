package cc.microblock.TGStickerProvider

const val exposedPath = "/storage/self/primary/Documents/TGStickersExported"
const val realDataPath = "/storage/self/primary/Android/media/com.tencent.mobileqq/TGStickersExported/v1"
const val destDataPath = "$exposedPath/cache/"
const val tgspDataPath = "$exposedPath/mb_TGSPData"
const val stickerDataPath =  "$tgspDataPath/sticker/"
const val syncFlagsPath = "$tgspDataPath/stickerSyncFlags/"
const val nomediaPath = "$exposedPath/.nomedia"
const val nomediaPath2 = "$realDataPath/.nomedia"

/**
 * Handshake with the hook running inside the Telegram client. The hook does nothing at all until
 * this request file appears: parsing sticker blobs means verifying thousands of classes from the
 * module dex, and doing that while the client is still defining classes at startup deadlocks ART's
 * class linker (the client then gets killed with "failed to complete startup").
 */
const val syncRequestPath = "$tgspDataPath/sync_request"
const val syncDonePath = "$tgspDataPath/sync_done"