package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.AccountHelper
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.sPrefs
import kotlin.concurrent.thread

/**
 * 自动领取卡券及经验的触发 Hook
 *
 * 在 B 站主进程启动后延迟 2 秒执行 CouponAutoReceiver.check()，
 * 自动领取大会员卡券、每日经验及视频分享经验。
 *
 * 触发时机：Application.onCreate 之后（在 XposedInit 中随其他 Hook 一起启动）
 * 执行线程：后台线程（避免阻塞主线程）
 * 开关控制：sPrefs "auto_receive_coupon"
 */
class CouponAutoHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    override fun startHook() {
        // 延迟 2 秒后在后台线程执行自动领取
        thread(name = "BiliRoaming-CouponAuto", isDaemon = true) {
            try {
                Thread.sleep(2000)
                if (!sPrefs.getBoolean("auto_receive_coupon", false)) return@thread
                if (!AccountHelper.isLogin) {
                    Log.d("CouponAutoHook: user not logged in, skip")
                    return@thread
                }
                Log.d("CouponAutoHook: start auto receive coupon and experience")
                CouponAutoReceiver.check()
            } catch (e: Throwable) {
                Log.e(e)
            }
        }
    }
}
