@file:Suppress("MemberVisibilityCanBePrivate", "UNCHECKED_CAST")

package cc.microblock.TGStickerProvider.ui.activity.base

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.viewbinding.ViewBinding
import cc.microblock.TGStickerProvider.R
import cc.microblock.TGStickerProvider.utils.factory.isNotSystemInDarkMode
import cc.microblock.TGStickerProvider.*
import java.lang.reflect.ParameterizedType

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    /**
     * Get the binding layout object
     *
     * 获取绑定布局对象
     */
    lateinit var binding: VB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = inflateBinding()
        setContentView(binding.root)
        /**
         * Hide Activity title bar
         * 隐藏系统的标题栏
         */
        supportActionBar?.hide()
        /**
         * Init immersive status bar
         * 初始化沉浸状态栏
         */
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isNotSystemInDarkMode
            isAppearanceLightNavigationBars = isNotSystemInDarkMode
        }
        ResourcesCompat.getColor(resources, R.color.colorThemeBackground, null).also {
            window?.statusBarColor = it
            window?.navigationBarColor = it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window?.navigationBarDividerColor = it
        }
        /**
         * Init children
         *
         * 装载子类
         */
        onCreate()
    }

    /**
     * Calls the generated `inflate(LayoutInflater)` of the `ViewBinding` subclass this activity was
     * parameterised with. (Previously done through YukiHookAPI's reflection DSL, which is gone.)
     */
    private fun inflateBinding(): VB {
        val bindingClass = (this::class.java.genericSuperclass as ParameterizedType)
            .actualTypeArguments[0] as Class<*>
        return bindingClass.getMethod("inflate", LayoutInflater::class.java)
            .invoke(null, layoutInflater) as VB
    }

    /**
     * Callback [onCreate] method
     *
     * 回调 [onCreate] 方法
     */
    abstract fun onCreate()
}
