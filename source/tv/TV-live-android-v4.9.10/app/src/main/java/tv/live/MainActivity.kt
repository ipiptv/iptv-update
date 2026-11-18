package tv.live

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPInputStream

class MainActivity : Activity() {

    /** 后台任务统一走这个池子，代替已废弃的 AsyncTask */
    private val bgPool = java.util.concurrent.Executors.newFixedThreadPool(2)
    /** 长按确定键的时间阈值：超时即视为长按（呼出底部信息条） */
    private val CENTER_LONG_PRESS_MS = 350L
    private val centerHandler = Handler(Looper.getMainLooper())
    private var centerDownPending = false
    private var centerLongPressFired = false
    private val centerLongPressRunnable = Runnable {
        centerLongPressFired = true
        channelsOfGroup(groupIndex).getOrNull(channelIndex)?.let { showOsd(it, channelIndex) }
    }
    private fun runBg(task: Runnable) = bgPool.execute(task)

    companion object {
        const val DEFAULT_URL = "https://gitee.com/IptvIptv/Iptv/raw/master/bbxx365_hotel.m3u"
        /** 没画面多久算失败、静默切下一条线路（5 秒） */
        const val TIMEOUT_MS = 5000L
        const val PANEL_AUTO_HIDE_MS = 5000L
        const val NUMBER_COMMIT_MS = 1500L
        /** 进入软件后多久才检查静默更新（2 分钟） */
        const val SILENT_UPDATE_DELAY_MS = 120000L
        /** 静默更新最短间隔（每天一次） */
        const val SILENT_UPDATE_INTERVAL_MS = 24 * 3600 * 1000L
        /** 首帧成功后多久解析列表（把 CPU 让给解码） */
        const val LIST_PARSE_AFTER_FRAME_MS = 2000L
        /** 首帧一直没出来的兜底解析时间 */
        const val LIST_PARSE_FALLBACK_MS = 8000L
        /** 首帧成功后多久拉节目单（错开更新任务） */
        const val EPG_DELAY_MS = 60000L
    }

    private lateinit var video: VideoView
    private lateinit var loading: LoadingSpinner
    private lateinit var tvTime: TextView
    private lateinit var panelContainer: View

    private lateinit var groupListRv: RecyclerView
    private lateinit var channelListRv: RecyclerView
    private lateinit var epgListRv: RecyclerView
    private lateinit var epgContainer: View
    private lateinit var epgHint: TextView

    private lateinit var numberInput: TextView
    private lateinit var bottomBar: View
    private val bottomBarHandler = Handler(Looper.getMainLooper())
    private val bottomBarHide = Runnable {
        if (panelState == 0) bottomBar.visibility = View.GONE
    }

    // 底部信息条
    private lateinit var bottomNum: TextView
    private lateinit var bottomName: TextView
    private lateinit var bottomNowProgram: TextView
    private lateinit var bottomNextProgram: TextView
    private lateinit var bottomNowRes: TextView
    private lateinit var bottomNextRes: TextView

    /** 二三级之间的竖排提示条 */
    private lateinit var hintStrip: TextView
    /** 三级节目单是否展开（默认收起，右键才出） */
    private var epgOpen = false
    /** 当前流的分辨率标签，例如 1080P；读不到就是空串 */
    private var currentRes = ""

    private lateinit var configOverlay: View
    private lateinit var qrImage: android.widget.ImageView
    private lateinit var configUrlText: TextView
    private lateinit var configCurrentText: TextView
    private var configServer: ConfigServer? = null

    private lateinit var settingsPanel: View
    private lateinit var settingsList: RecyclerView
    private lateinit var settingsTitle: TextView
    private lateinit var settingsHint: TextView
    private lateinit var settingsAdapter: ManageAdapter
    private val settingsItems = mutableListOf<ManageItem>()
    /** 设置层级：0=主页，1=订阅源，2=分组 */
    private var settingsLevel = 0
    private var settingsPos = 0

    private val allChannels = mutableListOf<Channel>()
    private val groups = mutableListOf<String>()

    private var groupIndex = 0
    private var channelIndex = 0
    private var sourceIndex = 0
    private var playCallbackFired = false

    /** 面板状态：0=关闭，1=菜单打开 */
    private var panelState = 0
    /** 三栏焦点：0=分组，1=频道，2=节目单 */
    private var focusIndex = 1

    private var groupPos = 0
    private var channelPos = 0
    private var epgPos = 0

    private var rowHeightPx = 0
    private var channelTextPx = 0f
    /** 左侧面板可用高度，右侧设置面板沿用同一套尺寸 */
    private var panelAvailPx = 0
    private var groupTextPx = 0f

    private val channelListData = mutableListOf<Channel>()
    private val epgData = mutableListOf<EpgProgram>()

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var epgAdapter: EpgAdapter

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { tryNextSource() }

    private val loadingHandler = Handler(Looper.getMainLooper())
    /** 从后台静默续播：不显示加载圈，避免每次回桌面再进来都转圈 */
    private var quietResume = false

    private val loadingRunnable = Runnable {
        if (!playCallbackFired && !quietResume) loading.visibility = View.VISIBLE
    }

    private val settingsHideHandler = Handler(Looper.getMainLooper())
    private val settingsHideRunnable = Runnable { if (settingsPanel.visibility == View.VISIBLE) closeSettings() }

    private fun armSettingsAutoHide() {
        settingsHideHandler.removeCallbacks(settingsHideRunnable)
        settingsHideHandler.postDelayed(settingsHideRunnable, PANEL_AUTO_HIDE_MS)
    }

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { closePanel() }

