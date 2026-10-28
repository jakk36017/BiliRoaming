package me.iacn.biliroaming.hook

import android.content.Context
import android.view.View
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.findClassOrNull
import me.iacn.biliroaming.utils.hookAllMethods
import me.iacn.biliroaming.utils.hookMethod
import me.iacn.biliroaming.utils.sPrefs

class PauseAdHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("hidden", false) || !sPrefs.getBoolean("purify_pause_ad", false)) return

        // 净化 3 秒倒计时栏
        "com.bilibili.ad.adview.videodetail.pausedpage.VDPausedPage".findClassOrNull(mClassLoader)?.let { clazz ->
            clazz.hookMethod(
                "getCountDownView",
                Context::class.java,
                "com.bapis.bilibili.app.viewunite.v1.KPauseBar",
                Float::class.java,
                "kotlin.jvm.functions.Function0",
                "kotlin.jvm.functions.Function0"
            ) { chain ->
                View(chain.args[0] as Context)
            }?.let {
                Log.d("startHook: PauseAd on ${clazz.name}.getCountDownView")
            }
        }

        // 拦截面板展示入口（面板不挂载，onCreateView 不会被调用，无需再拦）
        "com.bilibili.ship.theseus.ugc.ad.DetailAdService".findClassOrNull(mClassLoader)?.let { clazz ->
            val handles = clazz.hookAllMethods("showPanel") { _ -> null }
            if (handles.isNotEmpty()) {
                Log.d("startHook: PauseAd on ${clazz.name}.showPanel, hooked=${handles.size}")
            }
        }
    }
}