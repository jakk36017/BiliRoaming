package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.findClassOrNull
import me.iacn.biliroaming.utils.hookAllMethods
import me.iacn.biliroaming.utils.sPrefs

class GameCenterRecommendHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("hidden", false)
            || !sPrefs.getBoolean("purify_game_center_recommend", false)
        ) return

        // 新版我的页面（mineV2 / Compose）
        "com.bilibili.lib.homepage.mine.MenuGroupV2".findClassOrNull(mClassLoader)
            ?.apply {
                hookAllMethods("getMenuItemTips") { null }
                hookAllMethods("setMenuItemTips") { chain ->
                    val args = chain.args.toTypedArray()
                    args[0] = null
                    chain.proceed(args)
                }
            }

        // 旧版我的页面（HomeUserCenterFragment）
        "com.bilibili.lib.homepage.mine.MenuItemTip".findClassOrNull(mClassLoader)
            ?.hookAllMethods("checkValid") { false }
    }
}