package me.iacn.biliroaming.hook

import android.view.View
import android.view.ViewTreeObserver
import me.iacn.biliroaming.utils.findClassOrNull
import me.iacn.biliroaming.utils.hookMethod
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.sPrefs
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * 移除"我的"页大会员横幅（不保留卡片占位）。
 * 参考 Innocent_Lab MineVip：新"我的"页（V2/V3 Compose）与旧"我的"页
 * （View 体系）分别处理，两层互不知晓，任一层装上即生效。
 */
class VipSectionHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("hidden", false)
            || !sPrefs.getBoolean("remove_vip_section", false)
        ) return

        runCatching { hookCompose() }.onFailure { Log.e(it) }
        runCatching { hookLegacy() }.onFailure { Log.e(it) }
    }

    /**
     * V2/V3 共用的原生卡片 Compose 门面（MineVipModuleComposeKt 未混淆）：
     * 唯一匹配的静态 void 方法就是大会员卡片的渲染边界，直接跳过它（不留占位）。
     * 不改动账号大会员身份与其它大会员入口。
     */
    private fun hookCompose() {
        val owner = MINE_VIP_COMPOSE_OWNER.findClassOrNull(mClassLoader) ?: return
        val composer =
            "androidx.compose.runtime.Composer".findClassOrNull(mClassLoader) ?: return
        val modifier =
            "androidx.compose.ui.Modifier".findClassOrNull(mClassLoader) ?: return
        val facade = owner.declaredMethods.singleOrNull { method ->
            if (!Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE)
                return@singleOrNull false
            val p = method.parameterTypes.toList()
            (p.size == 4 && p[0] == Int::class.javaPrimitiveType && p[1] == composer &&
                p[2] == modifier && p[3].name.startsWith(MINE_V2_UI_PREFIX)) ||
                (p.size in 4..5 && p[0] == modifier &&
                    p[1].name.startsWith(MINE_V2_UI_PREFIX) && p[2] == composer &&
                    p.drop(3).all { it == Int::class.javaPrimitiveType })
        }?.also { it.isAccessible = true } ?: return
        // 不 proceed 即为整段替换，Composable 不再执行，卡片不占位
        facade.hookMethod { null }
    }

    /**
     * 旧"我的"页：Fragment 持有唯一 MineVipModuleManager、管理器持有唯一
     * ViewBinding、ViewBinding 暴露无参 getRoot——成员名混淆但这条结构链稳定。
     * onResume 后沿链取到卡片根视图置 GONE，并监听布局/附着变化，在异步数据
     * 或主题刷新把卡片重新置可见时立刻压回去；视图 detached 后移除监听。
     */
    private fun hookLegacy() {
        val fragmentClass = MINE_FRAGMENT.findClassOrNull(mClassLoader) ?: return
        val managerClass = MINE_VIP_MANAGER.findClassOrNull(mClassLoader) ?: return
        val onResume = fragmentClass.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.name == "onResume" &&
                it.parameterCount == 0 && it.returnType == Void.TYPE
        }?.also { it.isAccessible = true } ?: return
        val managerField = fragmentClass.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) && managerClass.isAssignableFrom(it.type)
        }?.also { it.isAccessible = true } ?: return
        val bindingField = managerClass.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) &&
                it.type.interfaces.any { face -> face.name == "androidx.viewbinding.ViewBinding" }
        }?.also { it.isAccessible = true } ?: return
        val rootGetter = bindingField.type.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && !it.isBridge && it.name == "getRoot" &&
                it.parameterCount == 0 && View::class.java.isAssignableFrom(it.returnType)
        }?.also { it.isAccessible = true } ?: return

        onResume.hookMethod { chain ->
            chain.proceed()
            val fragment = chain.thisObject ?: return@hookMethod null
            runCatching {
                val manager = managerField.get(fragment) ?: return@runCatching
                val binding = bindingField.get(manager) ?: return@runCatching
                (rootGetter.invoke(binding) as? View)?.let(::keepGone)
            }.onFailure { Log.e(it) }
            null
        }
    }

    private val goneGuards = WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()

    private fun keepGone(root: View) {
        root.visibility = View.GONE
        if (goneGuards.containsKey(root)) return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (root.visibility != View.GONE) root.visibility = View.GONE
        }
        goneGuards[root] = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (root.visibility != View.GONE) root.visibility = View.GONE
            }

            override fun onViewDetachedFromWindow(view: View) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                goneGuards.remove(root)
                root.removeOnAttachStateChangeListener(this)
            }
        })
    }

    companion object {
        private const val MINE_FRAGMENT =
            "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment"
        private const val MINE_VIP_MANAGER =
            "tv.danmaku.bili.ui.main2.mine.modularvip.MineVipModuleManager"
        private const val MINE_VIP_COMPOSE_OWNER =
            "tv.danmaku.bili.ui.main2.mineV2.ui.MineVipModuleComposeKt"
        private const val MINE_V2_UI_PREFIX = "tv.danmaku.bili.ui.main2.mineV2.ui."
    }
}
