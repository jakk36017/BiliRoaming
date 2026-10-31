package me.iacn.biliroaming.hook

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.BilibiliSponsorBlock
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.av2bv
import me.iacn.biliroaming.utils.callMethod
import me.iacn.biliroaming.utils.callMethodAs
import me.iacn.biliroaming.utils.hookMethod
import me.iacn.biliroaming.utils.mossResponseHandlerReplaceProxy
import me.iacn.biliroaming.utils.sPrefs
import java.lang.ref.WeakReference

class SkipVideoAd(classLoader: ClassLoader) : BaseHook(classLoader) {

    private var lastSeekTime = 0L
    private var playerRef: WeakReference<Any>? = null
    private val player get() = playerRef?.get()
    private var duration: Int = -1
    private var segments: List<BilibiliSponsorBlock.Segment>? = null
    private var bvid: String = ""
    private var cid: String = ""
    private var waitTime = 1000

    override fun startHook() {
        if (!sPrefs.getBoolean("skip_video_ad", false)) return

        Log.d("startHook: SkipVideoAd")

        instance.playerMossClass?.apply {
            hookMethod(
                "executePlayViewUnite",
                instance.playViewUniteReqClass ?: return@apply
            ) { chain ->
                val req = chain.args[0] ?: return@hookMethod chain.proceed()
                bvid = req.callMethodAs("getBvid")
                val vod = req.callMethod("getVod") ?: return@hookMethod chain.proceed()
                if (bvid.isEmpty()) {
                    val aid = vod.callMethodAs<Long>("getAid")
                    if (aid == -1L) {
                        return@hookMethod chain.proceed()
                    }
                    bvid = av2bv(aid)
                }
                cid = vod.callMethodAs<Long>("getCid").toString()
                chain.proceed()
            }

            hookMethod(
                "playViewUnite",
                instance.playViewUniteReqClass ?: return@apply,
                instance.mossResponseHandlerClass ?: return@apply
            ) { chain ->
                val handler = chain.args[1] ?: return@hookMethod chain.proceed()
                val args = chain.args.toTypedArray()
                args[1] = handler.mossResponseHandlerReplaceProxy { reply ->
                    reply ?: return@mossResponseHandlerReplaceProxy null
                    val playArc = reply.callMethod("getPlayArc")
                        ?: return@mossResponseHandlerReplaceProxy null
                    cid = playArc.callMethodAs<Long>("getCid").toString()
                    val aid = playArc.callMethodAs<Long>("getAid")
                    if (aid == -1L) {
                        return@mossResponseHandlerReplaceProxy null
                    }
                    bvid = av2bv(aid)
                    null
                }
                chain.proceed(args)
            }
        }

        instance.playerCoreServiceV2Class?.apply {
            // 状态回调在新版客户端由 G1 改名为 t，签名保持 void (int)。
            // 按「返回类型 + 参数类型」校验候选，避免误命中同名的其它方法。
            val stateMethod = declaredMethods.firstOrNull {
                it.name in arrayOf("G1", "t") &&
                    it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (stateMethod == null) Log.w("SkipVideoAd: player state method not found")
            stateMethod?.hookMethod { chain ->
                val result = chain.proceed()
                playerRef = WeakReference(chain.thisObject ?: return@hookMethod result)
                val state = chain.args[0] as Int
                if (state in 3..5 && duration <= 0) {
                    duration = (player?.callMethodAs<Int>("getDuration") ?: -1)
                }
                if (state == 2) {
                    duration = -1
                    segments = null
                    CoroutineScope(Dispatchers.IO).launch {
                        var retryCount = 0
                        val maxRetries = 3
                        while (retryCount < maxRetries) {
                            segments = BilibiliSponsorBlock(bvid, cid).getSegments()
                            if (segments.isNullOrEmpty()) {
                                retryCount++
                                delay(1000)
                            } else {
                                break
                            }
                        }
                        if (segments == null) {
                            Log.toast("广告片段数据获取失败")
                            return@launch
                        }
                    }
                }
                result
            }

            hookMethod("getCurrentPosition") { chain ->
                val result = chain.proceed()
                val now = System.currentTimeMillis()
                if (now - lastSeekTime > waitTime) {
                    lastSeekTime = now
                    waitTime = if (seekTo(result as Int)) 3000 else 1000
                }
                result
            }
        }
    }

    private fun seekTo(position: Int?): Boolean {
        if (position != null) {
            if (position > duration) return false
        }

        val segs = segments
        if (segs != null) {
            for (segment in segs) {
                val start = (segment.segment[0] * 1000).toInt()
                val end = (segment.segment[1] * 1000).toInt()
                if (position in start..<end) {
                    Log.toast("已跳过广告片段")
                    player?.callMethod("seekTo", end)
                    return true
                }
            }
        }
        return false
    }
}
