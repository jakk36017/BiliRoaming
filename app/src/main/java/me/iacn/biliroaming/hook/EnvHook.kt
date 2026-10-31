package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.*
import java.lang.reflect.Modifier
import java.util.regex.Pattern

class EnvHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        Log.d("startHook: Env")
    }

    override fun lateInitHook() {
        Log.d("lateHook: Env")
        if (!sPrefs.getBoolean("enable_av", false)) return
        val compatClass = "com.bilibili.droid.BVCompat".findClassOrNull(mClassLoader) ?: return

        // 显示侧唯一出口：static (avid, bvid) -> 实际展示的 ID。
        // 按结构定位而不写死混淆名，候选不唯一时放弃，避免误 hook。
        val chooser = compatClass.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.returnType == String::class.java &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
        }
        if (chooser == null) {
            Log.w("BVCompat chooser not found")
        } else {
            chooser.hookMethod { chain ->
                val result = chain.proceed()
                val current = result as? String ?: return@hookMethod result
                if (!isBvId(current)) return@hookMethod result
                // 只把真正的 BV 号换成另一个入参里的 AV 号，参数顺序变化也不会反向替换
                chain.args.filterIsInstance<String>()
                    .firstOrNull { it != current && it.isNotBlank() && !isBvId(it) }
                    ?: return@hookMethod result
            }
        }

        // 扩大文本识别正则，保证简介、评论里的 AV / BV 链接都还能点
        compatClass.declaredFields.forEach { f ->
            runCatching {
                val field = compatClass.getStaticObjectField(f.name)
                if (field is Pattern && field.pattern() == "av[1-9]\\d*") {
                    compatClass.setStaticObjectField(
                        f.name,
                        Pattern.compile("(av[1-9]\\d*)|(BV1[1-9A-NP-Za-km-z]{9})", field.flags())
                    )
                }
            }
        }
    }

    companion object {
        private fun isBvId(value: String) = value.length == 12 && value.startsWith("BV")
    }
}
