package me.iacn.biliroaming.hook

import android.content.Context
import android.net.Uri
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.findClassOrNull
import me.iacn.biliroaming.utils.findFieldOrNull
import me.iacn.biliroaming.utils.getObjectField
import me.iacn.biliroaming.utils.getObjectFieldOrNull
import me.iacn.biliroaming.utils.hookMethod
import me.iacn.biliroaming.utils.sPrefs
import me.iacn.biliroaming.utils.setIntField
import me.iacn.biliroaming.utils.setObjectField
import me.iacn.biliroaming.utils.systemContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ShareHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val contentUrlPattern = Regex("""[\s\S]*(https?://(?:bili2233\.cn|b23\.tv)/\S*)$""")

    private fun String.resolveB23URL(): String {
        val conn = URL(this).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        conn.connect()
        if (conn.responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
            return conn.getHeaderField("Location")
        }
        return this
    }

    private fun transformUrl(url: String): String {
        val target = Uri.parse(url)
        val newUrl = target.buildUpon()
        val encodedQuery = target.encodedQuery
        if (encodedQuery != null) {
            val query = encodedQuery.split("&").map {
                it.split("=")
            }.filter {
                it.size == 2
            }.mapNotNull {
                when {
                    it[0] == "p" || it[0] == "t" -> "${it[0]}=${it[1]}"
                    it[0] == "start_progress" -> "start_progress=${it[1]}&t=${it[1].toLong() / 1000}"
                    else -> null
                }
            }.joinToString("&", postfix = "&unique_k=2333")
            newUrl.encodedQuery(query)
        } else {
            newUrl.appendQueryParameter("unique_k", "2333")
        }
        return newUrl.build().toString()
    }

    override fun startHook() {
        val miniProgramEnabled = sPrefs.getBoolean("mini_program", false)
        val timShareEnabled = sPrefs.getBoolean("mini_program_tim", false)
        val purifyShareEnabled = sPrefs.getBoolean("purify_share", false)
        if (!miniProgramEnabled && !timShareEnabled && !purifyShareEnabled) return
        Log.d("startHook: ShareHook")
        instance.shareClickResultClass?.apply {
            if (purifyShareEnabled) {
                hookMethod("getLink") { chain ->
                    val result = chain.proceed()
                    (result as? String)?.takeIf {
                        it.startsWith("https://bili2233.cn") || it.startsWith("http://bili2233.cn") || it.startsWith("https://b23.tv") || it.startsWith("http://b23.tv")
                    }?.let {
                        val targetUrl = Uri.parse(it).buildUpon().query("").build().toString()
                        targetUrl.resolveB23URL().also { r -> chain.thisObject.setObjectField("link", r) }
                    } ?: result
                }
                hookMethod("getContent") { chain ->
                    val result = chain.proceed()
                    val content = result as? String
                    content?.let {
                        contentUrlPattern.matchEntire(it)?.groups?.get(1)?.value
                    }?.let { contentUrl ->
                        val resolvedUrl = (chain.thisObject.getObjectField("link")?.let { it as String } ?: contentUrl)
                            .let {
                                if (it.startsWith("https://bili2233.cn") || it.startsWith("http://bili2233.cn") || it.startsWith("https://b23.tv") || it.startsWith("http://b23.tv"))
                                    it.resolveB23URL()
                                else it
                            }
                        content.replace(contentUrl, transformUrl(resolvedUrl)).also { r ->
                            chain.thisObject.setObjectField("content", r)
                        }
                    } ?: result
                }
            }
            if (!miniProgramEnabled) return@apply
            // ShareMode Definition
            // 1: PARAMS_TYPE_TEXT
            // 2: PARAMS_TYPE_AUDIO
            // 4: PARAMS_TYPE_VIDEO
            // 5: PARAMS_TYPE_IMAGE
            // 6 / 7: PARAMS_TYPE_MIN_PROGRAM
            // 21: PARAMS_TYPE_PURE_IMAGE
            // Others: PARAMS_TYPE_WEB
            hookMethod("getShareMode") { chain ->
                val result = chain.proceed()
                val mode = (result as? Number)?.toInt() ?: return@hookMethod result
                if (mode !in MINI_PROGRAM_SHARE_MODES) return@hookMethod result
                // 小程序模式降级为普通图文链接模式，并写回同名字段：
                // 分享面板会多次读取同一个结果对象，只改本次返回值会让
                // 复制链接和真正发出去的内容出现不一致。
                chain.thisObject?.runCatching {
                    val shareModeType = javaClass.findFieldOrNull("shareMode")?.type
                    if (shareModeType == Int::class.javaPrimitiveType) {
                        setIntField("shareMode", PLAIN_SHARE_MODE)
                    } else {
                        setObjectField("shareMode", PLAIN_SHARE_MODE)
                    }
                    restoreTitleFromContent()
                }
                PLAIN_SHARE_MODE
            }
        }
    }

    override fun lateInitHook() {
        if (!sPrefs.getBoolean("mini_program_tim", false)) return
        runCatching {
            // 分享面板只把 mobileqq / qqlite 当作已安装的 QQ，只装 TIM 时入口会被隐藏
            "com.bilibili.lib.sharewrapper.selector.SharePlatform".findClassOrNull(mClassLoader)
                ?.hookMethod("isQQInstalled", Context::class.java) { chain ->
                    (chain.proceed() as? Boolean ?: false) || isTimInstalled()
                }
        }.onFailure { Log.e(it) }
    }

    private fun isTimInstalled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        systemContext.packageManager.getPackageInfo("com.tencent.tim", 0)
    }.isSuccess

    /**
     * 小程序模式下标题固定是应用名，真正的标题在正文里。
     * 只有标题为空或就是应用名时才回填，避免覆盖宿主已经写好的正常标题。
     */
    private fun Any.restoreTitleFromContent() {
        val title = getObjectFieldOrNull("title") as? String
        if (title != null && title.isNotBlank() &&
            title.trim().lowercase(Locale.ROOT) !in APP_NAME_TITLES
        ) {
            return
        }
        val content = (getObjectFieldOrNull("content") as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        setObjectField("title", content.take(MAX_TITLE_LENGTH))
    }

    companion object {
        /** 宿主分享模式：6/7 为小程序，0 走普通图文链接。 */
        private val MINI_PROGRAM_SHARE_MODES = setOf(6, 7)
        private const val PLAIN_SHARE_MODE = 0
        private const val MAX_TITLE_LENGTH = 120

        /** 小程序占位标题；只用于判断“这不是真标题”，不参与任何文案生成。 */
        private val APP_NAME_TITLES = setOf("哔哩哔哩", "嗶哩嗶哩", "bilibili")
    }
}
