package me.iacn.biliroaming.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Environment
import android.graphics.Color
import android.graphics.Typeface
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*

import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL

class CopyHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        private val DYNAMIC_COPYABLE_IDS = arrayOf(
            "dy_card_text",
            "dy_opus_paragraph_desc",
            "dy_opus_paragraph_title",
            "dy_opus_copy_right_id",
            "dy_opus_paragraph_text",
        )
    }

    private val enhanceLongClickCopy = sPrefs.getBoolean("comment_copy_enhance", false)

    // 视频详情 ViewReply 内存缓存（供简介长按弹窗构建内容，零网络）
    @Volatile
    private var cachedViewReply: WeakReference<Any>? = null

    @Volatile
    private var lastDescCopyPressAt = 0L

    private fun cacheViewReply(reply: Any?) {
        val n = reply?.javaClass?.name ?: return
        if (n == "com.bapis.bilibili.app.view.v1.ViewReply" ||
            n == "com.bapis.bilibili.app.viewunite.v1.ViewReply"
        ) cachedViewReply = WeakReference(reply)
    }

    override fun startHook() {
        // 启动时 hook Moss executeView/view 全部重载，缓存视频详情 ViewReply（同步返回 + 异步 handler 两种形态）
        runCatching {
            val viewFuncName = if (instance.useNewMossFunc) "executeView" else "view"
            listOfNotNull(instance.viewMossClass, instance.viewUniteMossClass).forEach { moss ->
                moss.hookAllMethods(viewFuncName) { chain ->
                    val handlerIdx = chain.args.indexOfFirst {
                        instance.mossResponseHandlerClass?.isInstance(it) == true
                    }
                    if (handlerIdx >= 0) {
                        val args = chain.args.toTypedArray()
                        args[handlerIdx] = args[handlerIdx]!!.mossResponseHandlerProxy { reply ->
                            cacheViewReply(reply)
                        }
                        chain.proceed(args)
                    } else {
                        chain.proceed()?.also { cacheViewReply(it) }
                    }
                }
            }
        }.onFailure { Log.e("CopyHook: view reply cache hook failed: $it") }

        if (!sPrefs.getBoolean("comment_copy", false)) return
        instance.descCopyView().zip(instance.descCopy()).forEach { p ->
            val clazz = p.first ?: return@forEach
            val method = p.second ?: return@forEach
            clazz.hookMethod(
                method,
                View::class.java,
                ClickableSpan::class.java
            ) { chain ->
                if (!enhanceLongClickCopy) return@hookMethod Unit

                chain.thisObject!!.getFirstFieldByExactTypeOrNull<SpannableStringBuilder>()?.let {
                    val view = chain.args[0] as View
                    showCopyDialog(view.context, it)
                } ?: (chain.args[0] as? TextView)?.let { tv ->
                    showCopyDialog(tv.context, tv.text)
                }
            }
        }

        val commentCopyHook = fun(chain: XposedInterface.Chain, idName: String): Any? {
            if (!enhanceLongClickCopy) return true
            if (chain.args[0] is FrameLayout) return chain.proceed()
            (chain.args[0] as? View)?.findViewById<View>(getId(idName))?.let {
                if (instance.commentSpanTextViewClass?.isInstance(it) == true ||
                    instance.commentSpanEllipsisTextViewClass?.isInstance(it) == true
                ) it else null
            }?.let { view ->
                view.getFirstFieldByExactTypeOrNull<CharSequence>()?.also { text ->
                    showCopyDialog(view.context, text)
                }
            } ?: Log.toast("找不到评论内容", true)
            return true
        }
        instance.commentCopyClass?.hookMethod("onLongClick", View::class.java) { chain ->
            commentCopyHook(chain, "message")
        }
        instance.commentCopyNewClass?.hookMethod("onLongClick", View::class.java) { chain ->
            commentCopyHook(chain, "comment_message")
        }

        instance.comment3CopyClass?.let { c ->
            instance.comment3Copy()?.let { m ->
                instance.comment3ViewIndex().let { i ->
                    c.hookAllMethods(m) { chain ->
                        if (!enhanceLongClickCopy) return@hookAllMethods true
                        val view = chain.args[i] as View
                        view.getFirstFieldByExactTypeOrNull<CharSequence>()?.also { text ->
                            showCopyDialog(view.context, text)
                        }
                        return@hookAllMethods true
                    }
                }
            }
        }

        // === 动态列表页：hook OnLongClickListener（照 BiliRoamingX 做法） ===
        instance.dynamicDescHolderListeners().forEach { c ->
            c?.hookMethod("onLongClick", View::class.java) { chain ->
                if (!enhanceLongClickCopy) return@hookMethod true
                val itemView = chain.args[0] as? View
                DYNAMIC_COPYABLE_IDS.asSequence().firstNotNullOfOrNull { n ->
                    getId(n).takeIf { it != 0 }?.let { itemView?.findViewById<TextView>(it) }
                }?.let { v ->
                    val text = if (instance.ellipsizingTextViewClass?.isInstance(v) == true) {
                        v.getFirstFieldByExactTypeOrNull()
                    } else v.text
                    if (text != null) {
                        showCopyDialog(v.context, text)
                        return@hookMethod true
                    }
                }
                chain.proceed()
            }
        }

        // === 动态：入口拦截 LongPressCopyGestureKt$longPressCopy$1$1.invokeSuspend() ===
        runCatching {
            val gestureClass = "kntr.app.following.card.utils.LongPressCopyGestureKt\$longPressCopy\$1\$1"
                .findClassOrNull(mClassLoader)
            val invokeSuspend = gestureClass?.declaredMethods?.firstOrNull {
                it.name == "invokeSuspend" && it.parameterTypes.size == 1
            }
            if (invokeSuspend == null) {
                Log.e("CopyHook[Entry]: LongPressCopyGestureKt\$longPressCopy\$1\$1.invokeSuspend not found")
                return@runCatching
            }
            invokeSuspend.isAccessible = true
            invokeSuspend.hookMethod { chain ->
                val self = chain.thisObject ?: return@hookMethod chain.proceed()
                val onCopyLambda = self.javaClass.declaredFields
                    .firstOrNull { it.name == "\$onCopy" }
                    ?.apply { isAccessible = true }
                    ?.get(self)
                onCopyLambda?.javaClass?.declaredMethods
                    ?.firstOrNull { it.name == "invoke" && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }
                    ?.hookMethod { lambdaChain ->
                        val lambdaSelf = lambdaChain.thisObject
                        val text = lambdaSelf?.javaClass?.declaredFields
                            ?.firstNotNullOfOrNull { f ->
                                if (f.type != String::class.java || Modifier.isStatic(f.modifiers)) return@firstNotNullOfOrNull null
                                runCatching {
                                    f.isAccessible = true
                                    f.get(lambdaSelf) as? String
                                }.getOrNull()?.takeIf { it.isNotBlank() }
                            }
                            ?: lambdaSelf?.callMethodOrNullAs<String>("a")
                        val activity = "com.bilibili.base.BiliContext".findClassOrNull(mClassLoader)
                            ?.callStaticMethodOrNull("topActivity") as? Activity
                        if (!text.isNullOrBlank() && activity != null) {
                            showCopyDialog(activity, text)
                            return@hookMethod null
                        }
                        lambdaChain.proceed()
                    }
                chain.proceed()
            }
        }.onFailure { Log.e("CopyHook[Entry] hook failed: ${it.message}") }

        // === 私信：ClipboardManager 作为主入口 ===
        runCatching {
            val clipboardClass = android.content.ClipboardManager::class.java
            val writeMethods = clipboardClass.declaredMethods
                .filter { it.name == "setPrimaryClip" || it.name == "setText" }
            writeMethods.forEach { method ->
                method.isAccessible = true
                method.hookMethod { chain ->
                    val stackFrames = Thread.currentThread().stackTrace
                        .asSequence()
                        .map { "${it.className}.${it.methodName}" }
                        .filterNot {
                            it.startsWith("io.github.libxposed") || it.startsWith("me.iacn.biliroaming") ||
                                it.startsWith("biliroaming.") || it.startsWith("dalvik.system.") ||
                                it.startsWith("kotlin.") || it.startsWith("kotlinx.") || it.startsWith("java.") ||
                                it.startsWith("android.content.ClipboardManager")
                        }
                        .toList()
                    val entryClass = stackFrames
                        .firstOrNull {
                            it.startsWith("com.bilibili") || it.startsWith("kntr.") || it.startsWith("tv.danmaku.")
                        }?.substringBeforeLast('.').orEmpty()

                    val isIM = entryClass.startsWith("com.bilibili.bplus.im") ||
                        entryClass.startsWith("com.bilibili.bplus.privateletter") ||
                        entryClass.startsWith("kntr.app.im") ||
                        entryClass.startsWith("kntr.app.message") ||
                        entryClass.startsWith("kntr.common.im") ||
                        entryClass.startsWith("kntr.common.message") ||
                        entryClass.startsWith("kntr.common.chat") ||
                        entryClass.contains("ChatBubble")

                    val isCommentOrDesc = entryClass.startsWith("kntr.common.comment") ||
                        entryClass.contains("UgcHeadlineService") ||
                        entryClass.contains("CommentMoreMenu") ||
                        entryClass.contains("CommentCardAllClick")

                    // 动态列表页长按复制：View$OnLongClickListener.onLongClick 里直接 setPrimaryClip。
                    // 新版类名是混淆短名（如 SS.u.onLongClick），无法按包名匹配，
                    // 用调用栈模式识别：onLongClick -> performLongClick -> Handler 消息循环
                    val isDynListLongClick = stackFrames.any {
                        it.endsWith(".onLongClick") && !it.startsWith("android.view.View")
                    } && stackFrames.any { it.startsWith("android.view.View.performLongClick") }

                    if ((isIM && !isCommentOrDesc) || isDynListLongClick) {
                        val arg0 = chain.args.getOrNull(0)
                        val text: String? = when (arg0) {
                            is android.content.ClipData -> (0 until arg0.itemCount).joinToString("\n") { i ->
                                arg0.getItemAt(i).coerceToText(currentContext)?.toString().orEmpty()
                            }
                            is CharSequence -> arg0.toString()
                            else -> null
                        }
                        if (!text.isNullOrEmpty()) {
                            val activity = "com.bilibili.base.BiliContext".findClassOrNull(mClassLoader)
                                ?.callStaticMethodOrNull("topActivity") as? Activity
                            if (activity != null) {
                                showCopyDialog(activity, text)
                                return@hookMethod null
                            }
                        }
                    }
                    chain.proceed()
                }
            }
        }.onFailure { Log.e("CopyHook[IM] hook failed: ${it.message}") }

        // === 动态列表页：吞掉长按复制后的「复制成功」toast ===
        // 剪贴板写入已在上面拦截，但 SS.u.onLongClick 会继续走 ToastHelper.showToastShort("复制成功")。
        // ToastHelper 非混淆类名稳定；双重条件（文本 + onLongClick 调用栈）避免误伤其他场景的同名 toast。
        runCatching {
            val toastHelper = "com.bilibili.droid.ToastHelper".findClassOrNull(mClassLoader)
                ?: return@runCatching
            listOf("showToastShort", "showToastLong", "showToast").forEach { name ->
                toastHelper.hookAllMethods(name) { chain ->
                    val str = chain.args.filterIsInstance<String>().firstOrNull()
                    if (str != "复制成功") return@hookAllMethods chain.proceed()
                    val frames = Thread.currentThread().stackTrace
                    val fromLongClick = frames.any {
                        it.className != "android.view.View" && it.methodName == "onLongClick"
                    } || frames.any { it.className == "android.view.View" && it.methodName == "performLongClick" }
                    if (fromLongClick) null else chain.proceed()
                }
            }
        }.onFailure { Log.e("CopyHook toast hook failed: ${it.message}") }

        // === 简介长按自由复制：hook 全部 descCopyPress 目标，拦截剪贴板写入 ===
        runCatching {
            val targets = instance.descCopyPressList()
            targets.forEach { (c, m) ->
                if (c == null || m == null) return@forEach
                c.hookAllMethods(m) { chain ->
                    // 拦截语义：comment_copy 开 + enhance 关 -> 只拦截不弹窗
                    if (!enhanceLongClickCopy) return@hookAllMethods null
                    // 800ms 防抖：一次长按可能接连命中多个匹配方法
                    val now = System.currentTimeMillis()
                    if (now - lastDescCopyPressAt < 800) return@hookAllMethods null
                    // 文本取 args 中的 String 参数（兼容 (String, boolean) 与 (boolean, String)）
                    val text = chain.args.filterIsInstance<String>().firstOrNull()
                        ?: return@hookAllMethods null
                    val activity = "com.bilibili.base.BiliContext".findClassOrNull(mClassLoader)
                        ?.callStaticMethodOrNull("topActivity") as? Activity
                        ?: return@hookAllMethods null
                    lastDescCopyPressAt = now
                    showDescCopyDialog(activity, text)
                    null // 不调 proceed()，拦截原剪贴板写入
                }
            }
        }.onFailure { Log.e("descCopyPress hook failed: $it") }

        // === 评论长按拦截：仅拦截 MoreCardInteraction(fromLongPress=true) ===
        runCatching {
            val c = instance.commentMoreMenuInteractClass
            val m = instance.commentMoreMenuInteract()
            if (c == null || m == null) {
                Log.e("commentMoreMenuInteract: class=$c method=$m")
                return@runCatching
            }
            c.hookAllMethods(m) { chain ->
                val arg = chain.args.getOrNull(0) ?: return@hookAllMethods chain.proceed()
                val s = arg.toString()
                if (!s.startsWith("MoreCardInteraction(cardId=") ||
                    !s.contains(", fromLongPress=true")
                ) return@hookAllMethods chain.proceed()
                if (!enhanceLongClickCopy) return@hookAllMethods true
                val text = extractRawTextFromInteraction(arg)
                val activity = "com.bilibili.base.BiliContext".findClassOrNull(mClassLoader)
                    ?.callStaticMethodOrNull("topActivity") as? Activity
                if (!text.isNullOrEmpty() && activity != null) {
                    showCopyDialog(activity, text)
                    return@hookAllMethods true
                }
                chain.proceed()
            }
        }.onFailure { Log.e("commentMoreMenuInteract hook failed: $it") }

    }

    // 反射取 MoreCardInteraction 第 3 个 String 字段（authorName、presetReplyText、rawText）
    private fun extractRawTextFromInteraction(interaction: Any): String? {
        val f = interaction.javaClass.declaredFields
            .filter { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }
            .getOrNull(2) ?: return null
        return runCatchingOrNull {
            f.isAccessible = true
            f.get(interaction) as? String
        }
    }

    private fun showDescCopyDialog(activity: Activity, descText: String) {
        val reply = cachedViewReply?.get()
        val message = if (reply != null) {
            buildReplyDescMessage(reply, descText)
        } else SpannableStringBuilder(descText)
        showDescDialog(activity, message)
    }

    // 从缓存的 ViewReply 构建弹窗内容（对齐 BiliRoamingX CopyEnhancePatch.onCopyDesc）
    // 展示顺序：标题 → AV号 / BV号 → 封面（点我保存）→ 简介 → BGM → 标签（紧凑无空行，无 AI 总结）
    private fun buildReplyDescMessage(reply: Any, fallbackDesc: String): CharSequence {
        fun Any?.str(method: String) =
            this?.callMethodOrNullAs<String>(method).orEmpty()

        fun bgmText(list: List<Any>?) = list.orEmpty().joinToString("\n") { m ->
            val t = m.str("getTitle")
            val a = m.str("getAuthor")
            if (a.isEmpty()) t else "$t - $a"
        }.trim()

        val built = runCatchingOrNull {
            var title = ""
            var aid = 0L
            var bvid = ""
            var cover = ""
            var desc = ""
            var bgm = ""
            var tags = ""
            if (reply.javaClass.name.contains("viewunite")) {
                // 新版 com.bapis.bilibili.app.viewunite.v1.ViewReply
                val introTab = reply.callMethodOrNull("getTab")
                    ?.callMethodOrNullAs<List<Any>>("getTabModuleList")
                    ?.firstOrNull { it.callMethodOrNullAs<Boolean>("hasIntroduction") == true }
                    ?.callMethodOrNull("getIntroduction")
                val modules = introTab?.callMethodOrNullAs<List<Any>>("getModulesList").orEmpty()
                val headline = modules
                    .firstOrNull { it.callMethodOrNullAs<Boolean>("hasHeadLine") == true }
                    ?.callMethodOrNull("getHeadLine")
                val intro = modules
                    .firstOrNull { it.callMethodOrNullAs<Boolean>("hasUgcIntroduction") == true }
                    ?.callMethodOrNull("getUgcIntroduction")
                if (headline == null || intro == null) return@runCatchingOrNull null
                title = headline.str("getContent")
                val arc = reply.callMethodOrNull("getArc")
                aid = arc?.callMethodOrNullAs<Long>("getAid") ?: 0L
                bvid = arc.str("getBvid")
                cover = arc.str("getCover")
                desc = intro.callMethodOrNullAs<List<Any>>("getDescList")
                    ?.joinToString("") { d ->
                        val text = d.str("getText")
                        val type = d.callMethodOrNullAs<Enum<*>>("getType")?.name
                        if (type == "DescTypeAt") "@$text" else text
                    }.orEmpty()
                bgm = bgmText(intro.callMethodOrNullAs("getBgmList"))
                tags = intro.callMethodOrNullAs<List<Any>>("getTagsList")
                    ?.joinToString(" ") { it.str("getName") }.orEmpty()
            } else {
                // 旧版 com.bapis.bilibili.app.view.v1.ViewReply
                val arc = reply.callMethodOrNull("getArc") ?: return@runCatchingOrNull null
                title = arc.str("getTitle")
                aid = arc.callMethodOrNullAs<Long>("getAid") ?: 0L
                bvid = reply.str("getBvid")
                cover = arc.str("getPic")
                desc = arc.str("getDesc")
                bgm = bgmText(reply.callMethodOrNullAs("getBgmList"))
                tags = reply.callMethodOrNullAs<List<Any>>("getDescTagList")
                    ?.joinToString(" ") { it.str("getName") }.orEmpty()
            }
            val sb = SpannableStringBuilder()
            // 标签加粗，冒号后内容不加粗；分区间隔一行
            var firstSection = true
            fun newSection() {
                if (firstSection) firstSection = false else sb.append("\n\n")
            }
            fun appendLabel(label: String) {
                val s = sb.length
                sb.append(label)
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (title.isNotBlank()) {
                newSection()
                appendLabel("标题：")
                sb.append(title)
            }
            if (aid != 0L || bvid.isNotBlank()) {
                newSection()
                if (aid != 0L) {
                    appendLabel("AV号：")
                    sb.append("AV$aid")
                }
                if (bvid.isNotBlank()) {
                    if (aid != 0L) sb.append("\n")
                    appendLabel("BV号：")
                    sb.append(bvid)
                }
            }
            if (cover.isNotBlank()) {
                newSection()
                appendLabel("封面：")
                val start = sb.length
                sb.append("点我保存")
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        // AlertDialog 自定义主题下 TextView 的 context 是 ContextThemeWrapper，
                        // 直接 as? Activity 会是 null，需要逐层解包
                        var ctx: Context? = widget.context
                        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
                        (ctx as? Activity)?.let { saveCoverFromUrl(it, cover) }
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = Color.parseColor("#FF2196F3")
                        ds.isUnderlineText = true
                    }
                }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (desc.isNotBlank()) {
                newSection()
                appendLabel("简介：")
                sb.append(desc)
            }
            if (bgm.isNotBlank()) {
                newSection()
                appendLabel("BGM：")
                sb.append(bgm)
            }
            if (tags.isNotBlank()) {
                newSection()
                appendLabel("标签：")
                sb.append(tags)
            }
            sb as CharSequence
        }
        return built?.takeIf { it.isNotBlank() } ?: SpannableStringBuilder(fallbackDesc)
    }

    // 兼容可选择复制的 span 点击：setTextIsSelectable(true) 后 TextView 的 Editor 会吞掉
    // 触摸事件，movementMethod.onTouchEvent 收不到（实测日志 clickableSpans=1 但点击无响应）。
    // OnTouchListener 在 View.dispatchTouchEvent 阶段、TextView 内部处理之前触发，最可靠。
    private fun TextView.installSpanClickHandler() {
        setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val tv = v as TextView
                val layout = tv.layout
                if (layout != null) {
                    val x = ev.x.toInt() - tv.totalPaddingLeft + tv.scrollX
                    val y = ev.y.toInt() - tv.totalPaddingTop + tv.scrollY
                    val line = layout.getLineForVertical(y)
                    val off = layout.getOffsetForHorizontal(line, x.toFloat())
                    val spans = (tv.text as? Spanned)
                        ?.getSpans(off, off, ClickableSpan::class.java)
                    spans?.firstOrNull()?.let {
                        it.onClick(tv)
                        return@setOnTouchListener true
                    }
                }
            }
            false // 其余事件放行，不影响文本选择复制
        }
    }

    private fun showDescDialog(activity: Activity, message: CharSequence) {
        // 严格对齐 BiliRoamingX CommentCopyHook 的弹窗结构与配置顺序
        val appDialogTheme = getResId("AppTheme.Dialog.Alert", "style")
        AlertDialog.Builder(activity, appDialogTheme)
            .setTitle("自由复制内容")
            .setMessage(message)
            .setPositiveButton("分享") { _, _ ->
                activity.startActivity(
                    Intent.createChooser(
                        Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, message.toString())
                            type = "text/plain"
                        }, "分享简介内容"
                    )
                )
            }
            .setNeutralButton("复制全部") { _, _ ->
                copyToClipboard(activity, message)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply { show() }
            .findViewById<TextView>(android.R.id.message)?.apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                installSpanClickHandler()
            }
    }

    private fun copyToClipboard(context: Context, text: CharSequence) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        clipboardManager.setPrimaryClip(
            android.content.ClipData.newPlainText("copied_text", text)
        )
        Log.toast("已复制", false)
    }

    // 保存封面到相册，照本模块 CommentImageHook.saveImage 的 MediaStore 实现
    // （BiliRoamingX 式直写 File 在 B 站宿主 scoped storage 下不可用）
    private fun saveCoverFromUrl(activity: Activity, url: String) {
        val fullUrl = if (url.startsWith("//")) "https:$url" else url
        Thread {
            runCatching {
                URL(fullUrl).openStream().use { stream ->
                    val fullFilename = fullUrl.substringAfterLast('/').substringBefore('?')
                        .ifEmpty { "bili_cover_${System.currentTimeMillis()}.jpg" }
                    val now = System.currentTimeMillis()
                    val contentValues = ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            fullFilename.substringBeforeLast('.')
                        )
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            HttpURLConnection.guessContentTypeFromName(fullFilename) ?: "image/jpeg"
                        )
                        put(MediaStore.MediaColumns.DATE_ADDED, now / 1000)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1000)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.DATE_TAKEN, now)
                            put(
                                MediaStore.MediaColumns.RELATIVE_PATH,
                                "${Environment.DIRECTORY_PICTURES}${File.separator}bili"
                            )
                        } else {
                            val path = File(
                                Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_PICTURES
                                ), "bili"
                            ).also { it.mkdirs() }
                            put(
                                MediaStore.MediaColumns.DATA,
                                File(path, fullFilename).absolutePath
                            )
                        }
                    }
                    val resolver = activity.contentResolver
                    val uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                    ) ?: error("MediaStore insert failed")
                    resolver.openOutputStream(uri)?.use { stream.copyTo(it) }
                        ?: error("openOutputStream failed")
                }
            }.onSuccess {
                activity.runOnUiThread { Log.toast("封面已保存至 Pictures/bili", true) }
            }.onFailure {
                Log.e("CopyHook: save cover failed: $it")
                activity.runOnUiThread { Log.toast("封面保存失败: ${it.message}", true) }
            }
        }.start()
    }

    private fun showCopyDialog(context: Context, text: CharSequence) {
        val appDialogTheme = getResId("AppTheme.Dialog.Alert", "style")
        AlertDialog.Builder(context, appDialogTheme).run {
            setTitle("自由复制内容")
            setMessage(text)
            setPositiveButton("分享") { _, _ ->
                context.startActivity(
                    Intent.createChooser(
                        Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, text)
                            type = "text/plain"
                        }, "分享评论内容"
                    )
                )
            }
            setNeutralButton("复制全部") { _, _ ->
                copyToClipboard(context, text)
            }
            setNegativeButton(android.R.string.cancel, null)
            show()
        }.apply {
            findViewById<TextView>(android.R.id.message).setTextIsSelectable(true)
        }
    }
}
