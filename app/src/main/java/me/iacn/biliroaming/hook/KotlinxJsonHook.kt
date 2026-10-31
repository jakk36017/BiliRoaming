package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.hook.kotlinx.KotlinxSplashListProcessor
import me.iacn.biliroaming.hook.kotlinx.KotlinxSplashShowProcessor
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.callMethodOrNull
import me.iacn.biliroaming.utils.callMethodOrNullAs
import me.iacn.biliroaming.utils.hookAllMethods
import java.util.concurrent.ConcurrentHashMap

class KotlinxJsonHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val processors = listOf(
        KotlinxSplashListProcessor(),
        KotlinxSplashShowProcessor(),
    ).filter { it.shouldEnable() }

    // 序列化器的 serialName 固定不变，缓存后避免每次 decode 都重复反射
    private val serialNameCache = ConcurrentHashMap<Any, String>()

    override fun startHook() {
        if (processors.isEmpty()) return
        Log.d("startHook: KotlinxJson")

        val jsonClass = instance.kotlinJsonClass ?: return

        jsonClass.hookAllMethods("decodeFromString") { chain ->
            val result = chain.proceed()
            dispatchResult(chain.args.getOrNull(0), result)
            result
        }
    }

    private fun dispatchResult(deserializer: Any?, result: Any?) {
        if (deserializer == null || result == null) return
        val serialName = serialNameOf(deserializer) ?: return
        val processor = processors.firstOrNull { it.targetSerialName == serialName } ?: return
        try {
            processor.process(result)
        } catch (e: Throwable) {
            Log.e("KotlinxJsonHook processor ${processor.targetSerialName} error: $e")
        }
    }

    private fun serialNameOf(deserializer: Any): String? =
        serialNameCache[deserializer] ?: run {
            val name = deserializer.callMethodOrNull("getDescriptor")
                ?.callMethodOrNullAs<String>("getSerialName") ?: return null
            serialNameCache[deserializer] = name
            name
        }
}