    private val numHandler = Handler(Looper.getMainLooper())
    private var numBuffer = ""
    private val numCommitRunnable = Runnable { commitNumberInput() }

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            tvTime.text = SimpleDateFormat("HH:mm EEE", Locale.CHINESE).format(Date())
            timeHandler.postDelayed(this, 30000)
        }
    }

    private val cacheFile: File get() = SourceStore.cacheFile(this)

    private val sp by lazy { getSharedPreferences("tv", MODE_PRIVATE) }
    private var epgStarted = false
    private var epgRequested = false

    // ============ 播放记忆按源分开保存：切源不覆盖，切回原源还能秒开 ============
    private fun memGet(base: String, srcId: String = SourceStore.currentId(this)): String? {
        val perSource = sp.getString(base + "_" + srcId, null)
        if (!perSource.isNullOrEmpty()) return perSource
        // 兼容旧版单键记忆
        return if (sp.getString("last_url_source", null) == srcId) sp.getString(base, null) else null
    }

    private fun memPut(base: String, value: String) {
        val id = SourceStore.currentId(this)
        sp.edit().putString(base + "_" + id, value).putString("last_url_source", id).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        video = findViewById(R.id.videoView)
        loading = findViewById(R.id.loading)
        tvTime = findViewById(R.id.tvTime)
        panelContainer = findViewById(R.id.panelContainer)
        groupListRv = findViewById(R.id.groupList)
        channelListRv = findViewById(R.id.channelList)
        epgListRv = findViewById(R.id.epgList)
        epgContainer = findViewById(R.id.epgContainer)
        epgHint = findViewById(R.id.epgHint)
        bottomBar = findViewById(R.id.bottomBar)
        bottomBar.visibility = View.GONE
        bottomNum = findViewById(R.id.bottomNum)
        bottomName = findViewById(R.id.bottomName)
        bottomNowProgram = findViewById(R.id.bottomNowProgram)
        bottomNextProgram = findViewById(R.id.bottomNextProgram)
        bottomNowRes = findViewById(R.id.bottomNowRes)
        bottomNextRes = findViewById(R.id.bottomNextRes)
        hintStrip = findViewById(R.id.hintStrip)
        numberInput = findViewById(R.id.numberInput)
        configOverlay = findViewById(R.id.configOverlay)
        qrImage = findViewById(R.id.qrImage)
        configUrlText = findViewById(R.id.configUrlText)
        configCurrentText = findViewById(R.id.configCurrentText)
        settingsPanel = findViewById(R.id.settingsPanel)
        settingsList = findViewById(R.id.settingsList)
        settingsTitle = findViewById(R.id.settingsTitle)
        settingsHint = findViewById(R.id.settingsHint)

        // 收藏功能已移除：清掉历史收藏数据，避免残留
        if (sp.contains("fav_channels")) sp.edit().remove("fav_channels").apply()
        applyClockVisibility()

        groupListRv.layoutManager = LinearLayoutManager(this)
        channelListRv.layoutManager = LinearLayoutManager(this)
        epgListRv.layoutManager = LinearLayoutManager(this)

        computeRowMetrics()

        groupAdapter = GroupAdapter(groups, 0, rowHeightPx, groupTextPx, false) { pos ->
            groupPos = pos
            groupAdapter.selected = pos
            groupAdapter.notifyDataSetChanged()
            groupListRv.scrollToPosition(pos)
            reloadChannelList(pos, 0)
            resetHideTimer()
        }
        channelAdapter = ChannelAdapter(channelListData, 0, rowHeightPx, channelTextPx, true,
            programProvider = { name -> currentProgramOf(name) },
            sourceInfoProvider = { ch -> sourceInfoOf(ch) },
            onClick = { pos ->
                channelPos = pos
                val ch = channelListData.getOrNull(pos)
                if (ch != null && isPlayingChannel(ch)) closePanel() else { playSelectedChannel(); closePanel() }
            },
            onLongClick = { pos ->
                channelPos = pos
            }
        )
        epgAdapter = EpgAdapter(epgData, 0, rowHeightPx, channelTextPx, true)

        groupListRv.adapter = groupAdapter
        channelListRv.adapter = channelAdapter
        epgListRv.adapter = epgAdapter

        settingsList.layoutManager = LinearLayoutManager(this)
        settingsAdapter = ManageAdapter(settingsItems, 0, rowHeightPx, channelTextPx) { pos ->
            settingsPos = pos
            activateSettingsItem()
        }
        settingsList.adapter = settingsAdapter

        // 启动优化：先用上次播放地址秒开，列表在后台异步解析
        // 只有上次地址属于当前使用的源才直接播，避免源切换后播到旧源的地址
        val curSourceId = SourceStore.currentId(this)
        val lastUrl = memGet("last_url", curSourceId)

        if (!lastUrl.isNullOrEmpty()) {
            playUrlDirect(lastUrl)
        } else {
            // 无可用记忆地址：1.2 秒后仍无画面才显示加载圈，减少启动渲染
            loadingHandler.postDelayed(loadingRunnable, 1200)
        }

        // 列表解析时间错开：有记忆地址时先让首帧，首帧成功后再解析；
        // 首帧迟迟不来则 8 秒兜底解析，保证按 OK 时列表能出来
        pendingAutoPlayFirst = lastUrl.isNullOrEmpty()
        requestListParse(if (pendingAutoPlayFirst) 0L else LIST_PARSE_FALLBACK_MS)
        // 兜底：20 秒还没列表，别再干转圈，提示并重试联网
        timeHandler.postDelayed(listWatchdog, LIST_WATCHDOG_MS)

        // 静默更新：进软件 2 分钟后再检查，完全不影响启动和当前播放
        scheduleSilentUpdate()
    }

    // ================= 列表解析（延后到首帧之后，避免抢解码CPU） =================
    private val LIST_WATCHDOG_MS = 20000L

    private val listWatchdog = Runnable {
        if (allChannels.isNotEmpty()) return@Runnable
        loading.visibility = View.GONE
        Toast.makeText(this, "内置频道列表没加载出来，正在联网重试", Toast.LENGTH_LONG).show()
        refreshFromNetwork(failToast = "频道列表获取失败，请检查网络后进设置刷新源")
    }



    private var listParsed = false
    private var pendingAutoPlayFirst = false

    private fun requestListParse(delayMs: Long) {
        if (listParsed) return
        timeHandler.removeCallbacks(listParseRunnable)
        timeHandler.postDelayed(listParseRunnable, delayMs)
    }

    private val listParseRunnable = Runnable {
        if (listParsed) return@Runnable
        listParsed = true
        Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                loadCache()
            } catch (e: Throwable) {
                // 解析失败（含内存不足）按无缓存处理，交给联网兜底
            }
            runOnUiThread {
                if (allChannels.isEmpty()) {
                    refreshFromNetwork()
                } else {
                    computeRowMetrics()
                    groupAdapter.notifyDataSetChanged()
                    if (pendingAutoPlayFirst) {
                        pendingAutoPlayFirst = false
                        playChannel(0, probe = false)
                    } else {
                        restoreSelectionFromPrefs()
                        syncPlayingStateWithUrl(memGet("last_url"), memGet("last_name"))
                    }
                    // 解析完成时如果面板已经打开，补上列表和节目单
                    if (panelState == 1) {
                        reloadChannelList(groupIndex, channelIndex)
                        refreshEpgPreview()
                    }
                }
            }
        }.start()
    }

    // ================= 静默更新（每天一次） =================
    private var silentUpdateStarted = false

    private fun scheduleSilentUpdate() {
        if (silentUpdateStarted) return
        silentUpdateStarted = true
        timeHandler.postDelayed({ silentUpdateNow() }, SILENT_UPDATE_DELAY_MS)
    }

    private fun silentUpdateNow() {
        val lastCheck = sp.getLong("last_silent_update", 0L)
        if (System.currentTimeMillis() - lastCheck < SILENT_UPDATE_INTERVAL_MS) return
        val sourceId = SourceStore.currentId(this)
        val sourceUrl = SourceStore.currentUrl(this)
        runBg {
            try {
                val (text, parsed) = M3UParser.fetchAndParse(sourceUrl)
                if (parsed.isEmpty()) return@runBg
                runOnUiThread {
                    // 期间用户可能已换源，换过就不覆盖
                    if (SourceStore.currentId(this) != sourceId) return@runOnUiThread
                    sp.edit().putLong("last_silent_update", System.currentTimeMillis()).apply()
                    val oldSize = allChannels.size
                    val oldHead = allChannels.take(60).joinToString("|") { it.name }
                    val newHead = parsed.take(60).joinToString("|") { it.name }
                    if (parsed.size == oldSize && oldHead == newHead) {
                        // 没有变化：保留原列表，什么都不做
                        return@runOnUiThread
                    }
                    // 有变化：写缓存 + 换内存列表，但不打断正在播放的画面
                    try {
                        cacheFile.writeText(text)
                    } catch (e: Exception) {
                    }
                    allChannels.clear()
                    allChannels.addAll(parsed)
                    buildGroups()
                    restoreSelectionFromPrefs()
                    groupAdapter.notifyDataSetChanged()
                    reloadChannelList(groupIndex, channelIndex)
                    refreshEpgPreview()
                }
            } catch (e: Exception) {
                // 静默失败：保留原列表
            }
        }
    }

    /** 直链起播时按上次地址找回对应频道，让底部条/节目单有频道名可用 */
    private fun syncPlayingStateWithUrl(url: String?, name: String?) {
        val ch = allChannels.firstOrNull { !url.isNullOrEmpty() && it.urls.contains(url) }
            ?: allChannels.firstOrNull { !name.isNullOrEmpty() && it.name == name }
            ?: return
        val gi = groups.indexOf(ch.group)
        if (gi < 0) return
        val ci = channelsOfGroup(gi).indexOfFirst { it.name == ch.name }
        if (ci < 0) return
        groupIndex = gi
        groupPos = gi
        channelIndex = ci
        channelPos = ci
        updateBottomBar(ch)
    }

    /** 已经按缓存地址在播、但还不知道是哪个频道：按 URL 反查，补编号和频道名 */
    private fun syncBottomBarWithPlaying() {
        val url = memGet("last_url") ?: return
        val ch = allChannels.firstOrNull { it.urls.contains(url) } ?: return
        val gi = groups.indexOf(ch.group)
        if (gi < 0) return
        val ci = channelsOfGroup(gi).indexOfFirst { it.name == ch.name }
        groupIndex = gi
        channelIndex = if (ci > 0) ci else 0
        groupPos = groupIndex
        channelPos = channelIndex
        updateBottomBar(ch)
    }

    /** 启动时按上次频道名恢复分组/频道位置，保证上下键和菜单都正确 */
    private fun restoreSelectionFromPrefs() {
        restoreSelectionByName(memGet("last_name"))
    }

    /** 设置 → 关于 → 检查更新：读仓库里的版本清单，有新版就弹窗确认后自动下载安装 */
    private fun startUpdateCheck() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        bgPool.execute {
            val remote = UpdateChecker.fetchRemote()
            runOnUiThread {
                if (remote == null) {
                    Toast.makeText(this, "检查更新失败，请稍后再试", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val cur = UpdateChecker.currentVersionCode(this)
                if (remote.versionCode <= cur) {
                    Toast.makeText(this, "已是最新版本 v" + appVersion(), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                confirmUpdate(remote)
            }
        }
    }

    /** 发现新版本：确认 → 下载 → 拉起系统安装器 */
    private fun confirmUpdate(remote: UpdateChecker.Remote) {
        val msg = StringBuilder()
        msg.append("当前版本 v").append(appVersion())
        msg.append("  →  新版本 v").append(remote.versionName).append("\n\n")
        if (remote.note.isNotEmpty()) msg.append(remote.note)
        android.app.AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(msg.toString())
            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(remote) }
            .setNegativeButton("以后再说", null)
            .show()
    }

    private fun downloadAndInstall(remote: UpdateChecker.Remote) {
        Toast.makeText(this, "开始下载 v" + remote.versionName + "…", Toast.LENGTH_SHORT).show()
        bgPool.execute {
            try {
                UpdateChecker.checkAndInstall(this, remote.apkUrl) { m ->
                    runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "更新失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** 时间显示开关（设置里可关） */
    private fun applyClockVisibility() {
        val on = sp.getBoolean("show_clock", true)
        tvTime.visibility = if (on) View.VISIBLE else View.GONE
        if (on) timeHandler.post(timeRunnable)
    }

    private fun appVersion(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.versionName ?: "1.0"
        } catch (e: Exception) {
            "4.5"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 85% 分位宽度：个别超长名字不拖累整栏 */
    private fun p85(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = (((sorted.size - 1) * 0.85f).toInt()).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun computeRowMetrics(group: Int = groupIndex) {
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        // 顶部/底部留白 24*2，底部信息条约 96dp
        // 上下各留 20dp 外边距 + 20dp 内边距，整体垂直居中
        val avail = (screenH - dp(80)).coerceAtLeast(dp(240))
        // 一级 10 行、二级 7 行，两栏总高度一致
        // 行高按“实际条数”均分整栏高度：不满 10/7 条时把空余平均摊开，不再挤在上方
        // 固定行高：一级按 10 行、二级按 8 行算；条数不足就少占位置，其余留空
        val groupRows = 10
        val channelRows = 8
        // 二级栏比一级栏底部多出 14dp，形成轻微错落的悬浮感
        val channelAvail = avail + dp(14)
        val groupRowH = (avail / groupRows).coerceAtLeast(dp(28))
        val channelRowH = (channelAvail / channelRows).coerceAtLeast(dp(40))
        // 字号仍按满栏 10/7 条计算，换组时字不会忽大忽小
        val baseGroupH = (avail / 10).coerceAtLeast(dp(28))
        val baseChannelH = (avail / 7).coerceAtLeast(dp(44))
        panelAvailPx = avail
        if (::settingsPanel.isInitialized) {
            val lpS2 = settingsPanel.layoutParams
            if (lpS2 != null && lpS2.height != avail) {
                lpS2.height = avail
                settingsPanel.layoutParams = lpS2
            }
        }
        rowHeightPx = groupRowH
        // 字号按屏幕高度自适应：以 1080p 为基准，大屏/高分屏自动放大，观感一致
        val uiScale = (screenH / 1080f).coerceIn(0.9f, 2.2f)
        // 一级、二级统一 32px 基准，两栏字号一致
        groupTextPx = 32f * uiScale
        channelTextPx = 32f * uiScale

        if (::groupAdapter.isInitialized) {
            groupAdapter.rowHeightPx = groupRowH
            groupAdapter.textSizePx = groupTextPx
            groupAdapter.notifyDataSetChanged()
        }
        if (::channelAdapter.isInitialized) {
            channelAdapter.rowHeightPx = channelRowH
            channelAdapter.textSizePx = channelTextPx
            channelAdapter.notifyDataSetChanged()
        }
        if (::epgAdapter.isInitialized) {
            epgAdapter.rowHeightPx = channelRowH
            epgAdapter.textSizePx = 34f * uiScale
            epgAdapter.notifyDataSetChanged()
        }
        if (::settingsAdapter.isInitialized) {
            settingsAdapter.rowHeightPx = groupRowH
            settingsAdapter.textSizePx = 32f * uiScale
            settingsAdapter.notifyDataSetChanged()
        }

        // 设置面板与左侧一致：高度拉满可用高度、垂直居中、留悬浮边；宽度按屏宽比例
        val settingsW = (screenW * 0.20f).toInt()
        if (::settingsPanel.isInitialized) {
            val lpS = settingsPanel.layoutParams
            if (lpS != null && (lpS.width != settingsW || lpS.height != avail)) {
                lpS.width = settingsW
                lpS.height = avail
                settingsPanel.layoutParams = lpS
            }
        }

        val lpGroup = groupListRv.layoutParams
        lpGroup.height = avail
        groupListRv.layoutParams = lpGroup

        val lpChannel = channelListRv.layoutParams
        lpChannel.height = channelAvail
        channelListRv.layoutParams = lpChannel

        val lpEpg0 = epgContainer.layoutParams
        lpEpg0.height = channelAvail
        epgContainer.layoutParams = lpEpg0

        // 三栏之间统一 2dp；竖排提示条单独占位
        val gap = dp(2)
        val hintW = dp(20)
        listOf(channelListRv, epgContainer).forEach { v ->
            val lp = v.layoutParams as android.view.ViewGroup.MarginLayoutParams
            lp.marginStart = gap
            v.layoutParams = lp
        }
        val lpHint = hintStrip.layoutParams
        lpHint.width = hintW
        lpHint.height = channelAvail
        hintStrip.layoutParams = lpHint

        val content = screenW - dp(36) // 左边距 24dp + 右边距 12dp
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // 一级栏：按分组名 85% 分位计算栏宽
        paint.textSize = groupTextPx
        val gList = groups.map { paint.measureText(it) }
        var w1 = ((if (gList.isEmpty()) dp(90).toFloat() else p85(gList)) + dp(34)).toInt()
        w1 = w1.coerceIn(dp(120), (content * 0.22f).toInt())

        // 二级栏：只按当前分组频道名的 85% 分位，超长名不撑栏
        paint.textSize = channelTextPx
        val numW = paint.measureText("888") + dp(8)
        val iconW = dp(16) + dp(8)
        val curList = channelsOfGroup(group)
        val pool = if (curList.isNotEmpty()) curList else allChannels
        val cList = pool.map { paint.measureText(it.name) }
        // 二级第二行还有“正在播放 + 节目名”，按名称宽度 1.7 倍给位，避免被面板边缘截断
        val nameW = if (cList.isEmpty()) dp(80).toFloat() else p85(cList)
        var w2 = (dp(4) + numW + iconW + nameW * 1.7f + dp(30)).toInt()
        w2 = w2.coerceIn(dp(200), (content * 0.40f).toInt())

        val w3 = (content - w1 - w2 - gap * 2 - hintW).coerceAtLeast(dp(320))

        val lp1 = groupListRv.layoutParams
        lp1.width = w1
        groupListRv.layoutParams = lp1

        val lp2 = channelListRv.layoutParams
        lp2.width = w2
        channelListRv.layoutParams = lp2

        val lp3 = epgContainer.layoutParams
        lp3.width = w3
        epgContainer.layoutParams = lp3
    }

    /** 升级兼容：旧版 channels.m3u 直接迁移到按源命名的缓存，避免强制联网 */
    private fun migrateLegacyCache() {
        try {
            val newFile = cacheFile
            val oldFile = File(filesDir, "channels.m3u")
            if (!newFile.exists() && oldFile.exists() && oldFile.length() > 0) {
                oldFile.copyTo(newFile, overwrite = true)
                oldFile.delete()
            }
        } catch (e: Exception) {
            // 迁移失败不影响主流程
        }
    }

    /** 内置源版本戳：每次重新打包内置源改这个值，升级安装后必定换成新内置源 */
    /** 内置源版本戳：每次重新打包内置源改这个值，升级安装后必定换成新内置源 */
    private val BUNDLED_STAMP = 2026111623L

    private fun cacheExists(): Boolean = cacheFile.exists() && cacheFile.length() > 0L

    /** 是否需要用内置源覆盖缓存：没缓存（新装/换机/清数据），或内置源版本更新过 */
    private fun bundledNeedApply(): Boolean =
        !cacheExists() || sp.getLong("bundled_stamp", 0L) < BUNDLED_STAMP

    /** 打开内置源：优先 gz（体积小），没有就退回明文 */
    private fun openBundledM3u(): java.io.InputStream {
        return try {
            GZIPInputStream(assets.open("default_channels.m3u.gz"))
        } catch (e: Throwable) {
            assets.open("default_channels.m3u")
        }
    }

    /** 流式解析内置源，不把整份文本读进内存 */
    private fun parseBundled(): List<Channel> = try {
        openBundledM3u().use { input ->
            M3UParser.parse(input.bufferedReader())
        }
    } catch (e: Throwable) {
        emptyList()
    }

    private fun parseCacheFile(): List<Channel> = try {
        if (!cacheExists()) emptyList()
        else cacheFile.inputStream().use { M3UParser.parse(it.bufferedReader()) }
    } catch (e: Throwable) {
        emptyList()
    }

    /** 把内置源原样落成缓存文件（拷贝流，不占内存），并记录版本戳 */
    private fun copyBundledToCache() {
        try {
            openBundledM3u().use { input ->
                cacheFile.outputStream().use { out -> input.copyTo(out) }
            }
            sp.edit().putLong("bundled_stamp", BUNDLED_STAMP).apply()
        } catch (e: Throwable) {
            // 写缓存失败不影响本次内存列表
        }
    }

    /**
     * 启动加载频道列表（流式，只解析一次）：
     * 1) 全新安装 / 换电视 / 缓存被清空 → 内置源直接落缓存并解析，不依赖网络
     * 2) 覆盖安装且内置源更新 → 内置源替换缓存
     * 3) 其余情况读本地缓存；缓存解析不出来再回退内置源
     * 只对“默认源”生效，用户自建源的缓存一律不动。
     */
    private fun loadCache() {
        migrateLegacyCache()
        val isDefault = SourceStore.currentId(this) == SourceStore.DEFAULT_SOURCE_ID
        var parsed: List<Channel> = emptyList()

        if (isDefault && bundledNeedApply()) {
            parsed = parseBundled()
            if (parsed.isNotEmpty()) copyBundledToCache()
        }
        if (parsed.isEmpty()) parsed = parseCacheFile()
        if (parsed.isEmpty() && isDefault) {
            // 缓存坏了：回到内置源兜底
            parsed = parseBundled()
        }
        if (parsed.isEmpty()) return

        allChannels.clear()
        allChannels.addAll(parsed)
        buildGroups()
    }

    /** 正在播放频道的线路信息：「源 当前/总数」，其他频道不显示 */
    private fun sourceInfoOf(ch: Channel): String? {
        val total = ch.urls.size
        if (total <= 0) return null
        // 正在播的频道显示真实源序号；其余显示记忆源，没记忆过就是第一个源
        val cur: Int = if (isPlayingChannel(ch)) {
            val last = memGet("last_url")
            val i = if (last.isNullOrEmpty()) -1 else ch.urls.indexOf(last)
            if (i >= 0) i + 1 else (sourceIndex + 1).coerceIn(1, total)
        } else {
            (SourceMemory.get(this, ch.group, ch.name) ?: 0) + 1
        }
        return "源 " + cur.coerceIn(1, total) + "/" + total
    }


    /** 全部分组（含已隐藏），按保存的顺序排：分组管理页和排序都用它 */
    private fun groupsInSavedOrder(): List<String> {
        val raw = rawGroupNames()
        val order = GroupStore.loadOrder(this)
        val out = ArrayList<String>(raw.size)
        order.forEach { g -> if (raw.contains(g) && !out.contains(g)) out.add(g) }
        raw.forEach { if (!out.contains(it)) out.add(it) }
        return out
    }

    private fun rawGroupNames(): List<String> {
        val set = LinkedHashSet<String>()
        allChannels.forEach { set.add(it.group) }
        return set.toList()
    }

    /** 分组 → 该组频道列表，换分组建一次，避免每次上下键都全表 filter */
    private val groupCache = HashMap<String, List<Channel>>()

    /** 取某个分组的频道列表（走缓存，不重复 filter） */
    private fun channelsOfGroup(pos: Int): List<Channel> =
        groups.getOrNull(pos)?.let { groupCache[it] ?: emptyList() } ?: emptyList()

    private fun buildGroups() {
        groups.clear()
        val applied = GroupStore.apply(this, rawGroupNames())
        // 兜底：分组全被隐藏会让左侧列表彻底空白（画面还在播），此时放行全部分组
        groups.addAll(if (applied.isEmpty()) rawGroupNames() else applied)
        allChannels.forEachIndexed { i, ch -> ch.number = i + 1 }
        rebuildGroupCache()
        groupIndex = 0
        channelIndex = 0
        groupPos = 0
        channelPos = 0
    }

    private fun rebuildGroupCache() {
        groupCache.clear()
        for (g in groups) {
            groupCache[g] = allChannels.filter { it.group == g }
        }
    }


    /** 刷新当前源：清掉本地缓存，强制重新联网拉取 */
    private fun refreshCurrentSource() {
        val cur = currentSourceItem()
        closeSettings()
        Toast.makeText(this, "正在刷新「" + (cur?.name ?: "当前源") + "」", Toast.LENGTH_SHORT).show()
        // 不删缓存、不清列表：新列表拉取成功才替换，失败就继续用原来的
        refreshFromNetwork(failToast = "刷新失败，已保留原有频道列表")
    }

    private var listLoading = false

    /** 选中某个频道名（不改播放状态） */
    private fun restoreSelectionByName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val ch = allChannels.firstOrNull { it.name == name } ?: return false
        val gi = groups.indexOf(ch.group)
        if (gi < 0) return false
        val ci = channelsOfGroup(gi).indexOf(ch)
        if (ci < 0) return false
        groupIndex = gi
        channelIndex = ci
        groupPos = gi
        channelPos = ci
        sourceIndex = 0
        return true
    }

    /** 上次只记了播放地址没记频道名时，用地址反查频道，补上选择状态 */
    private fun syncSelectionByUrl() {
        val u = memGet("last_url") ?: return
        val ch = allChannels.firstOrNull { it.urls.contains(u) } ?: return
        restoreSelectionByName(ch.name)
    }

    private fun playCurrentChannel() {
        val list = channelsOfGroup(groupIndex)
        if (list.isEmpty()) return
        channelIndex = channelIndex.coerceIn(0, list.size - 1)
        playChannel(channelIndex, probe = false)
    }

    /**
     * 联网拉取当前源的列表。
     * 只有拉到内容并解析成功才替换缓存和内存列表；失败时保留原有列表，只弹提示。
     */
    private fun refreshFromNetwork(
        failToast: String = "列表获取失败",
        preferredName: String? = null
    ) {
        if (listLoading) return
        listLoading = true
        loading.visibility = View.VISIBLE
        val srcId = SourceStore.currentId(this)
        runBg {
            try {
                val (text, parsed) = M3UParser.fetchAndParse(SourceStore.currentUrl(this))
                if (parsed.isEmpty()) throw IllegalStateException("empty list")
                runOnUiThread {
                    if (SourceStore.currentId(this) != srcId) {
                        // 期间用户又换了源，这次结果作废，不覆盖新源的列表
                        listLoading = false
                        loading.visibility = View.GONE
                        return@runOnUiThread
                    }
                    try {
                        cacheFile.writeText(text)
                    } catch (e: Exception) {
                    }
                    sp.edit().putLong("last_auto_update", System.currentTimeMillis()).apply()
                    // 列表被重新拉取：源地址整体可能变了，旧记忆作废
                    SourceMemory.clear(this)
                    allChannels.clear()
                    allChannels.addAll(parsed)
                    buildGroups()
                    groupAdapter.notifyDataSetChanged()
                    channelAdapter.notifyDataSetChanged()
                    val name = preferredName ?: memGet("last_name", srcId)
                    if (!restoreSelectionByName(name)) {
                        groupIndex = 0
                        channelIndex = 0
                    }
                    playCurrentChannel()
                    // 更新当前源的刷新时间
                    val sources = SourceStore.load(this)
                    sources.firstOrNull { it.id == srcId }?.updatedAt = System.currentTimeMillis()
                    SourceStore.save(this, sources)
                    listLoading = false
                    loading.visibility = View.GONE
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    listLoading = false
                    loading.visibility = View.GONE
                    Toast.makeText(this, failToast, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 切换订阅源：旧列表和旧缓存全部保留，不删任何文件。
     * 先读目标源自己的缓存立即顶上（没变化不联网），再后台检查更新，成功才替换。
     */
    private fun switchToSource(srcId: String, srcName: String) {
        if (SourceStore.currentId(this) == srcId) {
            Toast.makeText(this, "这个源正在使用中", Toast.LENGTH_SHORT).show()
            return
        }
        SourceStore.setCurrentId(this, srcId)
        closeSettings()
        Toast.makeText(this, "已切换到「$srcName」", Toast.LENGTH_SHORT).show()
        runBg {
            val f = SourceStore.cacheFileFor(this, srcId)
            val cached = if (f.exists() && f.length() > 0L) {
                runCatching { M3UParser.parse(f.readText()) }.getOrDefault(emptyList())
            } else emptyList()
            runOnUiThread {
                if (SourceStore.currentId(this) != srcId) return@runOnUiThread
                if (cached.isNotEmpty()) {
                    allChannels.clear()
                    allChannels.addAll(cached)
                    buildGroups()
                    groupAdapter.notifyDataSetChanged()
                    channelAdapter.notifyDataSetChanged()
                    if (!restoreSelectionByName(memGet("last_name", srcId))) {
                        groupIndex = 0
                        channelIndex = 0
                    }
                    playCurrentChannel()
                }
                // 有缓存也顺手检查一次更新；没缓存则靠这次联网拿列表。失败都保留现有列表
                refreshFromNetwork(failToast = "「$srcName」拉取失败，继续使用原有频道列表")
            }
        }
    }

    /** 该频道是不是当前正在播放的那个（分组+名称都一致） */
    private fun isPlayingChannel(ch: Channel): Boolean =
        ch.name == memGet("last_name") && ch.group == memGet("last_group")

    private fun startIndexOf(ch: Channel): Int {
        val mem = SourceMemory.get(this, ch.group, ch.name) ?: 0
        val n = ch.urls.size
        return if (n <= 0) 0 else mem.coerceIn(0, n - 1)
    }

    private fun playChannel(pos: Int, probe: Boolean = true) {
        if (groups.isEmpty()) return
        if (channelsOfGroup(groupIndex).isEmpty()) {
            val gi = groups.indexOfFirst { g -> channelsOfGroup(groups.indexOf(g)).isNotEmpty() }
            if (gi >= 0) { groupIndex = gi; groupPos = gi; channelIndex = 0 }
        }
        val list = channelsOfGroup(groupIndex)
        if (pos < 0 || pos >= list.size) return
        channelIndex = pos
        val ch = list[pos]
        val start = startIndexOf(ch)
        sourceIndex = start
        showOsd(ch, pos)
        playWithProbe(ch, start, probe)
    }

    /** 先挑一个能连上的源再播：记忆源优先，同时并发试探其它几条，谁先响应用谁 */
    private fun playWithProbe(ch: Channel, start: Int, probe: Boolean) {
        if (!probe || ch.urls.size <= 1) { playSource(ch, start); return }
        probeFastest(ch, start) { idx -> playSource(ch, idx) }
    }

    /**
     * 并发试探：默认源 + 最多 3 条候选同时连，先连上的立即播；
     * 500ms 内都连不上就按默认源播（交给原有的失败自动换源逻辑）。
     */
    private fun probeFastest(ch: Channel, start: Int, onPick: (Int) -> Unit) {
        val n = ch.urls.size
        if (n <= 1) { onPick(start); return }
        val cands = ArrayList<Int>(4)
        cands.add(start)
        var i = 0
        while (i < n && cands.size < 4) { if (i != start) cands.add(i); i++ }
        val lock = Any()
        var decided = false
        val pick = { idx: Int ->
            synchronized(lock) {
                if (!decided) { decided = true; runOnUiThread { onPick(idx) } }
            }
        }
        for (idx in cands) {
            Thread {
                if (quickConnect(ch.urls[idx])) pick(idx)
            }.start()
        }
        Thread {
            try { Thread.sleep(500) } catch (e: Throwable) { }
            pick(start)
        }.start()
    }

    /** 轻量连通性探测：只连不发数据，200~399 视为可用 */
    private fun quickConnect(url: String): Boolean = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 450
        c.readTimeout = 450
        c.requestMethod = "GET"
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        val code = c.responseCode
        try { if (code in 200..399) c.inputStream?.read() } catch (e: Throwable) { }
        c.disconnect()
        code in 200..399
    } catch (e: Throwable) { false }

    private fun playSource(ch: Channel, idx: Int) {
        if (idx >= ch.urls.size) {
            loading.visibility = View.GONE
            return
        }
        sourceIndex = idx
        val url = ch.urls[idx]
        // 记录上次播放及所属源，切源时各源各自保留，切回来仍能秒开
        memPut("last_url", url)
        memPut("last_name", ch.name)
        memPut("last_group", ch.group)
        startVideo(url, true)
    }

    /** 首次启动直接用上次地址播放，不等列表解析 */
    private fun playUrlDirect(url: String) {
        startVideo(url, false)
    }

    private fun startVideo(url: String, useSoftLineFallback: Boolean) {
        if (exiting) return
        playCallbackFired = false
        loading.visibility = View.GONE
        // 1.2 秒还没画面才显示加载圈：快源看不到圈，慢源有反馈
        loadingHandler.removeCallbacks(loadingRunnable)
        loadingHandler.postDelayed(loadingRunnable, 1200)
        video.setVideoURI(Uri.parse(url))
        video.setOnPreparedListener { mp ->
            applyFillScreen(mp)
            try {
                // 一次性回调，不轮询，对流畅度无影响
                mp.setOnVideoSizeChangedListener { _, w, h ->
                    onVideoSize(w, h)
                    runOnUiThread { updateBottomBarOnResChanged() }
                }
                onVideoSize(mp.videoWidth, mp.videoHeight)
            } catch (e: Throwable) {
            }
        }
        video.setOnInfoListener { _, what, _ ->
            if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END ||
                what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
            ) {
                onPlaySuccess(); true
            } else false
        }
        video.setOnErrorListener { _, _, _ ->
            when {
                useSoftLineFallback -> tryNextSource()
                allChannels.isEmpty() -> refreshFromNetwork()
                else -> {
                    // 上次地址失效：用缓存列表里的频道兜底
                    loading.visibility = View.GONE
                    playChannel(0, probe = false)
                }
            }
            true
        }
        video.start()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    /** 按屏幕比例铺满，消除黑边 */
    private fun applyFillScreen(mp: android.media.MediaPlayer) {
        val vw = mp.videoWidth
        val vh = mp.videoHeight
        if (vw <= 0 || vh <= 0) return
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val vr = vw.toFloat() / vh.toFloat()
        val sr = sw.toFloat() / sh.toFloat()
        val params = video.layoutParams
        if (vr < sr) {
            params.width = sw
            params.height = (sw / vr).toInt()
        } else {
            params.width = (sh * vr).toInt()
            params.height = sh
        }
        video.layoutParams = params
    }

    private fun onPlaySuccess() {
        if (playCallbackFired) return
        playCallbackFired = true
        // 播放成功：重置失败计数，并解除静默续播状态
        failoverCount = 0
        deadChannelCount = 0
        quietResume = false
        timeoutHandler.removeCallbacks(timeoutRunnable)
        loadingHandler.removeCallbacks(loadingRunnable)
        loading.visibility = View.GONE
        // 记住：这个频道用第几条源能播，下次进来直接从这条开始
        channelsOfGroup(groupIndex).getOrNull(channelIndex)?.let { ch ->
            SourceMemory.put(this, ch.group, ch.name, sourceIndex)
        }
        // 首帧出来了，等画面稳定 2 秒再解析列表，避免和首帧解码抢 CPU
        requestListParse(LIST_PARSE_AFTER_FRAME_MS)
        // 节目单再往后放，避免启动期多个网络任务挤在一起
        startEpgDelayed()
    }

    /** 单频道最多连续试几条线路，超过就换频道，避免一直转圈 */
    private var failoverCount = 0
    /** 连续多少个频道都连不上就停下提示 */
    private var deadChannelCount = 0
    private val MAX_FAILOVER_PER_CHANNEL = 3
    private val MAX_DEAD_CHANNELS = 5

    private fun tryNextSource() {
        val list = channelsOfGroup(groupIndex)
        if (list.isEmpty() || channelIndex >= list.size) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            if (allChannels.isEmpty()) {
                refreshFromNetwork()
            } else {
                loading.visibility = View.GONE
                playChannel(0, probe = false)
            }
            return
        }
        val ch = list[channelIndex]
        // 记忆源可能在中间：试到末尾后回到第一条继续试
        val next = if (sourceIndex + 1 < ch.urls.size) sourceIndex + 1 else 0
        // 线路试完，或同一频道试了太多条仍失败：换下一个频道，别在这一条上死循环
        if (next >= ch.urls.size || failoverCount >= MAX_FAILOVER_PER_CHANNEL) {
            failoverCount = 0
            deadChannelCount++
            timeoutHandler.removeCallbacks(timeoutRunnable)
            if (deadChannelCount >= MAX_DEAD_CHANNELS) {
                deadChannelCount = 0
                loading.visibility = View.GONE
                Toast.makeText(this, "这些频道暂时都连不上，可在设置里刷新源后再试", Toast.LENGTH_LONG).show()
                return
            }
            channelIndex = (channelIndex + 1) % list.size
            playChannel(channelIndex, probe = false)
            return
        }
        failoverCount++
        playSource(ch, next)
    }

    private fun switchSource(delta: Int) {
        val list = channelsOfGroup(groupIndex)
        if (channelIndex >= list.size) return
        val ch = list[channelIndex]
        if (ch.urls.size <= 1) {
            Toast.makeText(this, "当前频道只有 1 条线路", Toast.LENGTH_SHORT).show()
            return
        }
        val target = sourceIndex + delta
        if (target < 0 || target >= ch.urls.size) {
            Toast.makeText(this, if (delta > 0) "已是最后一条线路" else "已是第一条线路", Toast.LENGTH_SHORT).show()
            return
        }
        playSource(ch, target)
        showOsd(ch, channelIndex)
    }

    private fun showOsd(ch: Channel, pos: Int) {
        updateBottomBar(ch)
        bottomBar.visibility = View.VISIBLE
        bottomBarHandler.removeCallbacks(bottomBarHide)
        bottomBarHandler.postDelayed(bottomBarHide, PANEL_AUTO_HIDE_MS)
    }

    /** 取某频道当前正在播的节目名（没有就返回 null，二级行第二行留空） */
    private fun currentProgramOf(channelName: String): String? {
        val (now, _) = EpgRepository.nowNext(channelName)
        return now?.title
    }

    /** 刷新底部信息条：编号+名称 | 正在播 / 下一个；节目单没有就留空 */
    private fun updateBottomBar(ch: Channel) {
        bottomNum.text = if (ch.number > 0) ch.number.toString() else ""
        bottomName.text = ch.name
        val (now, next) = EpgRepository.nowNext(ch.name)
        bottomNowProgram.text = now?.title ?: ""
        if (next != null) {
            bottomNextProgram.text = EpgRepository.timeText(next.startMillis) + "  " + next.title
        } else {
            bottomNextProgram.text = ""
        }
        bottomNowRes.text = currentRes
        updateSourceInfo(ch)
        // 节目单可能还没拉到：拉完再刷新一次
        if (now == null && !EpgRepository.isLoaded()) loadEpgOnDemand()
    }

    /** 右下角：只显示当前频道的真实线路「源 x/y」，不再叠加订阅源序号 */
    private fun updateSourceInfo(ch: Channel? = null) {
        bottomNextRes.text = ch?.let { sourceInfoOf(it) } ?: ""
    }

    private fun updateBottomBarOnResChanged() {
        bottomNowRes.text = currentRes
        updateSourceInfo(channelsOfGroup(groupIndex).getOrNull(channelIndex))
    }

    /** 写入解码器回报的真实分辨率，例如 1920x1080 -> 1080P */
    private fun onVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val short = minOf(w, h)
        currentRes = when {
            short >= 2000 -> "4K"
            short >= 1000 -> "1080P"
            short >= 700 -> "720P"
            short >= 500 -> "576P"
            else -> short.toString() + "P"
        }
    }

    // ================= 数字键频道号直达 =================
    private fun handleNumberKey(keyCode: Int): Boolean {
        val digit = when {
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> return false
        }
        if (numBuffer.length >= 4) numBuffer = ""
        numBuffer += digit.toString()
        updateNumberInputText()
        numberInput.visibility = View.VISIBLE
        numHandler.removeCallbacks(numCommitRunnable)
        numHandler.postDelayed(numCommitRunnable, NUMBER_COMMIT_MS)
        return true
    }

    private fun updateNumberInputText() {
        val n = numBuffer.toIntOrNull()
        val target = if (n != null) allChannels.firstOrNull { it.number == n } else null
        numberInput.text = if (target != null) "$numBuffer  ${target.name}" else numBuffer
    }

    private fun commitNumberInput() {
        numHandler.removeCallbacks(numCommitRunnable)
        if (numBuffer.isEmpty()) return
        val n = numBuffer.toIntOrNull()
        numBuffer = ""
        numberInput.visibility = View.GONE
        if (n == null) return
        val target = allChannels.firstOrNull { it.number == n }
        if (target == null) {
            Toast.makeText(this, "频道 $n 不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val gi = groups.indexOf(target.group)
        if (gi < 0) return
        val list = channelsOfGroup(gi)
        val ci = list.indexOf(target)
        if (ci < 0) return
        groupIndex = gi
        channelIndex = ci
        sourceIndex = 0
        playSource(target, 0)
        showOsd(target, ci)
        if (panelState != 0) closePanel()
        groupPos = gi
        channelPos = ci
    }

    private fun clearNumberInput() {
        numHandler.removeCallbacks(numCommitRunnable)
        numBuffer = ""
        numberInput.visibility = View.GONE
    }

    // ================= 右侧设置面板 =================
    private fun showSettings() {
        panelContainer.visibility = View.GONE
        configOverlay.visibility = View.GONE
        panelState = 3
        settingsLevel = 0
        settingsPos = 0
        settingsPanel.visibility = View.VISIBLE
        buildSettingsItems()
        armSettingsAutoHide()
    }

    private fun closeSettings() {
        settingsHideHandler.removeCallbacks(settingsHideRunnable)
        settingsPanel.visibility = View.GONE
        if (panelState == 3) panelState = 0
    }

    private fun currentSourceItem(): SourceItem? {
        val sources = SourceStore.load(this)
        return sources.firstOrNull { it.id == SourceStore.currentId(this) } ?: sources.firstOrNull()
    }

    private fun buildSettingsItems() {
        settingsItems.clear()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val hidden = GroupStore.loadHidden(this)
        when (settingsLevel) {
            0 -> {
                // 一行一个功能，只显示名字（不写副标题，字号放大也不会被截断）
                settingsItems.add(ManageItem("分组管理", "", ManageType.NAV, payload = "2"))
                settingsItems.add(ManageItem("订阅源管理", "", ManageType.NAV, payload = "1"))
                val clockOn = sp.getBoolean("show_clock", true)
                settingsItems.add(
                    ManageItem(if (clockOn) "时间显示（开）" else "时间显示（关）", "", ManageType.NAV, payload = "6")
                )
                settingsItems.add(ManageItem("检查更新", "", ManageType.NAV, payload = "7"))
                settingsItems.add(ManageItem("关于", "", ManageType.NAV, payload = "4"))
                settingsTitle.text = "设置"
                settingsHint.text = "上下选择 · OK 进入 · 左键/返回退出"
            }
            3 -> {
                settingsItems.add(
                    ManageItem("TV 直播  v" + appVersion(), "打开即看 · 无需登录", ManageType.INFO)
                )
                settingsItems.add(
                    ManageItem(
                        "简介",
                        "一款给电视和机顶盒用的直播应用：装好就能看，内置一份电视直播源，" +
                            "也可以自己扫码添加订阅源；同一频道有多条线路时，没画面会自动换到能用的那条。",
                        ManageType.INFO
                    )
                )
                settingsItems.add(
                    ManageItem(
                        "功能",
                        "· 多线路静默换源：5 秒没画面自动切下一条线路\n" +
                            "· 扫码添加订阅源：手机填名称和地址，立即生效\n" +
                            "· 分组管理：分组可显示/隐藏、可调整顺序\n" +
                            "· 节目单：显示当前频道正在播什么\n" +
                            "· 记忆播放：开机自启，回到上次看的频道\n" +
                            "· 数字键直达频道号，左右键手动切线路",
                        ManageType.INFO
                    )
                )
                settingsItems.add(
                    ManageItem(
                        "快捷键",
                        "OK：呼出频道面板　菜单：设置\n上下：切频道　左右：切线路或逐级返回\n数字键：直接输入频道号（1.5 秒自动确认）",
                        ManageType.INFO
                    )
                )
                settingsItems.add(
                    ManageItem(
                        "数据来源",
                        "直播源：ipiptv/iptv-sources（bbxx365_hotel）\n节目单：iptv-4us 51zmt 节目单",
                        ManageType.INFO
                    )
                )
                settingsItems.add(
                    ManageItem(
                        "版本信息",
                        "版本号 v" + appVersion() + "\n包名 tv.live\n支持 Android 5.0 及以上（armeabi-v7a / arm64-v8a）",
                        ManageType.INFO
                    )
                )
                settingsTitle.text = "关于"
                settingsHint.text = "上下滚动查看 · OK 检查更新 · 左键/返回退出"
            }
            1 -> {
                // 顺序：扫码添加 → 默认源 → 分组管理 → 其他源
                settingsItems.add(ManageItem("＋ 扫码添加新源", "", ManageType.ADD))
                val sources = SourceStore.load(this).sortedBy {
                    if (it.id == SourceStore.DEFAULT_SOURCE_ID) 0 else 1
                }
                val curId = SourceStore.currentId(this)
                sources.forEach { src ->
                    settingsItems.add(
                        ManageItem(
                            title = src.name,
                            subtitle = "",
                            type = ManageType.SOURCE,
                            checked = src.id == curId,
                            payload = src.id
                        )
                    )
                }
                settingsTitle.text = "订阅源管理"
                settingsHint.text = "OK 切换源 · 右键刷新当前源（其他源右键删除）\n左键/返回回到设置"
            }
            2 -> {
                val all = groupsInSavedOrder()
                    all.forEach { g ->
                    settingsItems.add(
                        ManageItem(
                            title = g,
                            subtitle = if (hidden.contains(g)) "已隐藏" else "显示中",
                            type = ManageType.GROUP,
                            checked = !hidden.contains(g),
                            payload = g
                        )
                    )
                }
                if (settingsItems.isEmpty()) {
                    settingsItems.add(ManageItem("暂无分组", "请先加载频道列表", ManageType.GROUP))
                }
                settingsTitle.text = "分组管理"
                settingsHint.text = "OK 显示/隐藏 · 左右键调整顺序\n返回键回到设置"
            }
        }
        settingsPos = settingsPos.coerceIn(0, (settingsItems.size - 1).coerceAtLeast(0))
        // 设置条目固定行高，与左侧一级分组一致：有几项占几行，其余留空
        if (panelAvailPx > 0) {
            settingsAdapter.rowHeightPx = (panelAvailPx / 10).coerceAtLeast(dp(40))
            settingsAdapter.notifyDataSetChanged()
        }
        settingsAdapter.selected = settingsPos
        settingsAdapter.notifyDataSetChanged()
        if (settingsItems.isNotEmpty()) settingsList.scrollToPosition(settingsPos)
    }

    private fun activateSettingsItem() {
        if (settingsPos < 0 || settingsPos >= settingsItems.size) return
        val item = settingsItems[settingsPos]
        val hidden = GroupStore.loadHidden(this)
        when (settingsLevel) {
            0 -> {
                when (item.payload) {
                    "1" -> { settingsLevel = 1; settingsPos = 0; buildSettingsItems() }
                    "2" -> { settingsLevel = 2; settingsPos = 0; buildSettingsItems() }
                    "3" -> showConfig()
                    "5" -> refreshCurrentSource()
                    "7" -> startUpdateCheck()
                    "6" -> {
                        val nowOn = !sp.getBoolean("show_clock", true)
                        sp.edit().putBoolean("show_clock", nowOn).apply()
                        applyClockVisibility()
                        buildSettingsItems()
                        Toast.makeText(this, if (nowOn) "已开启时间显示" else "已关闭时间显示", Toast.LENGTH_SHORT).show()
                    }
                    "4" -> {
                        settingsLevel = 3
                        settingsPos = 0
                        buildSettingsItems()
                    }
                }
                return
            }
            1 -> {
                when (item.type) {
                    ManageType.NAV -> {
                        when (item.payload) {
                            "5" -> refreshCurrentSource()
                            "2" -> { settingsLevel = 2; settingsPos = 0; buildSettingsItems() }
                        }
                    }
                    ManageType.SOURCE -> {
                        if (SourceStore.currentId(this) == item.payload) {
                            Toast.makeText(this, "这个源正在使用中", Toast.LENGTH_SHORT).show()
                            return
                        }
                        switchToSource(item.payload, item.title)
                    }
                    ManageType.ADD -> showConfig()
                }
            }
            3 -> {
                // 检查更新：直接联网查仓库里的版本清单，有新版就提示下载安装
                if (item.payload == "7") {
                    closeSettings()
                    startUpdateCheck()
                }
                return
            }
            2 -> {
                if (item.payload.isEmpty()) return
                    if (hidden.contains(item.payload)) hidden.remove(item.payload) else hidden.add(item.payload)
                GroupStore.saveHidden(this, hidden)
                applyGroupSettings()
            }
        }
    }

    /** 右键：源列表删除当前源；分组列表上移一位 */
    private fun rightActionInSettings() {
        if (settingsPos < 0 || settingsPos >= settingsItems.size) return
        val item = settingsItems[settingsPos]
        if (settingsLevel == 1 && item.type == ManageType.SOURCE) {
            // 当前正在用的源：右键＝强制重新拉取（缓存的旧列表先留着，成功才替换）
            if (item.payload == SourceStore.currentId(this)) {
                refreshCurrentSource()
                Toast.makeText(this, "正在重新拉取「${item.title}」", Toast.LENGTH_SHORT).show()
                return
            }
            if (item.payload == SourceStore.DEFAULT_SOURCE_ID) {
                Toast.makeText(this, "默认源不能删除", Toast.LENGTH_SHORT).show()
                return
            }
            val sources = SourceStore.load(this)
            sources.removeAll { it.id == item.payload }
            SourceStore.save(this, sources)
            if (SourceStore.currentId(this) == item.payload) {
                switchToSource(SourceStore.DEFAULT_SOURCE_ID, "默认源")
            }
            // 只清理被删除这个源自己的缓存，其他源的缓存一律不动
            try {
                SourceStore.cacheFileFor(this, item.payload).delete()
            } catch (e: Exception) {
            }
            Toast.makeText(this, "已删除「${item.title}」", Toast.LENGTH_SHORT).show()
            settingsPos = (settingsPos - 1).coerceAtLeast(0)
            buildSettingsItems()
        } else if (settingsLevel == 2 && item.type == ManageType.GROUP) {
            // 右键 = 下移一位
            moveGroupInSettings(1)
        }
    }

    /** 分组管理页里手动调整顺序：delta=-1 上移，+1 下移 */
    private fun moveGroupInSettings(delta: Int) {
        if (settingsPos < 0 || settingsPos >= settingsItems.size) return
        val item = settingsItems[settingsPos]
        if (item.type != ManageType.GROUP || item.payload.isEmpty()) return
        val display = groupsInSavedOrder().toMutableList()
        val idx = display.indexOf(item.payload)
        if (idx < 0) return
        val to = idx + delta
        if (to < 0 || to >= display.size) {
            Toast.makeText(this, if (delta < 0) "已经在最上面了" else "已经在最下面了", Toast.LENGTH_SHORT).show()
            return
        }
        val tmp = display[to]
        display[to] = display[idx]
        display[idx] = tmp
        val full = display.toMutableList()
        rawGroupNames().forEach { g -> if (!full.contains(g)) full.add(g) }
        GroupStore.saveOrder(this, full)
        settingsPos = to
        applyGroupSettings()
    }

    private fun applyGroupSettings() {
        groups.clear()
        groups.addAll(GroupStore.apply(this, rawGroupNames()))
        if (groups.isEmpty()) {
            groupIndex = 0
            channelIndex = 0
        } else {
            groupIndex = groupIndex.coerceIn(0, groups.size - 1)
            val size = channelsOfGroup(groupIndex).size
            channelIndex = if (size == 0) 0 else channelIndex.coerceIn(0, size - 1)
        }
        groupPos = groupIndex
        channelPos = channelIndex
        groupAdapter.notifyDataSetChanged()
        buildSettingsItems()
    }

    private fun handleSettingsKey(keyCode: Int): Boolean {
        // 设置面板 5 秒无操作自动收回
        armSettingsAutoHide()
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (settingsLevel > 0) {
                    settingsLevel = 0
                    settingsPos = 0
                    buildSettingsItems()
                } else {
                    closeSettings()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { closeSettings(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                settingsPos = (settingsPos - 1).coerceAtLeast(0)
                settingsAdapter.selected = settingsPos
                settingsAdapter.notifyDataSetChanged()
                settingsList.scrollToPosition(settingsPos)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                settingsPos = (settingsPos + 1).coerceAtMost((settingsItems.size - 1).coerceAtLeast(0))
                settingsAdapter.selected = settingsPos
                settingsAdapter.notifyDataSetChanged()
                settingsList.scrollToPosition(settingsPos)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (settingsLevel == 2) { moveGroupInSettings(-1); return true }
                if (settingsLevel > 0) {
                    settingsLevel = 0
                    settingsPos = 0
                    buildSettingsItems()
                } else {
                    closeSettings()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (settingsLevel == 2) { moveGroupInSettings(1); return true }
                rightActionInSettings(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                activateSettingsItem(); return true
            }
        }
        return false
    }

    // ================= 扫码换源 =================
    private fun showConfig() {
        panelContainer.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        panelState = 0
        hideHandler.removeCallbacks(hideRunnable)
        configOverlay.visibility = View.VISIBLE

        val ip = localIpAddress()
        val port = 8888
        val address = "http://$ip:$port"
        configUrlText.text = address
        qrImage.setImageBitmap(QrGenerator.generate(address, dp(220)))
        configCurrentText.text = "当前订阅源：" + (sp.getString("source_url", DEFAULT_URL) ?: DEFAULT_URL)

        configServer?.stop()
        configServer = ConfigServer(this, port, onSourceUpdated = { name, url, setCurrent ->
            runOnUiThread {
                val sources = SourceStore.load(this)
                val exist = sources.firstOrNull { it.url == url }
                if (exist == null) {
                    sources.add(SourceItem(SourceStore.newId(), name, url))
                } else {
                    exist.name = name
                }
                SourceStore.save(this, sources)
                if (setCurrent) {
                    val item = SourceStore.load(this).firstOrNull { it.url == url }
                    if (item != null) {
                        configOverlay.visibility = View.GONE
                        stopConfigServer()
                        // 不先清空旧列表：旧频道继续显示，新源拉到内容后才替换
                        switchToSource(item.id, name)
                        return@runOnUiThread
                    }
                }
                configCurrentText.text = "已添加：$name"
                Toast.makeText(this, "已添加「$name」，可在管理页切换", Toast.LENGTH_SHORT).show()
            }
        }, onUpdateNow = {
            runOnUiThread { startUpdateCheck() }
        }, onApkUploaded = { apkFile ->
            runOnUiThread {
                try {
                    UpdateChecker.installLocalFile(this, apkFile) { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Throwable) {
                    Toast.makeText(this, "安装失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                }
            }
        }).also { it.start() }
    }

    private fun hideConfig() {
        configOverlay.visibility = View.GONE
        stopConfigServer()
    }

    private fun stopConfigServer() {
        configServer?.stop()
        configServer = null
    }

    /** 取本机局域网 IPv4 地址 */
    private fun localIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
            "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    // ================= 菜单 =================
    private fun openPanel() {
        panelState = 1
        focusIndex = 1
        panelContainer.visibility = View.VISIBLE

        // 用户主动要看列表了：还没解析就立刻解析，不等后台延迟
        if (allChannels.isEmpty() && !listParsed) requestListParse(0L)

        epgOpen = false
        epgContainer.visibility = View.GONE
        hintStrip.visibility = View.VISIBLE

        bottomBarHandler.removeCallbacks(bottomBarHide)
        bottomBar.visibility = View.GONE

        groupPos = groupIndex
        channelPos = channelIndex
        groupAdapter.selected = groupPos
        reloadChannelList(groupPos, channelIndex)
        channelsOfGroup(groupPos).getOrNull(channelIndex)?.let { updateBottomBar(it) }
        groupListRv.scrollToPosition(groupPos)
        applyFocusStyle()
        refreshEpgPreview()
        loadEpgOnDemand()
        resetHideTimer()
    }

    private fun reloadChannelList(g: Int, keepPos: Int) {
        val src = channelsOfGroup(g)
        channelListData.clear()
        channelListData.addAll(src)
        channelPos = if (src.isEmpty()) 0 else keepPos.coerceIn(0, src.size - 1)
        channelAdapter.selected = channelPos
        channelAdapter.notifyDataSetChanged()
        if (src.isNotEmpty()) channelListRv.scrollToPosition(channelPos)
        // 换了分组就按新分组重新量二级栏宽，二三级之间不留空档
        computeRowMetrics(g)
    }

    /** 三栏焦点样式：焦点栏亮蓝，其余暗蓝 */
    private fun applyFocusStyle() {
        groupAdapter.focused = focusIndex == 0
        channelAdapter.focused = focusIndex == 1
        groupAdapter.notifyDataSetChanged()
        channelAdapter.notifyDataSetChanged()
        epgAdapter.focused = focusIndex == 2
        epgAdapter.notifyDataSetChanged()
    }

    private fun playSelectedChannel() {
        val list = channelsOfGroup(groupPos)
        if (channelPos < 0 || channelPos >= list.size) return
        val ch = list[channelPos]
        // 点的是当前正在播的频道：只收面板，不重新播放、不转圈
        if (isPlayingChannel(ch)) { closePanel(); return }
        groupIndex = groupPos
        channelIndex = channelPos
        val start = startIndexOf(ch)
        sourceIndex = start
        showOsd(ch, channelPos)
        playWithProbe(ch, start, true)
    }

    /** 右键弹出三级节目单：焦点进入节目单，当前节目居中 */
    private fun showEpgPanel() {
        epgOpen = true
        epgContainer.visibility = View.VISIBLE
        hintStrip.visibility = View.GONE
        focusIndex = 2
        refreshEpgPreview()
        applyFocusStyle()
        loadEpgOnDemand()
    }

    /** 左键收回三级节目单，焦点回二级频道 */
    private fun hideEpgPanel() {
        epgOpen = false
        epgContainer.visibility = View.GONE
        hintStrip.visibility = View.VISIBLE
        focusIndex = 1
        applyFocusStyle()
    }

    /** 把某一条节目滚到列表垂直中间 */
    private fun centerEpgOn(index: Int) {
        epgListRv.post {
            val lm = epgListRv.layoutManager as? LinearLayoutManager ?: return@post
            val h = epgListRv.height
            val rowH = if (::epgAdapter.isInitialized) epgAdapter.rowHeightPx else rowHeightPx
            val offset = ((h - rowH) / 2).coerceAtLeast(0)
            lm.scrollToPositionWithOffset(index, offset)
        }
    }

    private fun closePanel() {
        panelState = 0
        epgOpen = false
        epgContainer.visibility = View.GONE
        hintStrip.visibility = View.VISIBLE
        panelContainer.visibility = View.GONE
        bottomBarHandler.removeCallbacks(bottomBarHide)
        bottomBar.visibility = View.GONE
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        if (panelState == 1) hideHandler.postDelayed(hideRunnable, PANEL_AUTO_HIDE_MS)
    }

    // ================= 三级：节目单（随频道高亮实时联动） =================
    private fun refreshEpgPreview() {
        if (channelListData.isEmpty()) {
            epgData.clear()
            epgAdapter.notifyDataSetChanged()
            epgHint.visibility = View.VISIBLE
            epgHint.text = "未选择频道"
            return
        }
        val ch = channelListData[channelPos]
        val programs = EpgRepository.programsFor(ch.name)
        epgData.clear()
        epgData.addAll(programs)

        if (programs.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val idx = programs.indexOfFirst { now >= it.startMillis && now < it.endMillis }
            epgPos = if (idx >= 0) idx else 0
        } else {
            epgPos = 0
        }
        epgAdapter.selected = epgPos
        epgAdapter.notifyDataSetChanged()
        if (programs.isNotEmpty()) centerEpgOn(epgPos)

        if (programs.isEmpty()) {
            epgHint.visibility = View.VISIBLE
            epgHint.text = if (!EpgRepository.isLoaded()) "节目单加载中…" else "暂无节目信息"
        } else {
            epgHint.visibility = View.GONE
        }
    }

    /** 首帧成功后延迟 60 秒再拉节目单，和列表更新任务错开 */
    /** 节目单到了之后统一刷新界面：二级副标题、底部条、三级列表 */
    private fun refreshProgramUi() {
        if (::channelAdapter.isInitialized) channelAdapter.notifyDataSetChanged()
        val list = channelsOfGroup(groupIndex)
        if (channelIndex in list.indices) updateBottomBar(list[channelIndex])
        if (panelState == 1) refreshEpgPreview()
    }

    private fun startEpgDelayed() {
        if (epgStarted) return
        epgStarted = true
        timeHandler.postDelayed({
            // 期间如果用户打开面板已经按需拉过了，就不再重复请求
            if (!EpgRepository.isLoaded() && !epgRequested) {
                epgRequested = true
                EpgRepository.load(this) { refreshProgramUi() }
            }
        }, EPG_DELAY_MS)
    }

    /** 打开面板时按需加载节目单：还没加载就立即拉，不必等延迟策略 */
    private fun loadEpgOnDemand() {
        if (EpgRepository.isLoaded() || epgRequested) return
        epgRequested = true
        EpgRepository.load(this) { refreshProgramUi() }
    }

    // ================= 按键 =================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 长按产生的重复按下事件一律吞掉：确定键长按不做任何动作，避免重复换台/重进频道
        if (event != null && event.repeatCount > 0) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return true
            }
        }
        // 长按 OK / 菜单键可呼出扫码换源，先开启长按追踪
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_MENU
        ) {
            event?.startTracking()
        }
        if (numBuffer.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { clearNumberInput(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    commitNumberInput(); return true
                }
            }
        }
        if (handleNumberKey(keyCode)) return true

        // 右侧设置面板打开时
        if (settingsPanel.visibility == View.VISIBLE) {
            return handleSettingsKey(keyCode) || true
        }

        // 扫码配置页打开时：只处理返回
        if (configOverlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideConfig()
                return true
            }
            return true
        }

        if (panelState == 1) {
            resetHideTimer()
            return handlePanelKey(keyCode) || true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                showSettings(); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // 单击＝开面板，长按＝看底部信息条：先等 350ms 再判定
                centerDownPending = true
                centerLongPressFired = false
                centerHandler.removeCallbacks(centerLongPressRunnable)
                centerHandler.postDelayed(centerLongPressRunnable, CENTER_LONG_PRESS_MS)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (channelIndex > 0) playChannel(channelIndex - 1, probe = false)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (channelIndex < channelsOfGroup(groupIndex).size - 1) playChannel(channelIndex + 1, probe = false)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { switchSource(-1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { switchSource(1); true }
            KeyEvent.KEYCODE_BACK -> { handleBackExit(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (handleBackOnUp(keyCode)) return true
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (centerDownPending) {
                centerDownPending = false
                centerHandler.removeCallbacks(centerLongPressRunnable)
                val fired = centerLongPressFired
                centerLongPressFired = false
                // 只是普通单击 → 打开面板；已经算作长按 → 不再开面板
                if (!fired) openPanel()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handlePanelKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                closePanel(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { moveInPanel(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveInPanel(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // 左键：节目单 → 收回三级回到频道 → 分组 → 关闭
                when (focusIndex) {
                    2 -> hideEpgPanel()
                    1 -> { focusIndex = 0; applyFocusStyle() }
                    else -> closePanel()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 右键：分组 → 进入频道 → 弹出三级节目单
                when (focusIndex) {
                    0 -> { focusIndex = 1; applyFocusStyle() }
                    1 -> showEpgPanel()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (focusIndex) {
                    0 -> {
                        // 分组栏确认：焦点进入频道栏
                        focusIndex = 1
                        applyFocusStyle()
                    }
                    else -> {
                        playSelectedChannel()
                        closePanel()
                    }
                }
                return true
            }
        }
        return false
    }

    private fun moveInPanel(delta: Int) {
        when (focusIndex) {
            0 -> {
                if (groups.isEmpty()) return
                // 到头循环：最后一组再按↓回第一组，第一组按↑跳最后一组
                groupPos = when {
                    delta > 0 -> (groupPos + 1) % groups.size
                    groupPos - 1 < 0 -> groups.size - 1
                    else -> groupPos - 1
                }
                groupAdapter.selected = groupPos
                groupAdapter.notifyDataSetChanged()
                groupListRv.scrollToPosition(groupPos)
                reloadChannelList(groupPos, 0)
                refreshEpgPreview()
            }
            1 -> {
                val size = channelListData.size
                if (size == 0) return
                channelPos = (channelPos + delta).coerceIn(0, size - 1)
                channelAdapter.selected = channelPos
                channelAdapter.notifyDataSetChanged()
                channelListRv.scrollToPosition(channelPos)
                refreshEpgPreview()
            }
            else -> {
                if (epgData.isEmpty()) return
                epgPos = (epgPos + delta).coerceIn(0, epgData.size - 1)
                epgAdapter.selected = epgPos
                epgAdapter.notifyDataSetChanged()
                epgListRv.scrollToPosition(epgPos)
            }
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { bgPool.shutdownNow() } catch (e: Throwable) { }
        timeoutHandler.removeCallbacksAndMessages(null)
        loadingHandler.removeCallbacksAndMessages(null)
        timeHandler.removeCallbacksAndMessages(null)
    }

    override fun onPause() {
        super.onPause()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeHandler.removeCallbacks(timeRunnable)
        hideHandler.removeCallbacks(hideRunnable)
        numHandler.removeCallbacks(numCommitRunnable)
        stopConfigServer()
        // 回桌面不暂停播放：再切回来画面立刻就在，不用重新连流转圈
    }

    override fun onStop() {
        super.onStop()
        // 只有真正退出应用时才停播
        if (isFinishing) {
            try {
                if (video.isPlaying) video.pause()
            } catch (e: Throwable) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        timeHandler.post(timeRunnable)
        if (allChannels.isEmpty() || video.isPlaying) return
        // 从桌面切回来：缓冲还在就直接续播，秒回画面，不重新连接也不转圈
        val prepared = try { video.duration > 0 } catch (e: Exception) { false }
        if (prepared) {
            loading.visibility = View.GONE
            timeoutHandler.removeCallbacks(timeoutRunnable)
            playCallbackFired = true
            try { video.start() } catch (e: Throwable) { }
            // 续播没接上（连接已失效）时再静默重新起播
            timeoutHandler.postDelayed({
                if (!video.isPlaying) {
                    val list = channelsOfGroup(groupIndex)
                    if (channelIndex < list.size) {
                        quietResume = true
                        playChannel(channelIndex, probe = false)
                    }
                }
            }, 1500L)
        } else {
            // 缓冲被系统回收了才重新起播，静默进行，不弹转圈
            val list = channelsOfGroup(groupIndex)
            if (channelIndex < list.size) {
                quietResume = true
                playChannel(channelIndex, probe = false)
            }
        }
    }

    /** 退出中：所有延迟任务、失败续播都立刻失效，防止退出瞬间又被拉回来 */
    @Volatile private var exiting = false

    /** 两次返回确认：第一次只提示，2 秒内再按一次才真的退桌面 */
    private val BACK_CONFIRM_MS = 2000L
    private var backArmedAt = 0L
    private var lastBackKeyAt = 0L
    private val backHandler = Handler(Looper.getMainLooper())
    private val backResetRunnable = Runnable { backArmedAt = 0L }

    private fun handleBackExit() {
        val now = android.os.SystemClock.elapsedRealtime()
        // 同一个按键的按下/抬起只算一次
        if (now - lastBackKeyAt < 250L) return
        lastBackKeyAt = now
        backHandler.removeCallbacks(backResetRunnable)
        if (backArmedAt > 0L && now - backArmedAt <= BACK_CONFIRM_MS) {
            backArmedAt = 0L
            exitToHome()
            return
        }
        backArmedAt = now
        Toast.makeText(this, "再按一次返回桌面", Toast.LENGTH_SHORT).show()
        backHandler.postDelayed(backResetRunnable, BACK_CONFIRM_MS)
    }


    /** 面板没开时按返回：一次性退回电视桌面 */
    private fun exitToHome() {
        if (exiting) return
        exiting = true
        loadingHandler.removeCallbacksAndMessages(null)
        timeoutHandler.removeCallbacksAndMessages(null)
        centerHandler.removeCallbacksAndMessages(null)
        hideHandler.removeCallbacksAndMessages(null)
        settingsHideHandler.removeCallbacksAndMessages(null)
        bottomBarHandler.removeCallbacksAndMessages(null)
        timeHandler.removeCallbacksAndMessages(null)
        backHandler.removeCallbacksAndMessages(null)
        loading.visibility = View.GONE
        try { video.stopPlayback() } catch (e: Throwable) { }
        // 直接显式跳回电视桌面，再结束自己：不依赖系统的“退到上一个任务”行为
        try {
            val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            home.addCategory(android.content.Intent.CATEGORY_HOME)
            home.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(home)
        } catch (e: Throwable) { }
        try {
            finishAndRemoveTask()
        } catch (e: Throwable) {
            finish()
        }
    }

    /** 有些遥控器只在抬起时报返回：再兜一次，靠 exiting 标记防重复 */
    private fun handleBackOnUp(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return false
        if (settingsPanel.visibility == View.VISIBLE) {
            if (settingsLevel > 0) {
                settingsLevel = 0
                settingsPos = 0
                buildSettingsItems()
            } else closeSettings()
            return true
        }
        if (configOverlay.visibility == View.VISIBLE) { hideConfig(); return true }
        if (panelState == 1) { closePanel(); return true }
        handleBackExit()
        return true
    }

    override fun onBackPressed() {
        if (panelState != 0) closePanel() else handleBackExit()
    }
}
