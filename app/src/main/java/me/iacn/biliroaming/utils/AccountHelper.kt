package me.iacn.biliroaming.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bilibili 账号信息读取工具
 *
 * 从 B 站客户端的本地存储中读取登录凭证：
 * - SESSDATA: 用于 Cookie 鉴权的 API 请求
 * - bili_jct: CSRF token，用于 POST 请求的 csrf 参数
 * - access_key: 访问令牌，用于需要签名的 API
 *
 * 数据来源：
 * - bili.passport.storage: AES 加密的访问令牌（base64 编码后存储）
 * - bili.account.storage: base64 编码的 Cookie JSON
 * - account/controller.blkv: 当前账号信息（B 站自研二进制 KV 存储）
 *
 * 迁移后的账号（account_migrate == 1）直接从 controller.blkv 读取 token 和 cookie 字段。
 * 注意 controller.blkv 是二进制格式，必须通过 B 站自带的 BLKV 类读取，不能用 XML 解析。
 */
object AccountHelper {

    @Volatile
    private var accountCache: Account? = null

    /** 登录凭证数据类 */
    data class Account(
        val accessKey: String,
        val cookies: List<Cookie>
    ) {
        data class Cookie(val name: String, val value: String)
    }

    val cookieSESSDATA: String
        get() = get()?.cookies?.find { it.name == "SESSDATA" }?.value.orEmpty()

    val cookieBiliJct: String
        get() = get()?.cookies?.find { it.name == "bili_jct" }?.value.orEmpty()

    val accessKey: String
        get() = get()?.accessKey.orEmpty()

    val isLogin: Boolean
        get() = get() != null

    /**
     * 获取账号信息（带缓存，读取失败不缓存以便下次重试）
     */
    fun get(): Account? {
        accountCache?.let { return it }
        synchronized(this) {
            accountCache?.let { return it }
            val account = readAccount() ?: return null
            accountCache = account
            return account
        }
    }

    private fun readAccount(): Account? = runCatching {
        val context = currentContext
        val passportFile = File(context.filesDir, "bili.passport.storage")
        val cookieFile = File(context.filesDir, "bili.account.storage")

        // 通过 B 站自带的 BLKV 存储类读取 controller.blkv（二进制格式，非 XML）
        val accountDir = context.getDir("account", Context.MODE_PRIVATE)
        val controllerPrefs = blkvPrefsByFile(File(accountDir, "controller.blkv"))
        val accountMigrated = controllerPrefs?.getInt("account_migrate", 0) == 1
        val currentAccount = controllerPrefs?.getString("current_account", "").orEmpty()
        Log.d("AccountHelper: accountMigrated=$accountMigrated, hasCurrentAccount=${currentAccount.isNotEmpty()}")

        val accountJson = currentAccount.ifEmpty { null }?.toJSONObject()

        // 读取加密的 access token
        val account = if (accountMigrated) {
            accountJson?.optString("token")
        } else if (passportFile.isFile) {
            passportFile.fastReadToString()
        } else null

        // 读取 cookie
        val cookie = if (accountMigrated) {
            accountJson?.optString("cookie")
        } else if (cookieFile.isFile) {
            cookieFile.fastReadToString()
        } else null

        if (account.isNullOrEmpty() || cookie.isNullOrEmpty()) {
            Log.d("AccountHelper: account or cookie is empty, not logged in")
            return@runCatching null
        }

        // AES 解密 access token
        val accountInfo = biliAesDecrypt(account.base64Decode)
            .toString(Charsets.UTF_8)
            .toJSONObject()
        val accessKey = accountInfo.optString("access_token")
        val mid = accountInfo.optLong("mid")

        // 解析 cookie
        val cookieInfo = cookie.base64Decode
            .toString(Charsets.UTF_8)
            .toJSONObject()
        val cookies = cookieInfo.optJSONArray("cookies")?.let { arr ->
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Account.Cookie(c.optString("name"), c.optString("value"))
            }
        } ?: emptyList()

        if (accessKey.isEmpty()) {
            Log.d("AccountHelper: accessKey is empty after decrypt")
            null
        } else {
            Log.d("AccountHelper: account loaded, mid=$mid, cookies=${cookies.size}")
            Account(accessKey, cookies)
        }
    }.onFailure {
        Log.e(it)
    }.getOrNull()

    /**
     * 通过反射调用 B 站自带的 BLKV 存储类，按文件读取 SharedPreferences。
     * 对应 B 站方法：com.bilibili.lib.blkv.BLKV.getBLSharedPreferences(Context, File, boolean, int)
     * 与 BiliRoamingX 的 blkvPrefsByFile 等价。
     */
    private fun blkvPrefsByFile(file: File): SharedPreferences? = runCatching {
        val classLoader = currentContext.classLoader
        val blkvClass = Class.forName("com.bilibili.lib.blkv.BLKV", false, classLoader)
        val method = blkvClass.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 4
                    && m.parameterTypes[0] == Context::class.java
                    && m.parameterTypes[1] == File::class.java
                    && m.parameterTypes[2] == Boolean::class.javaPrimitiveType
                    && m.parameterTypes[3] == Int::class.javaPrimitiveType
                    && SharedPreferences::class.java.isAssignableFrom(m.returnType)
        } ?: run {
            Log.d("AccountHelper: BLKV.getBLSharedPreferences method not found")
            return@runCatching null
        }
        method.isAccessible = true
        method.invoke(null, currentContext, file, true, 0) as? SharedPreferences
    }.onFailure {
        Log.e(it)
    }.getOrNull()

    /**
     * 使用 mmap 快速读取文件内容
     */
    private fun File.fastReadToString(): String {
        return RandomAccessFile(this, "r").use { file ->
            val channel = file.channel
            val length = file.length()
            channel.lock(0L, length, true).use {
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, length)
                ByteArray(length.toInt()).apply { buffer.get(this) }.toString(Charsets.UTF_8)
            }
        }
    }

    /**
     * B 站 AES 解密（CBC/PKCS5Padding）
     * 密钥为 appKey 的前 16 字节，IV 由 LibBili 的 getAesIv 方法（新版混淆为 b）获取
     */
    private fun biliAesDecrypt(bytes: ByteArray): ByteArray {
        val appKey = instance.appKey
        val key = appKey.toByteArray().copyOf(16)
        val keySpec = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, getAesIv(appKey))
        return cipher.doFinal(bytes)
    }

    /**
     * 通过反射调用 B 站 LibBili 的 getAesIv 方法获取 AES IV。
     * 新版 B 站该方法被混淆（如 b(String)），因此按方法签名特征查找：
     * 参数为 String，返回 IvParameterSpec。
     */
    private fun getAesIv(appKey: String): IvParameterSpec {
        val libBiliClass = instance.libBiliClass
            ?: error("LibBili class not found")
        val ivMethod = libBiliClass.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1
                    && m.parameterTypes[0] == String::class.java
                    && m.returnType == IvParameterSpec::class.java
        } ?: error("getAesIv method not found in LibBili")
        ivMethod.isAccessible = true
        return ivMethod.invoke(null, appKey) as IvParameterSpec
    }

    private val String.base64Decode: ByteArray
        get() = Base64.decode(this, Base64.DEFAULT)
}
