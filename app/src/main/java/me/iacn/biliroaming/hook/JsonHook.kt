package me.iacn.biliroaming.hook

import android.view.View
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

class JsonHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        val bottomItems = mutableListOf<BottomItem>()
        val drawerItems = mutableListOf<BottomItem>()
        val homeTabItems = mutableListOf<HomeTabItem>()

        const val HIDED_HOME_TAB_KEY = "hided_home_tab_items"
        private const val HOME_TAB_DATA_SERIAL = "tv.danmaku.bili.khomeapi.frame.HomeTabData"
        private const val HOME_TAB_ITEM_SERIAL = "tv.danmaku.bili.khomeapi.frame.HomeTabItemData"
        private const val MAX_GENERIC_DEPTH = 4
        private val BOTTOM_TAB_HOST_CLASSES = listOf(
            "com.bilibili.lib.homepage.widget.TabHost",
            "com.bilibili.p5690lib.p5708homepage.widget.TabHost",
            "tv.danmaku.bili.ui.main2.widget.TabHost",
            "tv.danmaku.p9138bili.p9228ui.main2.widget.TabHost"
        )
        private val NON_ROUTE_SCHEMES = setOf("http", "https", "file")
        private val WHITESPACE = Regex("\\s+")
        private val LEGACY_HOME_FRAGMENT_CLASSES = listOf(
            "tv.danmaku.bili.ui.main2.HomeFragmentV2",
            "tv.danmaku.p9138bili.p9228ui.main2.HomeFragmentV2"
        )
        private val LEGACY_HOME_TAB_RESOURCE_CLASSES = listOf(
            "tv.danmaku.bili.ui.main2.resource.z",
            "tv.danmaku.p9138bili.p9228ui.main2.resource.z"
        )
        private val SPLASH_RESPONSE_CLASSES = listOf(
            "tv.danmaku.bili.splash.ad.model.SplashListResponse",
            "tv.danmaku.bili.splash.ad.model.SplashShowResponse",
            "tv.danmaku.bili.ui.splash.SplashData",
            "tv.danmaku.bili.ui.splash.ad.model.SplashData",
            "tv.danmaku.bili.ui.splash.ShowSplashData",
            "tv.danmaku.bili.ui.splash.ad.model.SplashShowData"
        )
        private val SPLASH_LIST_GETTERS =
            setOf("getSplashList", "getStrategyList", "getShowList", "getAdList")
        private val SKIPPED_PACKAGES =
            listOf("java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.")
    }

    override fun startHook() {
        Log.d("startHook: Json")

        val hidden = sPrefs.getBoolean("hidden", false)
        val purifyLivePopups = sPrefs.getStringSet("purify_live_popups", null) ?: setOf()
        val unlockPlayLimit = sPrefs.getBoolean("play_arc_conf", false)

        val tabResponseClass =
            "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$TabResponse".findClassOrNull(
                mClassLoader
            )
        val accountMineClass =
            "tv.danmaku.bili.ui.main2.api.AccountMine".findClassOrNull(mClassLoader)
        val garbEntranceClass =
            "tv.danmaku.bili.ui.main2.api.AccountMine\$GarbEntrance".from(mClassLoader)
        val tabClass =
            "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$Tab".findClassOrNull(
                mClassLoader
            )
        val defaultWordClass =
            "tv.danmaku.bili.ui.main2.api.SearchDefaultWord".findClassOrNull(mClassLoader)
        val defaultKeywordClass =
            "com.bilibili.search.api.DefaultKeyword".findClassOrNull(mClassLoader)
        val brandSplashDataClass =
            "tv.danmaku.bili.ui.splash.brand.BrandSplashData".findClassOrNull(mClassLoader)
                ?: "tv.danmaku.bili.ui.splash.brand.model.BrandSplashData".findClassOrNull(mClassLoader)
        val eventEntranceClass =
            "tv.danmaku.bili.ui.main.event.model.EventEntranceModel".findClassOrNull(mClassLoader)
        val searchRanksClass = "com.bilibili.search.api.SearchRanks".findClassOrNull(mClassLoader)
        val searchReferralClass =
            "com.bilibili.search.api.SearchReferral".findClassOrNull(mClassLoader)
        val searchReferralV2Class =
            "com.bilibili.search2.api.SearchReferral".findClassOrNull(mClassLoader)
        val followingcardSearchRanksClass =
            "com.bilibili.bplus.followingcard.net.entity.b".findClassOrNull(mClassLoader)
        val spaceClass =
            "com.bilibili.app.authorspace.api.BiliSpace".findClassOrNull(mClassLoader)
        val ogvApiResponseClass =
            "tv.danmaku.bili.ui.offline.api.OgvApiResponse".findClassOrNull(mClassLoader)
        val ogvApiResponseV2Class =
            "tv.danmaku.bili.ui.offline.api.OgvApiResponseV2".findClassOrNull(mClassLoader)
        val dmAdvertClass =
            "com.bilibili.ad.adview.videodetail.danmakuv2.model.DmAdvert".from(mClassLoader)
        val liveShoppingInfoClass =
            "com.bilibili.bililive.room.biz.shopping.beans.LiveShoppingInfo".from(mClassLoader)
        val liveGoodsCardInfoClass =
            "com.bilibili.bililive.room.biz.shopping.beans.LiveGoodsCardInfo".from(mClassLoader)
        val liveRecommendCardGoodsClass =
            "com.bilibili.bililive.room.biz.shopping.beans.LiveShoppingRecommendCardGoodsDetail"
                .from(mClassLoader)
        val biliLiveRoomInfoClass =
            "com.bilibili.bililive.videoliveplayer.net.beans.gateway.roominfo.BiliLiveRoomInfo"
                .from(mClassLoader)
        val liveRoomReserveInfoClass =
            "com.bilibili.bililive.room.biz.reverse.bean.LiveRoomReserveInfo".from(mClassLoader)
        val biliLiveRoomUserInfoClass =
            "com.bilibili.bililive.videoliveplayer.net.beans.gateway.userinfo.BiliLiveRoomUserInfo"
                .from(mClassLoader)
        val liveRoomRecommendCardClass =
            "com.bilibili.bililive.videoliveplayer.net.beans.attentioncard.LiveRoomRecommendCard"
                .from(mClassLoader)
        val liveShoppingGotoBuyInfoClass =
            "com.bilibili.bililive.room.biz.shopping.beans.LiveShoppingGotoBuyInfo"
                .from(mClassLoader)
        val shareChannelsClass = "com.bilibili.lib.sharewrapper.online.api.ShareChannels"
            .from(mClassLoader)
        val channelItemClass = "com.bilibili.lib.sharewrapper.online.api.ShareChannels\$ChannelItem"
            .from(mClassLoader)

        val processFastJsonResult: (Any?) -> Any? = processFastJsonResult@{ origResult ->
            var result = origResult ?: return@processFastJsonResult null
            if (result.javaClass == instance.generalResponseClass) {
                result = result.getObjectField("data") ?: return@processFastJsonResult origResult
            }

            when (result.javaClass) {
                tabResponseClass -> {
                    val data = result.getObjectField("tabData")

                    bottomItems.clear()
                    val hides = sPrefs.getStringSet("hided_bottom_items", mutableSetOf())!!
                    data?.getObjectFieldAs<MutableList<*>?>("bottom")?.removeAll {
                        val uri = it?.getObjectFieldAs<String>("uri")
                        val id = it?.getObjectFieldAs<String>("tabId")
                        val showing = id !in hides
                        bottomItems.add(
                            BottomItem(
                                it?.getObjectFieldAs("name"),
                                uri, id, showing
                            )
                        )
                        showing.not()
                    }

                    if (sPrefs.getBoolean("drawer", false) && !sPrefs.getBoolean("hidden", false)) {
                        data?.getObjectFieldAs<MutableList<*>?>("bottom")?.removeAll {
                            it?.getObjectFieldAs<String?>("uri")
                                ?.startsWith("bilibili://user_center/mine")
                                ?: false
                        }
                    }

                    configTab(data, tabClass)

                    if (sPrefs.getBoolean("purify_game", false) &&
                        sPrefs.getBoolean("hidden", false)
                    ) {
                        val top = data?.getObjectFieldAs<MutableList<*>?>("top")
                        top?.removeAll {
                            val uri = it?.getObjectFieldAs<String?>("uri")
                            uri?.startsWith("bilibili://game_center/home") ?: false
                        }
                    }

                }
                accountMineClass -> {
                    drawerItems.clear()
                    val hides = sPrefs.getStringSet("hided_drawer_items", mutableSetOf())!!
                    if (platform == "android_hd") {
                        listOf(result.getObjectFieldOrNullAs<MutableList<*>?>("padSectionList"),
                               result.getObjectFieldOrNullAs<MutableList<*>?>("recommendSectionList"),
                               result.getObjectFieldOrNullAs<MutableList<*>?>("moreSectionList")
                        ).forEach {
                            it?.removeAll { items ->
                                // 分析内容
                                val title = items?.getObjectFieldAs<String>("title")
                                val uri = items?.getObjectFieldAs<String>("uri")
                                val id = items?.getObjectField("id").toString()

                                // 修改成自定义按钮
                                if (sPrefs.getBoolean("add_custom_button", false) && id == sPrefs.getString("custom_button_id", "")){
                                    val icon = items?.getObjectFieldAs<String>("icon").toString()
                                    items?.setObjectField("title", sPrefs.getString("custom_button_title", title))
                                        ?.setObjectField("uri", sPrefs.getString("custom_button_uri", uri))
                                        ?.setObjectField("icon", sPrefs.getString("custom_button_icon", icon))
                                    return@removeAll false
                                }

                                val showing = id !in hides
                                // 将结果写入 drawerItems
                                drawerItems.add(BottomItem(title, uri, id, showing))
                                // 去除红点
                                if (sPrefs.getBoolean("purify_drawer_reddot", false)) items?.setIntField("redDot",0)
                                showing.not()
                            }
                        }
                    } else {
                        result.getObjectFieldOrNullAs<MutableList<*>?>("sectionListV2")?.forEach { sections ->
                            try {
                                // 将标题写入 drawerItems
                                val bigTitle = sections?.getObjectFieldOrNull("title").toString()
                                if (bigTitle != "null") drawerItems.add(BottomItem("【标题项目】", null, bigTitle, bigTitle !in hides))
                                // 去除项目
                                sections?.getObjectFieldOrNullAs<MutableList<*>?>("itemList")
                                    ?.removeAll { items ->
                                        // 分析内容
                                        val title = try {
                                            items?.getObjectFieldAs<String>("title")
                                        } catch (thr: Throwable) {
                                            return@removeAll false
                                        }
                                        if (title == "null") return@removeAll false
                                        val uri = items?.getObjectFieldAs<String>("uri")
                                        val id = items?.getObjectField("id").toString()

                                        // 修改成自定义按钮
                                        if (sPrefs.getBoolean("add_custom_button", false) && id == sPrefs.getString("custom_button_id", "")){
                                            val icon = items?.getObjectFieldAs<String>("icon").toString()
                                            items?.setObjectField("title", sPrefs.getString("custom_button_title", title))
                                                ?.setObjectField("uri", sPrefs.getString("custom_button_uri", uri))
                                                ?.setObjectField("icon", sPrefs.getString("custom_button_icon", icon))
                                            return@removeAll false
                                        }

                                        val showing = id !in hides
                                        // 将结果写入 drawerItems
                                        drawerItems.add(BottomItem(title, uri, id, showing))
                                        // 去除红点
                                        if (sPrefs.getBoolean("purify_drawer_reddot", false)) items?.setIntField("redDot", 0)
                                        showing.not()
                                    }
                                // 去除按钮
                                val button = sections?.getObjectFieldOrNull("button")
                                if (button != null) {
                                    val buttonText = button.getObjectField("text").toString()
                                    val showing = buttonText !in hides
                                    if (buttonText != "null") {
                                        val uri = button.getObjectFieldAs<String>("jumpUrl")
                                        drawerItems.add(BottomItem("按钮：", uri, buttonText, showing))
                                        if (!showing) sections.setObjectField("button", null)
                                    }
                                }
                                // 改变样式
                                if (sPrefs.getBoolean("drawer_style_switch", false)) {
                                    sections?.setIntField(
                                        "style",
                                        when {
                                            sPrefs.getBoolean("drawer_style", false) -> 2
                                            else -> 1
                                        }
                                    )
                                }
                            } catch (e: Exception) {
                                Log.d(e)
                            }
                        }
                        // 删除标题组
                        var deleteTitle = true
                        result.getObjectFieldOrNullAs<MutableList<*>?>("sectionListV2")?.removeAll { sections ->
                            val title = sections?.getObjectFieldOrNull("title").toString()
                            if (title !in hides) {
                                if (title == "创作中心" || title == "游戏中心" || title == "創作中心" || title == "遊戲中心")
                                    deleteTitle = false // 标记
                                else if (title == "推荐服务" || title == "推薦服務")
                                    deleteTitle = true // 取消标记
                            } else {
                                if ((title == "更多服务" || title == "更多服務") && !deleteTitle) {
                                    Log.toast("自定义我的页面，【标题项目】不能只保留【创作中心】或【游戏中心】，因此不删除【更多服务】，请修改你的漫游设置", true)
                                    return@removeAll false
                                }
                            }
                            title in hides
                        }

                        // liveTip
                        // 就是通常在 我的 -> 创做中心 下 但不属于创作中心的提示
                        result.getObjectFieldOrNull("liveTip")?.let { liveTip ->
                            val text = liveTip.getObjectFieldAs<String>("text")
                            val id = liveTip.getObjectField("id").toString()
                            val url = liveTip.getObjectFieldAs<String>("url")
                            val showing = id !in hides
                            drawerItems.add(BottomItem(
                                name = text, id = id, uri = url, showing = showing
                            ))
                            if (!showing) {
                                result.setObjectField("liveTip", null)
                            }
                        }

                    }
                    accountMineClass.findFieldOrNull("vipSectionRight")?.set(result, null)
                    if (sPrefs.getBoolean("custom_theme", false)) {
                        if (instance.clientVersionCode >= 7480200) {
                            garbEntranceClass?.new()?.apply {
                                setObjectField("uri", "activity://navigation/theme/")
                            }?.let { result.setObjectField("garbEntrance", it) }
                        } else {
                            result.setObjectField("garbEntrance", null)
                        }
                    }
                }
                defaultWordClass, defaultKeywordClass, searchRanksClass, searchReferralClass, followingcardSearchRanksClass -> if (sPrefs.getBoolean(
                        "purify_search",
                        false
                    ) &&
                    sPrefs.getBoolean("hidden", false)
                ) {
                    result.javaClass.fields.forEach {
                        if (!it.type.isPrimitive)
                            result.setObjectField(it.name, null)
                    }
                }
                searchReferralV2Class -> if (sPrefs.getBoolean(
                        "purify_search",
                        false
                    ) &&
                    sPrefs.getBoolean("hidden", false)
                ) {
                    result.javaClass.declaredFields.forEach {
                        it.isAccessible = true
                        it.set(result, it.type.let { type ->
                            when (type) {
                                Int::class.javaPrimitiveType -> 0
                                else -> null
                            }
                        })
                    }
                }
                brandSplashDataClass -> if (sPrefs.getBoolean("custom_splash", false) ||
                    sPrefs.getBoolean("custom_splash_logo", false)
                ) {
                    result.getObjectFieldOrNullAs<MutableList<Any>>("brandList")?.clear()
                    result.getObjectFieldOrNullAs<MutableList<Any>>("showList")?.clear()
                }
                eventEntranceClass -> if (sPrefs.getBoolean("purify_game", false) &&
                    sPrefs.getBoolean("hidden", false)
                ) {
                    result.setObjectField("online", null)
                    result.setObjectField("hash", "")
                }
                spaceClass -> {
                    val purifySpaceSet =
                        sPrefs.getStringSet("customize_space", emptySet()).orEmpty()
                    if (purifySpaceSet.isNotEmpty()) {
                        purifySpaceSet.forEach {
                            if (!it.contains(".")) result.setObjectField(
                                it,
                                null
                            )
                        }
                        // Exceptions (adV2 -> ad + adV2)
                        if (purifySpaceSet.contains("adV2")) result.setObjectField("ad", null)

                        result.getObjectFieldAs<MutableList<*>?>("tab")?.removeAll {
                            when (it?.getObjectFieldAs<String?>("param")) {
                                "home" -> purifySpaceSet.contains("tab.home")
                                "dynamic" -> purifySpaceSet.contains("tab.dynamic")
                                "contribute" -> purifySpaceSet.contains("tab.contribute")
                                "shop" -> purifySpaceSet.contains("tab.shop")
                                "bangumi" -> purifySpaceSet.contains("tab.bangumi")
                                "cheese" -> purifySpaceSet.contains("tab.cheese")
                                else -> false
                            }
                        }
                    }
                }

                ogvApiResponseClass -> if (sPrefs.getBoolean("allow_download", false)) {
                    result.getObjectFieldOrNullAs<ArrayList<*>>("result")?.forEach {
                        it?.setIntField("isPlayable", 1)
                    }
                }

                ogvApiResponseV2Class -> if (sPrefs.getBoolean("allow_download", false)) {
                    result.getObjectFieldOrNull("data")
                        ?.getObjectFieldOrNullAs<MutableList<*>>("epPlayableParams")
                        ?.forEach { it?.setIntField("playableType", 0) }
                }

                dmAdvertClass -> if (hidden && sPrefs.getBoolean("block_up_rcmd_ads", false))
                    result.setObjectField("ads", null)

                shareChannelsClass -> if (unlockPlayLimit) runCatchingOrNull {
                    val belowChannels = result.getObjectFieldAs<ArrayList<Any>?>("belowChannels")
                        .takeUnless { it.isNullOrEmpty() } ?: return@processFastJsonResult origResult
                    var alreadyHas = false
                    var toInsertIdx = -1
                    belowChannels.forEachIndexed { idx, item ->
                        val shareChannel = item.getObjectFieldAs<String?>("shareChannel")
                        if (shareChannel == "PLAY_BACKGROUND_OFF")
                            toInsertIdx = idx
                        if (shareChannel == "LISTEN")
                            alreadyHas = true
                    }
                    if (alreadyHas || toInsertIdx == -1)
                        return@processFastJsonResult origResult
                    val listenChannel = channelItemClass?.new()
                        ?.setObjectField("name", "听视频")
                        ?.setObjectField("shareChannel", "LISTEN")
                        ?.setObjectField(
                            "picture",
                            "https://i0.hdslb.com/bfs/share/f88d8c420a59ff1ca5975b38722408056e7337b7.png"
                        ) ?: return@processFastJsonResult origResult
                    belowChannels.add(toInsertIdx, listenChannel)
                }
            }
            origResult
        }

        // 新版客户端的 fastjson 委托链中，各 parseObject 重载不再经过
        // (String, Type, int, Feature[]) 四参方法，需同时覆盖三个入口：
        // 缓存加载走 (String, Class)，网络解析走 (String, Type, Feature[])
        instance.fastJsonClass?.hookMethod(
            instance.fastJsonParse(),
            String::class.java,
            Type::class.java,
            Int::class.javaPrimitiveType,
            "com.alibaba.fastjson.parser.Feature[]"
        ) { chain ->
            processFastJsonResult(chain.proceed())
        }

        instance.fastJsonClass?.hookMethod(
            instance.fastJsonParse(),
            String::class.java,
            Type::class.java,
            "com.alibaba.fastjson.parser.Feature[]"
        ) { chain ->
            processFastJsonResult(chain.proceed())
        }

        instance.fastJsonClass?.hookMethod(
            instance.fastJsonParse(),
            String::class.java,
            Class::class.java
        ) { chain ->
            processFastJsonResult(chain.proceed())
        }

        val searchRankClass = "com.bilibili.search.api.SearchRank".findClassOrNull(mClassLoader)
        val searchGuessClass =
            "com.bilibili.search.api.SearchReferral\$Guess".findClassOrNull(mClassLoader)
        val searchRankV2Class = "com.bilibili.search2.api.SearchRank".findClassOrNull(mClassLoader)
        val searchGuessV2Class = "com.bilibili.search2.api.SearchReferral\$Guess".findClassOrNull(mClassLoader)

        instance.fastJsonClass?.hookMethod(
            "parseArray",
            String::class.java,
            Class::class.java
        ) { chain ->
            val result = chain.proceed()
            @Suppress("UNCHECKED_CAST")
            val list = result as? MutableList<Any>
            when (chain.args[1] as Class<*>) {
                searchRankClass, searchGuessClass, searchRankV2Class, searchGuessV2Class ->
                    if (sPrefs.getBoolean("purify_search", false) && sPrefs.getBoolean(
                            "hidden",
                            false
                        )
                    ) {
                        list?.clear()
                    }
            }
            result
        }

        instance.fastJsonClass?.hookMethod(
            instance.fastJsonParse(),
            String::class.java,
            Type::class.java,
            "com.alibaba.fastjson.parser.Feature[]"
        ) { chain ->
            val origResult = chain.proceed()
            var returnResult = origResult
            var result = origResult ?: return@hookMethod null
            if (result.javaClass == instance.generalResponseClass)
                result = result.getObjectField("data") ?: return@hookMethod origResult

            when (result.javaClass) {
                liveShoppingInfoClass -> {
                    if (hidden && purifyLivePopups.contains("shoppingCard")) {
                        result.setObjectField("shoppingCardDetail", null)
                        result.runCatchingOrNull {
                            setObjectField("recommendCardDetail", null)
                        }
                    }
                    if (hidden && purifyLivePopups.contains("shoppingSelected"))
                        result.runCatchingOrNull {
                            setObjectField("selectedGoods", null)
                        }
                }

                liveGoodsCardInfoClass, liveRecommendCardGoodsClass -> {
                    if (hidden && purifyLivePopups.contains("shoppingCard"))
                        returnResult = null
                }

                biliLiveRoomInfoClass -> {
                    if (hidden && purifyLivePopups.contains("follow"))
                        result.getObjectFieldOrNull("functionCard")
                            ?.setObjectField("followCard", null)
                    if (hidden && purifyLivePopups.contains("banner"))
                        result.setObjectField("bannerInfo", null)
                    if (hidden && sPrefs.getBoolean("no_live_mask", false))
                        result.setObjectField("areaMaskInfo", null)
                }

                liveRoomRecommendCardClass -> {
                    if (hidden && purifyLivePopups.contains("follow"))
                        returnResult = null
                }

                liveRoomReserveInfoClass -> {
                    if (hidden && purifyLivePopups.contains("reserve"))
                        result.setBooleanField("showReserveDetail", false)
                }

                biliLiveRoomUserInfoClass -> {
                    if (hidden && purifyLivePopups.contains("gift"))
                        result.getObjectFieldOrNull("functionCard")
                            ?.setObjectField("sengGiftCard", null)
                    if (hidden && purifyLivePopups.contains("task"))
                        result.runCatchingOrNull {
                            setObjectField("taskInfo", null)
                        }
                }

                liveShoppingGotoBuyInfoClass -> {
                    if (hidden && purifyLivePopups.contains("gotoBuy"))
                        returnResult = null
                }
            }
            returnResult
        }

        // 新版首页框架（khome）的底栏数据不走 fastjson TabResponse，
        // 单独定位 hook；失败不影响上面的旧框架路径
        runCatching { hookHomeFrameBottom() }.onFailure {
            Log.e("hookHomeFrameBottom error: $it")
        }
        // 旧首页框架（TabHost）的底栏同样不再经过 fastjson 委托链，按
        // Innocent_Lab 的方式直接 hook TabHost 单项绑定入口
        runCatching { hookTabHostBottom() }.onFailure {
            Log.e("hookTabHostBottom error: $it")
        }
        // 净化首页标签：新旧两套首页框架各装一层，参考 Innocent_Lab 的
        // HomeTabFilterFeatureInstaller；不勾选任何标签时只扫描，供设置弹窗列出
        runCatching { hookHomeFrameTabs() }.onFailure {
            Log.e("hookHomeFrameTabs error: $it")
        }
        runCatching { hookLegacyHomeTabs() }.onFailure {
            Log.e("hookLegacyHomeTabs error: $it")
        }
        runCatching { hookSplashPurify() }.onFailure {
            Log.e("hookSplashPurify error: $it")
        }
    }

    // ---- 新版首页框架（khome）底栏 ----
    // 8.92.1 起宿主同时带两套首页框架，新框架（平板/宽屏或服务端开关）由 Compose
    // 按 HomeTabData（kotlinx 序列化）渲染底栏，不经过上面的 fastjson TabResponse，
    // 旧框架仍走原路径，两条路径并存、任一框架生效即可。
    // 定位不依赖混淆名（类名每版都变）：未混淆锚点 HomeFrameViewModel 的字段泛型
    // 两跳内收集候选类，用 Companion.serializer() 的 serialName 精确比对（协议名
    // R8 不改），再按 dex 字段排序规则（名字、再类型描述符）把元素名映射到字段。
    private class HomeTabModel(
        val dataClass: Class<*>,
        val bottomField: Field,
        val tabField: Field?,
        val itemClass: Class<*>,
        val nameField: Field,
        val uriField: Field,
        val idField: Field?,
        val tabIdField: Field,
        val defaultSelectedField: Field
    )

    private fun hookHomeFrameBottom() {
        val model = resolveHomeFrameModel() ?: return
        Log.d("startHook: HomeFrameBottom")
        model.dataClass.hookAllConstructors { chain ->
            chain.proceed()
            val self = chain.thisObject ?: return@hookAllConstructors null
            val bottom = runCatching { model.bottomField.get(self) as? List<*> }
                .getOrNull() ?: return@hookAllConstructors null
            bottomItems.clear()
            val hides = sPrefs.getStringSet("hided_bottom_items", mutableSetOf())!!
            val filtered = bottom.filter { item ->
                if (item == null || !model.itemClass.isInstance(item))
                    return@filter true
                val name = model.nameField.get(item) as? String
                val uri = model.uriField.get(item) as? String
                val id = model.tabIdField.get(item) as? String
                val showing = id !in hides
                bottomItems.add(BottomItem(name, uri, id, showing))
                // 服务端默认选中项（首页）删掉后宿主会失去初始页，强制保留
                showing || runCatching {
                    model.defaultSelectedField.getInt(item) != 0
                }.getOrDefault(false)
            }
            if (filtered.size != bottom.size) {
                runCatching { model.bottomField.set(self, filtered) }
            }
            null
        }
    }

    private fun resolveHomeTabModel(): HomeTabModel? {
        val viewModel = "tv.danmaku.bili.khome.vm.HomeFrameViewModel"
            .findClassOrNull(mClassLoader) ?: return null
        val serialization = HomeTabSerialization.load(mClassLoader) ?: return null
        val dataClass = homeTabCandidateClasses(viewModel).firstOrNull { candidate ->
            serialization.describe(candidate)?.serialName == HOME_TAB_DATA_SERIAL
        } ?: return null
        val dataShape = serialization.describe(dataClass) ?: return null
        val dataFields = orderedInstanceFields(dataClass)
        if (!matchesSerialDescriptor(dataClass, dataFields, dataShape)) return null
        val bottomField = dataFields.getOrNull(dataShape.elements.indexOf("bottom"))
            ?.takeIf { List::class.java.isAssignableFrom(it.type) } ?: return null
        val itemClass = listItemClass(bottomField) ?: return null
        val itemShape = serialization.describe(itemClass)
            ?.takeIf { it.serialName == HOME_TAB_ITEM_SERIAL } ?: return null
        val itemFields = orderedInstanceFields(itemClass)
        if (!matchesSerialDescriptor(itemClass, itemFields, itemShape)) return null
        fun itemField(element: String, type: Class<*>): Field? =
            itemFields.getOrNull(itemShape.elements.indexOf(element))
                ?.takeIf { it.type == type }
        val nameField = itemField("name", String::class.java) ?: return null
        val uriField = itemField("uri", String::class.java) ?: return null
        val tabIdField = itemField("tab_id", String::class.java) ?: return null
        // 顶栏标签的净化键优先取 id（与旧框架资源的第一个 String 字段同源），
        // 部分宿主没有该元素时回退到 uri/name
        val idField = itemField("id", String::class.java)
        // 顶栏 tab 与底栏 bottom 共用同一条目类型；缺失或类型不符时只装底栏
        val tabField = dataFields.getOrNull(dataShape.elements.indexOf("tab"))
            ?.takeIf {
                List::class.java.isAssignableFrom(it.type) && listItemClass(it) == itemClass
            }
        // default_selected 是"默认选中项不得删除"保护的前提，读不到就整层放弃，
        // 避免勾中的首页页签被静默删掉导致宿主失去初始页。
        val defaultSelectedField = itemField(
            "default_selected", Int::class.javaPrimitiveType!!
        ) ?: return null
        (dataFields + itemFields).forEach { it.isAccessible = true }
        return HomeTabModel(
            dataClass, bottomField, tabField, itemClass,
            nameField, uriField, idField, tabIdField, defaultSelectedField
        )
    }

    /** ViewModel 字段泛型里出现的类，再加它们各自的实例字段类型（两跳）。 */
    private fun homeTabCandidateClasses(viewModel: Class<*>): List<Class<*>> {
        val first = LinkedHashSet<Class<*>>()
        instanceFields(viewModel).forEach { collectGenericTypes(it.genericType, first, 0) }
        val all = LinkedHashSet(first)
        first.forEach { owner -> instanceFields(owner).forEach { all += it.type } }
        return all.filter(::isHostAppClass)
    }

    private fun collectGenericTypes(type: Type, sink: MutableSet<Class<*>>, depth: Int) {
        if (depth > MAX_GENERIC_DEPTH) return
        when (type) {
            is Class<*> -> sink += type
            is ParameterizedType -> {
                collectGenericTypes(type.rawType, sink, depth + 1)
                type.actualTypeArguments.forEach { collectGenericTypes(it, sink, depth + 1) }
            }
            is WildcardType ->
                type.upperBounds.forEach { collectGenericTypes(it, sink, depth + 1) }
            is GenericArrayType ->
                collectGenericTypes(type.genericComponentType, sink, depth + 1)
        }
    }

    private fun isHostAppClass(type: Class<*>): Boolean =
        !type.isPrimitive && !type.isArray && !type.isInterface &&
            SKIPPED_PACKAGES.none { type.name.startsWith(it) }

    private fun instanceFields(owner: Class<*>): List<Field> =
        owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) }

    /**
     * 按 dex field_ids 排序规则（字段名，其次类型描述符）重建声明顺序；
     * Class.getDeclaredFields() 不承诺顺序，不能直接用。
     */
    private fun orderedInstanceFields(owner: Class<*>): List<Field> =
        instanceFields(owner).sortedWith(compareBy({ it.name }, { descriptorOf(it.type) }))

    private fun descriptorOf(type: Class<*>): String = when {
        type.isArray -> "[" + descriptorOf(type.componentType!!)
        type == Int::class.javaPrimitiveType -> "I"
        type == Long::class.javaPrimitiveType -> "J"
        type == Boolean::class.javaPrimitiveType -> "Z"
        type == Byte::class.javaPrimitiveType -> "B"
        type == Short::class.javaPrimitiveType -> "S"
        type == Char::class.javaPrimitiveType -> "C"
        type == Float::class.javaPrimitiveType -> "F"
        type == Double::class.javaPrimitiveType -> "D"
        type == Void.TYPE -> "V"
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    /**
     * 字段数与元素数相同，且存在参数窗口与字段类型序列逐个相同的构造器
     * （反序列化构造器 `(int seen, 元素…)` 或主构造器 `(元素…)`）。
     */
    private fun matchesSerialDescriptor(
        owner: Class<*>,
        fields: List<Field>,
        shape: HomeTabSerialization.Shape
    ): Boolean {
        if (fields.size != shape.elements.size || fields.isEmpty()) return false
        val types = fields.map { it.type }
        return owner.declaredConstructors.any { constructor ->
            val params = constructor.parameterTypes.toList()
            params == types || (params.size >= types.size + 1 &&
                params[0] == Int::class.javaPrimitiveType &&
                params.subList(1, types.size + 1) == types)
        }
    }

    private fun listItemClass(field: Field): Class<*>? =
        ((field.genericType as? ParameterizedType)?.actualTypeArguments?.singleOrNull())
            ?.let { argument ->
                when (argument) {
                    is Class<*> -> argument
                    is WildcardType -> argument.upperBounds.singleOrNull() as? Class<*>
                    else -> null
                }
            }

    /** 只通过 kotlinx 公开接口读描述符；接口与访问器名不参与混淆。 */
    private class HomeTabSerialization(
        private val kSerializer: Class<*>,
        private val getDescriptor: Method,
        private val getSerialName: Method,
        private val getElementsCount: Method,
        private val getElementName: Method
    ) {
        class Shape(val serialName: String, val elements: List<String>)

        fun describe(owner: Class<*>): Shape? = runCatching {
            val companion = owner.declaredFields
                .filter { Modifier.isStatic(it.modifiers) }
                .firstNotNullOfOrNull { field ->
                    val factory = field.type.declaredMethods.singleOrNull { method ->
                        !Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
                            kSerializer.isAssignableFrom(method.returnType)
                    } ?: return@firstNotNullOfOrNull null
                    field.isAccessible = true
                    factory.isAccessible = true
                    field.get(null)?.let { factory.invoke(it) }
                } ?: return@runCatching null
            val descriptor = getDescriptor.invoke(companion) ?: return@runCatching null
            val count = getElementsCount.invoke(descriptor) as Int
            Shape(
                serialName = getSerialName.invoke(descriptor) as String,
                elements = (0 until count).map { getElementName.invoke(descriptor, it) as String }
            )
        }.getOrNull()

        companion object {
            fun load(loader: ClassLoader): HomeTabSerialization? {
                val serializer = "kotlinx.serialization.KSerializer"
                    .findClassOrNull(loader) ?: return null
                val descriptor = "kotlinx.serialization.descriptors.SerialDescriptor"
                    .findClassOrNull(loader) ?: return null
                return runCatching {
                    HomeTabSerialization(
                        kSerializer = serializer,
                        getDescriptor = serializer.getMethod("getDescriptor"),
                        getSerialName = descriptor.getMethod("getSerialName"),
                        getElementsCount = descriptor.getMethod("getElementsCount"),
                        getElementName = descriptor.getMethod(
                            "getElementName", Int::class.javaPrimitiveType
                        )
                    )
                }.getOrNull()
            }
        }
    }

    // ---- 旧首页框架（TabHost）底栏 ----
    // 参考 Innocent_Lab locateBottomBar/installTabHostLayer：TabHost 有唯一
    // getTabs() 与唯一 (int, View) 绑定方法（新版混淆名为 q）；在绑定回调里读
    // 全量条目，按值形状提取名称/路由/tab_id 填充 bottomItems，命中隐藏的条目
    // 视图置 GONE。条目 String 字段无语义，按 dex 序（名字排序）为 名称/图标/
    // 选中图标/id/tab_id/气泡路由/路由/换肤图标。
    private fun hookTabHostBottom() {
        val hostClass = BOTTOM_TAB_HOST_CLASSES.firstNotNullOfOrNull {
            it.findClassOrNull(mClassLoader)
        } ?: return
        val tabsGetter = hostClass.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.name == "getTabs" &&
                it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType)
        }.distinctBy(Method::toGenericString).singleOrNull() ?: return
        val itemClass = (tabsGetter.genericReturnType as? ParameterizedType)
            ?.actualTypeArguments?.singleOrNull() as? Class<*> ?: return
        val bindMethod = hostClass.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, View::class.java)
                )
        } ?: return
        // dex field_ids 按（名字、再类型描述符）排序，按名字排序即还原声明顺序
        val stringFields = itemClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && it.type == String::class.java
        }.sortedBy { it.name }.onEach { it.isAccessible = true }
        if (stringFields.isEmpty()) return
        tabsGetter.isAccessible = true
        Log.d("startHook: TabHostBottom")
        bindMethod.hookMethod { chain ->
            chain.proceed()
            val host = chain.thisObject ?: return@hookMethod null
            val view = chain.args.getOrNull(1) as? View ?: return@hookMethod null
            val tabs = runCatching { tabsGetter.invoke(host) as? List<*> }.getOrNull()
                ?.filter { it != null && itemClass.isInstance(it) }
                ?: return@hookMethod null
            val hides = sPrefs.getStringSet("hided_bottom_items", mutableSetOf())!!
            bottomItems.clear()
            val hiddenFlags = tabs.map { item ->
                val strings = stringFields.mapNotNull { field ->
                    runCatching { field.get(item) as? String }.getOrNull()
                        ?.trim()?.takeIf(String::isNotEmpty)
                }
                // 第一个不含 :// 的短值是名称；排除 http/https/file（图标与换肤
                // 资源）后最后一个含 :// 的值是路由；tab_id 取不含 :// 的最后
                // 一个短值（dex 序上位于名称与 id 之后）
                val name = strings.firstOrNull { !it.contains("://") && it.length <= 12 }
                val uri = strings.lastOrNull { value ->
                    value.contains("://") &&
                        value.substringBefore("://").lowercase() !in NON_ROUTE_SCHEMES
                }
                val id = strings.lastOrNull { !it.contains("://") && it.length <= 32 }
                    ?: name
                val showing = id !in hides
                bottomItems.add(BottomItem(name, uri, id, showing))
                !showing
            }
            // 至少保留一个可见页签（与 Innocent_Lab canHide 同口径）
            val hiddenCount = hiddenFlags.count { it }
            if (hiddenCount == 0 || hiddenCount >= tabs.size) return@hookMethod null
            val index = (chain.args.getOrNull(0) as? Number)?.toInt()
                ?: return@hookMethod null
            if (hiddenFlags.getOrNull(index) != true) return@hookMethod null
            view.visibility = View.GONE
            view.isClickable = false
            view.isEnabled = false
            view.alpha = 0f
            null
        }
    }

    // ---- 净化首页标签 ----
    // 参考 Innocent_Lab HomeTabFilterFeatureInstaller：新旧两套首页框架各一层，
    // 条目都来自服务端同一份标签配置，净化键统一按 id → uri 生成，跨框架通用。
    // 不勾选任何项时只扫描，设置弹窗（自定义底栏同款多选）据此列出标签元素。
    private val homeTabDepth = ThreadLocal<IntArray>()
    private var homeFrameModel: HomeTabModel? = null

    /** 底栏与顶栏标签共用一次定位结果，避免重复扫描候选类。 */
    private fun resolveHomeFrameModel(): HomeTabModel? =
        homeFrameModel ?: resolveHomeTabModel()?.also { homeFrameModel = it }

    private fun homeTabKey(id: String?, uri: String?): String? {
        fun normalize(value: String) = value.trim().replace(WHITESPACE, " ")
        return id?.takeIf { it.isNotBlank() }?.let { "id:${normalize(it)}" }
            ?: uri?.takeIf { it.isNotBlank() }?.let { "uri:${normalize(it)}" }
    }

    /** 新首页框架（khome）：HomeTabData.tab 构造完成后剔除勾选的标签。 */
    private fun hookHomeFrameTabs() {
        val model = resolveHomeFrameModel() ?: return
        val tabField = model.tabField ?: return
        Log.d("startHook: HomeFrameTabs")
        model.dataClass.hookAllConstructors { chain ->
            // 默认参数构造器会嵌套委托给主构造器，只在最外层构造结束时处理一次，
            // 此时字段已是最终值，也避免重复扫描覆盖快照
            val counter = homeTabDepth.get() ?: IntArray(1).also { homeTabDepth.set(it) }
            counter[0]++
            try {
                chain.proceed()
            } finally {
                counter[0]--
            }
            if (counter[0] == 0) {
                runCatching { processHomeFrameTabs(model, tabField, chain.thisObject) }
                    .onFailure { Log.e("processHomeFrameTabs error: $it") }
            }
            null
        }
    }

    private fun processHomeFrameTabs(model: HomeTabModel, tabField: Field, self: Any?) {
        if (self == null) return
        val source = runCatching { tabField.get(self) as? List<*> }.getOrNull() ?: return
        val hides = sPrefs.getStringSet(HIDED_HOME_TAB_KEY, emptySet())!!
        homeTabItems.clear()
        val filtered = source.filter { item ->
            if (item == null || !model.itemClass.isInstance(item)) return@filter true
            val name = runCatching { model.nameField.get(item) as? String }.getOrNull()
            val uri = runCatching { model.uriField.get(item) as? String }.getOrNull()
            val id = model.idField?.let {
                runCatching { it.get(item) as? String }.getOrNull()
            }
            val key = homeTabKey(id, uri)
            val showing = key == null || key !in hides
            if (key != null) homeTabItems.add(HomeTabItem(name, uri, id, key, showing))
            // 服务端默认选中项（推荐）删掉后宿主会失去初始页，强制保留
            showing || runCatching {
                model.defaultSelectedField.getInt(item) != 0
            }.getOrDefault(false)
        }
        if (filtered.size != source.size) {
            runCatching { tabField.set(self, filtered) }
        }
    }

    /** 旧首页框架：HomeFragmentV2 的标签构建方法在交给宿主前过滤。 */
    private fun hookLegacyHomeTabs() {
        val build = LEGACY_HOME_FRAGMENT_CLASSES.asSequence()
            .mapNotNull { it.findClassOrNull(mClassLoader) }
            .flatMap { owner ->
                owner.declaredMethods.filter {
                    !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                        it.parameterCount == 1 &&
                        List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        List::class.java.isAssignableFrom(it.returnType) &&
                        it.genericParameterTypes.getOrNull(0)?.toString()
                            ?.contains("main2.resource.") == true
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .singleOrNull() ?: return
        val resourceClass = (build.genericParameterTypes[0] as? ParameterizedType)
            ?.actualTypeArguments?.singleOrNull() as? Class<*>
            ?: LEGACY_HOME_TAB_RESOURCE_CLASSES.firstNotNullOfOrNull {
                it.findClassOrNull(mClassLoader)
            }
            ?: return
        // 与 Innocent_Lab 同序：dex 字段序（此处同类型按名字排序）前三个 String
        // 依次是 id、标题、uri，tab_id 在第四个字段
        val stringFields = resourceClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && it.type == String::class.java
        }.sortedWith(compareBy({ it.name }, { descriptorOf(it.type) }))
            .onEach { it.isAccessible = true }
        if (stringFields.size < 3) return
        val idField = stringFields[0]
        val nameField = stringFields[1]
        val uriField = stringFields[2]
        build.isAccessible = true
        Log.d("startHook: LegacyHomeTabs")
        build.hookMethod { chain ->
            val source = chain.args.getOrNull(0) as? List<*> ?: return@hookMethod chain.proceed()
            val hides = sPrefs.getStringSet(HIDED_HOME_TAB_KEY, emptySet())!!
            homeTabItems.clear()
            val filtered = source.filter { item ->
                if (item == null || !resourceClass.isInstance(item)) return@filter true
                val id = runCatching { idField.get(item) as? String }.getOrNull()
                val name = runCatching { nameField.get(item) as? String }.getOrNull()
                val uri = runCatching { uriField.get(item) as? String }.getOrNull()
                val key = homeTabKey(id, uri)
                val showing = key == null || key !in hides
                if (key != null) homeTabItems.add(HomeTabItem(name, uri, id, key, showing))
                showing
            }
            // 全删光时原样放行（宿主拿到空列表会退回兜底配置），至少保留一个
            if (filtered.size == source.size || filtered.isEmpty())
                return@hookMethod chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = ArrayList(filtered)
            chain.proceed(args)
        }
    }

    // ---- 净化开屏页面（开屏广告）----
    // 参考 Innocent_Lab SplashAdFeatureInstaller，两道互不知情的防线：
    // 数据层 hook 开屏响应类的列表 getter 返回空表——不打在解析器上（fastjson/
    // Gson/kotlinx 均可能），候选类名 8.84–9.x 均为明文；决策层把选片决策
    // SplashOrder.isEmptyAd() 恒置 true，覆盖不经过列表 getter 的构造路径
    // （品牌预加载、事件开屏、本地缓存恢复），该方法 8.98.0 起才有，旧版自然
    // 降级为仅数据层。两道各自定位，互不拖累。
    private fun hookSplashPurify() {
        if (!sPrefs.getBoolean("purify_splash", false) ||
            !sPrefs.getBoolean("hidden", false)
        ) return
        SPLASH_RESPONSE_CLASSES.forEach { className ->
            val owner = className.findClassOrNull(mClassLoader) ?: return@forEach
            owner.methods.filter {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.name in SPLASH_LIST_GETTERS &&
                    List::class.java.isAssignableFrom(it.returnType)
            }.distinctBy(Method::toGenericString).forEach { getter ->
                getter.hookMethod {
                    // 调用方可能继续往返回列表里塞元素，必须给可变的空表
                    ArrayList<Any>(0)
                }
            }
        }
        "tv.danmaku.bili.splash.ad.model.SplashOrder".findClassOrNull(mClassLoader)
            ?.declaredMethods?.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.name == "isEmptyAd" &&
                    it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }?.hookMethod { true }
        // 展示层兜底：SplashManager.a(SplashOrder): BaseSplash 是所有开屏广告
        // 页面的唯一建页出口（选片/缓存/deeplink/COLD/CALL_UP/HOT 均汇于此），
        // 恒返回 null 即从根上阻止任何广告页创建，调用方对 null 均有判空；
        // 品牌页(2233娘)走 BrandSplashData，不经过此方法，不受影响。
        "tv.danmaku.bili.splash.ad.core.SplashManager".findClassOrNull(mClassLoader)
            ?.declaredMethods?.filter {
                Modifier.isStatic(it.modifiers) && it.name == "a" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].name ==
                        "tv.danmaku.bili.splash.ad.model.SplashOrder"
            }?.forEach { method ->
                method.hookMethod { null }
            }
    }

    private fun configTab(data: Any?, tabClass: Class<*>?) {
        val tab = data?.getObjectFieldAs<MutableList<Any>>("tab") ?: return
        if (tabClass == null) return

        var hasBangumiCN = false
        var hasBangumiTW = false
        var hasMovieCN = false
        var hasMovieTW = false
        var hasKoreaHK = false
        var hasKoreaTW = false
        tab.forEach {
            when (it.getObjectFieldAs<String>("uri")) {
                "bilibili://pgc/bangumi_v2",
                "bilibili://pgc/home" -> hasBangumiCN = true

                "bilibili://following/home_activity_tab/6544" -> hasBangumiTW = true

                "bilibili://pgc/cinema_v2",
                "bilibili://pgc/home?home_flow_type=2" -> hasMovieCN = true

                "bilibili://following/home_activity_tab/168644" -> hasMovieTW = true
                "bilibili://following/home_activity_tab/163541" -> hasKoreaHK = true
                "bilibili://following/home_activity_tab/95636" -> hasKoreaTW = true
            }
        }

        if (sPrefs.getBoolean("add_bangumi", false)) {
            if (!hasBangumiCN) {
                val bangumiCN = tabClass.new()
                    .setObjectField("tabId", "50")
                    .setObjectField("name", "追番（大陸）")
                    .setObjectField("uri", "bilibili://pgc/home")
                    .setObjectField("reportId", "bangumi")
                    .setIntField("pos", 50)
                tab.add(bangumiCN)
            }
            if (!hasBangumiTW) {
                val bangumiTW = tabClass.new()
                    .setObjectField("tabId", "60")
                    .setObjectField("name", "追番（港澳台）")
                    .setObjectField("uri", "bilibili://following/home_activity_tab/6544")
                    .setObjectField("reportId", "bangumi")
                    .setIntField("pos", 60)
                tab.add(bangumiTW)
            }
        }

        if (sPrefs.getBoolean("add_movie", false)) {
            if (!hasMovieCN) {
                val movieCN = tabClass.new()
                    .setObjectField("tabId", "70")
                    .setObjectField("name", "影視（大陸）")
                    .setObjectField("uri", "bilibili://pgc/home?home_flow_type=2")
                    .setObjectField("reportId", "film")
                    .setIntField("pos", 70)
                tab.add(movieCN)
            }
            if (!hasMovieTW) {
                val movieTW = tabClass.new()
                    .setObjectField("tabId", "80")
                    .setObjectField("name", "戏剧（港澳台）")
                    .setObjectField("uri", "bilibili://following/home_activity_tab/168644")
                    .setObjectField("reportId", "jptv")
                    .setIntField("pos", 80)
                tab.add(movieTW)
            }
        }

        if (sPrefs.getBoolean("add_korea", false)) {
            if (!hasKoreaHK) {
                val koreaHK = tabClass.new()
                    .setObjectField("tabId", "803")
                    .setObjectField("name", "韩综（港澳）")
                    .setObjectField("uri", "bilibili://following/home_activity_tab/163541")
                    .setObjectField("reportId", "koreavhk")
                    .setIntField("pos", 803)
                tab.add(koreaHK)
            }
            if (!hasKoreaTW) {
                val koreaTW = tabClass.new()
                    .setObjectField("tabId", "804")
                    .setObjectField("name", "韩综（台湾）")
                    .setObjectField("uri", "bilibili://following/home_activity_tab/95636")
                    .setObjectField("reportId", "koreavtw")
                    .setIntField("pos", 804)
                tab.add(koreaTW)
            }
        }
    }

    data class BottomItem(
        val name: String?,
        val uri: String?,
        val id: String?,
        var showing: Boolean
    )

    data class HomeTabItem(
        val name: String?,
        val uri: String?,
        val id: String?,
        val key: String,
        var showing: Boolean
    )
}
