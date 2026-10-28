package me.iacn.biliroaming.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.TextView
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*
import java.lang.reflect.Method

class CopyCommentHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("copy_comment", false)) return

        // 新版 kntr 评论区 —— D0.invoke(String)（COPY 枚举字段扫描定位）
        val kntrClass = instance.commentMoreMenuCopyClass
        val kntrMethod = instance.commentMoreMenuCopy()
        if (kntrClass != null && kntrMethod != null) {
            runCatching {
                kntrClass.hookAllMethods(kntrMethod) { chain ->
                    val text = chain.args.getOrNull(0) as? String
                        ?: return@hookAllMethods chain.proceed()
                    val topActivity = "com.bilibili.base.BiliContext"
                        .findClassOrNull(mClassLoader)
                        ?.callStaticMethodOrNull("topActivity") as? Activity
                        ?: return@hookAllMethods chain.proceed()
                    // 所有 guard 通过后才拦截默认行为（剪贴板写入 + Toast）
                    showCommentCopyDialog(topActivity, text)
                    null
                }
            }.onFailure { Log.e(it) }
        } else {
            Log.e("CopyCommentHook: kntr D0.invoke not resolved (class=$kntrClass method=$kntrMethod)")
        }

        // 旧版 comment3 评论区 —— CommentMoreMenuItemHolder.y3()
        val menuItemHolderClass =
            "com.bilibili.app.comment3.ui.widget.menu.CommentMoreMenuItemHolder"
                .findClassOrNull(mClassLoader)
        if (menuItemHolderClass != null) {
            findY3(menuItemHolderClass)?.let { y3 ->
                y3.isAccessible = true
                y3.hookMethod { chain ->
                    val result = chain.proceed()
                    val activity = "com.bilibili.base.BiliContext".findClassOrNull(mClassLoader)
                        ?.callStaticMethodOrNull("topActivity") as? Activity ?: return@hookMethod result
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(activity)?.toString()
                    if (!text.isNullOrEmpty()) {
                        showCommentCopyDialog(activity, text)
                    }
                    result
                }
            } ?: Log.e("CopyCommentHook: y3 method not found in CommentMoreMenuItemHolder")
        }
    }

    private fun findY3(clazz: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            c.declaredMethods.firstOrNull { it.name == "y3" }?.let { return it }
            c = c.superclass
        }
        return null
    }

    private fun showCommentCopyDialog(activity: Activity, text: String) {
        val appDialogTheme = getResId("AppTheme.Dialog.Alert", "style")
        AlertDialog.Builder(activity, appDialogTheme).run {
            setTitle("自由复制内容")
            setMessage(text)
            setPositiveButton("分享") { _, _ ->
                activity.startActivity(
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
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("copied_text", text)
                )
                Log.toast("已复制", false)
            }
            setNegativeButton(android.R.string.cancel, null)
            show()
        }.apply {
            findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }
    }
}
