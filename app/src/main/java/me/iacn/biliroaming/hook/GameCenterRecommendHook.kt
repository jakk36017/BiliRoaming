package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.findClassOrNull
import me.iacn.biliroaming.utils.hookAllMethods
import me.iacn.biliroaming.utils.sPrefs

class GameCenterRecommendHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("hidden", false)
            || !sPrefs.getBoolean("purify_game_center_recommend", false)
        ) return

        // 新版我的页面（mineV2 / Compose）：menuItemTips 只经 getter 参与轮播
        // 渲染，恒返回 null 即固定「查看更多」；写入走构造器/Parcelable，无 setter
        // 调用点，且 getter 与 setter 读写同一字段，钩 setter 纯属冗余
        "com.bilibili.lib.homepage.mine.MenuGroupV2".findClassOrNull(mClassLoader)
            ?.hookAllMethods("getMenuItemTips") { null }

        // 旧版我的页面（HomeUserCenterFragment）
        "com.bilibili.lib.homepage.mine.MenuItemTip".findClassOrNull(mClassLoader)
            ?.hookAllMethods("checkValid") { false }
    }
}