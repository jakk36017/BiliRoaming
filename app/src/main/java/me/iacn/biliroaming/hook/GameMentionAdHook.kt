package me.iacn.biliroaming.hook

import android.content.Context
import android.view.View
import io.github.libxposed.api.XposedInterface
import me.iacn.biliroaming.utils.HookCallback
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.findClassOrNull
import me.iacn.biliroaming.utils.hookAllMethods
import me.iacn.biliroaming.utils.hookMethod
import me.iacn.biliroaming.utils.sPrefs
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections

/**
 * 净化视频提及的游戏广告。
 * 移植自 Bilibili_Innocent_Lab 的 GamePromotionFeatureInstaller
 * （隐藏视频提及区域中的游戏广告），数据层与渲染层双路拦截。
 */
class GameMentionAdHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("hidden", false) || !sPrefs.getBoolean("purify_game_mention_ad", false)) return

        // 数据层：清空提及标题与卡片列表
        hookFirstMethod(VIDEO_MENTIONS_CLASS, "getTitle") { "" }
        hookFirstMethod(MENTION_CLASS, "getTitle") { "" }
        hookFirstMethod(MENTION_CLASS, "getCardsList") { Collections.emptyList<Any>() }

        // 拦截视频提及 section 工厂方法，直接构造空 section
        MENTION_FACTORY_CLASS.findClassOrNull(mClassLoader)
            ?.declaredMethods?.firstOrNull { it.name == MENTION_FACTORY_METHOD }?.let { method ->
                method.isAccessible = true
                method.hookMethod {
                    try {
                        Log.d("GameMentionAd: 已拦截视频提及 section 工厂方法 $MENTION_FACTORY_CLASS.$MENTION_FACTORY_METHOD")
                        mentionedSectionConstructor().newInstance()
                    } catch (e: Throwable) {
                        Log.e("GameMentionAd: 空 section 构造失败: $e")
                        null
                    }
                }
            }

        hookFirstMethod(GAME_CARD_DATA_CLASS, "hidden") { true }
        hookFirstMethod(GAME_FEED_ITEM_CLASS, "getBottomBenefitTipGroup") { 0 }
        hookFirstMethod(GAME_FEED_ITEM_CLASS, "getShowBenefitWidget") { false }

        // 渲染层：视频提及游戏卡 createViewEntry 换成空 UI 组件
        MENTIONED_COMPONENT_CLASS.findClassOrNull(mClassLoader)
            ?.declaredMethods?.firstOrNull { it.name == CREATE_VIEW_ENTRY }?.let { method ->
                method.isAccessible = true
                method.hookMethod(replaceViewEntry())
                Log.d("GameMentionAd: 已 hook ${MENTIONED_COMPONENT_CLASS}.$CREATE_VIEW_ENTRY")
            }

        // 渲染层：视频提及 header（清空标题 + 替换 view entry）
        MENTIONED_HEADER_COMPONENT_CLASS.findClassOrNull(mClassLoader)?.let { headerClass ->
            headerClass.declaredConstructors.firstOrNull { it.parameterCount == 1 }?.let { ctor ->
                ctor.isAccessible = true
                ctor.hookMethod { chain ->
                    val args = chain.args.toTypedArray()
                    args[0] = ""
                    chain.proceed(args)
                }
            }
            headerClass.hookAllMethods(CREATE_VIEW_ENTRY, replaceViewEntry())
        }

        // 收益挂件与 section 访问器：跳过原方法并返回安全零值
        hookFirstMethod(BENEFIT_WIDGET_CLASS, "I") { chain -> defaultReturnValue(chain) }
        hookFirstMethod(MENTIONED_SECTION_CLASS, "getCards") { chain -> defaultReturnValue(chain) }
        hookFirstMethod(MENTIONED_SECTION_CLASS, "getHeight") { chain -> defaultReturnValue(chain) }
        hookFirstMethod(MENTIONED_SECTION_CLASS, "getHeader") { chain -> defaultReturnValue(chain) }
        hookFirstMethod(MENTIONED_SECTION_CLASS, "getFoldCount") { chain -> defaultReturnValue(chain) }
    }

    /** hook 指定类中首个名为 [methodName] 的声明方法。 */
    private fun hookFirstMethod(className: String, methodName: String, callback: HookCallback) {
        val clazz = className.findClassOrNull(mClassLoader) ?: return
        val method = clazz.declaredMethods.firstOrNull { it.name == methodName } ?: return
        method.isAccessible = true
        method.hookMethod(callback)
    }

    /** createViewEntry 替换逻辑：返回空 UI 组件，取不到 Context 时放行原方法。 */
    private fun replaceViewEntry(): HookCallback = { chain ->
        val context = chain.args.firstOrNull() as? Context
        if (context == null) {
            chain.proceed()
        } else {
            try {
                uiComponentConstructor(context).newInstance(View(context))
            } catch (e: Throwable) {
                Log.e("GameMentionAd: createViewEntry 拦截失败: $e")
                null
            }
        }
    }

    /** 按返回类型给出安全零值（对齐原 intercept() 语义）。 */
    private fun defaultReturnValue(chain: XposedInterface.Chain): Any? {
        val type = (chain.executable as? Method)?.returnType ?: return null
        return when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Short.TYPE -> 0.toShort()
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }

    private fun mentionedSectionConstructor(): Constructor<*> =
        mentionedSectionCtor ?: synchronized(this) {
            mentionedSectionCtor ?: run {
                val owner = MENTIONED_SECTION_CLASS.findClassOrNull(mClassLoader)
                    ?: throw ClassNotFoundException(MENTIONED_SECTION_CLASS)
                owner.getDeclaredConstructor().also {
                    it.isAccessible = true
                    mentionedSectionCtor = it
                }
            }
        }

    private fun uiComponentConstructor(context: Context): Constructor<*> =
        uiComponentCtor ?: synchronized(this) {
            uiComponentCtor ?: run {
                val owner = UI_COMPONENT_CLASS.findClassOrNull(context.classLoader)
                    ?: throw ClassNotFoundException(UI_COMPONENT_CLASS)
                owner.getDeclaredConstructor(View::class.java).also {
                    it.isAccessible = true
                    uiComponentCtor = it
                }
            }
        }

    companion object {
        private const val MENTIONED_COMPONENT_CLASS =
            "com.bilibili.biligame.videocard.GameVideoMentionedComponent"
        private const val UI_COMPONENT_CLASS = "com.bilibili.app.gemini.ui.UIComponent\$b"
        private const val MENTIONED_HEADER_COMPONENT_CLASS =
            "com.bilibili.biligame.videocard.GameVideoMentionedHeaderComponent"
        private const val MENTION_FACTORY_CLASS = "yx3.a"
        private const val MENTION_FACTORY_METHOD = "c"
        private const val MENTION_CLASS = "com.bapis.bilibili.app.viewunite.common.Mention"
        private const val VIDEO_MENTIONS_CLASS =
            "com.bapis.bilibili.app.viewunite.common.VideoMentions"
        private const val GAME_CARD_DATA_CLASS =
            "com.bilibili.biligame.videocard.GameVideoMentionCardData"
        private const val GAME_FEED_ITEM_CLASS =
            "com.bilibili.biligame.ui.feed.bean.GameFeedItem"
        private const val BENEFIT_WIDGET_CLASS =
            "com.bilibili.biligame.ui.feed.widget.bottomtip.BottomGameBenefitWidgetKt"
        private const val MENTIONED_SECTION_CLASS =
            "com.bilibili.playerbizcommonv2.videomentioned.MentionedSectionItem"
        private const val CREATE_VIEW_ENTRY = "createViewEntry"

        @Volatile
        private var uiComponentCtor: Constructor<*>? = null

        @Volatile
        private var mentionedSectionCtor: Constructor<*>? = null
    }
}
