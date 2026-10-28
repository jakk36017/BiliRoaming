package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.AccountHelper
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.signQuery
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 自动领取 B 币券与每日经验
 *
 * 移植自 BiliRoamingX 的 CouponAutoReceiver，完全脱离 ReVanced 环境，
 * 在 BiliRoaming 的 LSPosed 模块中独立运行。
 *
 * 功能：
 * 1. 自动领取大会员每月卡券（B 币券）
 * 2. 自动领取大会员每日经验
 * 3. 自动完成每日视频分享获取经验
 *
 * 相关 API：
 * - GET  https://api.bilibili.com/x/vip/privilege/my        获取卡券列表
 * - POST https://api.bilibili.com/x/vip/privilege/receive   领取卡券
 * - POST https://api.bilibili.com/x/vip/experience/add      领取每日经验
 * - POST https://api.bilibili.com/x/share/finish            完成视频分享（获取经验）
 */
object CouponAutoReceiver {

    private const val URL_COUPON_LIST = "https://api.bilibili.com/x/vip/privilege/my"
    private const val URL_RECEIVE_COUPON = "https://api.bilibili.com/x/vip/privilege/receive"
    private const val URL_ADD_EXPERIENCE = "https://api.bilibili.com/x/vip/experience/add"
    private const val URL_SHARE_FINISH = "https://api.bilibili.com/x/share/finish"

    /**
     * 执行自动领取检查
     * 由 CouponAutoHook 在 B 站启动后延迟调用（开关已由调用方判断）
     */
    fun check() {
        val couponInfo = getCouponInfo() ?: return

        // 领取卡券（type <= 7，未领取 state != 1，且为大会员 vipType > 0）
        val couponSuccessCount = couponInfo.optJSONArray("list")?.let { list ->
            (0 until list.length()).count { i ->
                val item = list.getJSONObject(i)
                if (item.optInt("type") <= 7
                    && item.optInt("state") != 1
                    && item.optInt("vip_type") > 0
                ) {
                    receiveCoupon(item.optInt("type"))
                } else false
            }
        } ?: 0
        Log.d("CouponAutoReceiver: couponSuccessCount = $couponSuccessCount")

        // 领取每日经验（type == 9，未领取 state == 0，且为大会员 vipType > 0）
        val experienceSuccessCount = couponInfo.optJSONArray("list")?.let { list ->
            (0 until list.length()).count { i ->
                val item = list.getJSONObject(i)
                if (item.optInt("type") == 9
                    && item.optInt("state") == 0
                    && item.optInt("vip_type") > 0
                ) {
                    receiveExperience()
                } else false
            }
        } ?: 0
        Log.d("CouponAutoReceiver: experienceSuccessCount = $experienceSuccessCount")

        // 每日视频分享经验
        val shareSuccess = receiveShareExperience()
        Log.d("CouponAutoReceiver: shareSuccess = $shareSuccess")

        // 汇总提示
        val coupon = if (couponSuccessCount > 0) "${couponSuccessCount}张卡券" else null
        val vipExp = if (experienceSuccessCount > 0) "大会员每日经验" else null
        val shareExp = if (shareSuccess) "每日视频分享经验" else null

        arrayOf(coupon, vipExp, shareExp).filterNotNull().joinToString("、")
            .takeIf { it.isNotEmpty() }?.let {
                Log.toast("自动领取成功：$it")
            }
    }

    /**
     * 获取卡券列表
     * Cookie 鉴权，需要 SESSDATA
     */
    private fun getCouponInfo(): JSONObject? {
        val sessdata = AccountHelper.cookieSESSDATA
        if (sessdata.isEmpty()) {
            Log.d("CouponAutoReceiver: SESSDATA is empty, user not logged in")
            return null
        }
        return httpGet(URL_COUPON_LIST, mapOf("Cookie" to "SESSDATA=$sessdata"))
            ?.optJSONObject("data")
    }

    /**
     * 领取指定类型的卡券
     * POST 表单，需要 SESSDATA Cookie + csrf(bili_jct)
     */
    private fun receiveCoupon(type: Int): Boolean {
        val sessdata = AccountHelper.cookieSESSDATA
        val biliJct = AccountHelper.cookieBiliJct
        if (sessdata.isEmpty() || biliJct.isEmpty()) return false

        val body = mapOf(
            "type" to type.toString(),
            "csrf" to biliJct
        )
        val response = httpPostForm(
            URL_RECEIVE_COUPON,
            body,
            mapOf("Cookie" to "SESSDATA=$sessdata")
        )
        Log.d("CouponAutoReceiver.receiveCoupon type=$type response=$response")
        return response?.optInt("code", -1) == 0
    }

    /**
     * 领取大会员每日经验
     * POST 表单，需要 SESSDATA Cookie + csrf(bili_jct)
     */
    private fun receiveExperience(): Boolean {
        val sessdata = AccountHelper.cookieSESSDATA
        val biliJct = AccountHelper.cookieBiliJct
        if (sessdata.isEmpty() || biliJct.isEmpty()) return false

        val body = mapOf("csrf" to biliJct)
        val response = httpPostForm(
            URL_ADD_EXPERIENCE,
            body,
            mapOf("Cookie" to "SESSDATA=$sessdata")
        )
        Log.d("CouponAutoReceiver.receiveExperience response=$response")
        return response?.optInt("code", -1) == 0
    }

    /**
     * 完成每日视频分享以获取经验
     * 使用 access_key 签名请求，无需 Cookie
     *
     * 选择固定的 aid 和随机的 cid 模拟一次视频分享操作
     */
    private fun receiveShareExperience(): Boolean {
        val accessKey = AccountHelper.accessKey
        if (accessKey.isEmpty()) {
            Log.d("CouponAutoReceiver: accessKey is empty")
            return false
        }

        val sid = arrayOf(
            "279786", "275431", "279787", "280467", "280468",
            "280469", "274491", "267410", "267714", "270380"
        ).random() // 随机 cid

        val params = mapOf(
            "access_key" to accessKey,
            "oid" to "170001", // 固定 aid
            "panel_type" to "1",
            "share_channel" to "QQ",
            "share_id" to "main.ugc-video-detail.0.0.pv",
            "share_origin" to "vinfo_player",
            "sid" to sid,
            "success" to "true"
        )

        // 使用 B 站签名算法对请求参数签名
        val signedQuery = signQuery(params).takeIf { it != "null" } ?: return false

        val response = httpPostRaw(
            URL_SHARE_FINISH,
            signedQuery,
            mapOf("Content-Type" to "application/x-www-form-urlencoded")
        )
        Log.d("CouponAutoReceiver.receiveShareExperience response=$response")
        return response?.run {
            optInt("code", -1) == 0
                    && !optJSONObject("data")?.optString("toast").isNullOrEmpty()
        } ?: false
    }

    // ==================== HTTP 工具方法 ====================

    /**
     * 发送 GET 请求，返回解析后的 JSONObject
     */
    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "BiliRoaming")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()
            JSONObject(response)
        }.onFailure {
            Log.e(it)
        }.getOrNull()
    }

    /**
     * 发送 POST 表单请求，返回解析后的 JSONObject
     */
    private fun httpPostForm(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return httpPostRaw(url, body, headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"))
    }

    /**
     * 发送 POST 请求（原始 body），返回解析后的 JSONObject
     */
    private fun httpPostRaw(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("User-Agent", "BiliRoaming")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            DataOutputStream(conn.outputStream).use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()
            JSONObject(response)
        }.onFailure {
            Log.e(it)
        }.getOrNull()
    }
}
