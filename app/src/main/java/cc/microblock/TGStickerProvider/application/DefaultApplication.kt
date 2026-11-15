package cc.microblock.TGStickerProvider.application

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class DefaultApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        /**
         * 跟随系统夜间模式
         * Follow system night mode
         */
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}
