package com.sxsy45.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream

/**
 * 论坛网页壳（尚香书院，Discuz! X3.5）。
 *
 * 冷启动直接打开配置的论坛首页（默认 https://sxsy45.com/index.php，可在设置里改）。
 * 用户在 WebView 内正常登录（Cookie 持久化），浏览帖子正文、点击附件下载。
 *
 * 下载能力（与站点版本无关，适配 Discuz 通用结构）：
 *  - DownloadListener 主通道：附件链接让 WebView 原生导航，服务器返回文件时自动接管下载；
 *  - onCreateWindow 弹窗接管：跳转页/新窗口(window.open/target=_blank) 的文件地址转发回主 WebView，
 *    文件响应触发 DownloadListener；
 *  - shouldInterceptRequest 拦截网：对附件/文件地址先自己探测一次，是文件直接保存（不依赖系统弹窗），
 *    是网页则交回 WebView 原生渲染（页面内 meta refresh / JS 跳转才能生效）；
 *  - 浏览器通道(DownloadBridge)：原生请求被返回网页拦截时，改用页面内 fetch()（网络栈与真实浏览器一致）
 *    取文件，分块经 JS 桥回传保存。
 *  - 文件名：去站点标记（如 [sxsy.org] 前缀），乱码修复，按魔数补扩展名，同名自动加序号。
 *
 * 版面列表「自动加载下一页」的返回还原：
 *  - 站点是把后续页用 JS 追加进当前 DOM，而 Android WebView 的 goBack 一定会重建文档
 *    （WebView 不支持 BFCache），追加内容必然丢失，只剩第一页；
 *  - 因此从「版面列表」点进**派生页面**时，一律改在新开的独立界面里打开
 *    （同一 Activity，带 EXTRA_THREAD_MODE），列表界面只被覆盖、不被销毁，
 *    返回时列表已追加的多页内容与滚动位置原封不动，且不引入任何还原脚本（不卡顿）。
 *  - 派生页面 = 帖子页 + 帖子名旁的分类 tag（filter=typeid）+ 标签页（mod=tag）等；
 *    版块自身翻页（&page=N）不算，仍在原界面内导航。
 *  - 堆叠有上限：版面列表 → 列表型派生页（标签/分类/淘帖）→ 帖子，共 3 层。
 *    从标签列表点进帖子时再开一层，是为了让返回时**标签列表**也不被销毁（否则一样回到第一页）；
 *    而帖子之间互跳、列表之间互跳仍在同界面内导航，不会无界堆叠。
 *
 * 保留通用辅助：浏览历史、内置 TXT 阅读器入口(在下载管理里打开)、设置(网址/下载目录/历史保留/清数据)、诊断日志。
 * 说明：本版为纯净基础版，不注入任何页面脚本（去广告/自动回复等均未内置）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_STORAGE = 100
        private const val REQ_SETUP = 101
        /** 从「历史记录」页 / 帖子独立界面打开指定网址时，Intent 携带的 URL 键 */
        const val EXTRA_OPEN_URL = "open_url"
        /**
         * 本实例是否为承载「派生页面」的独立界面（帖子 / 分类 tag 筛选 / 标签页）。
         * 从版面列表点进这些页面时新开一个界面承载，列表界面只被覆盖、不被销毁，
         * 返回时列表已自动加载的多页内容与滚动位置原样还在。
         */
        const val EXTRA_THREAD_MODE = "thread_mode"
    }

    /** 适配高刷新率屏幕：在同分辨率模式中选刷新率最高的（API 23+） */
    @SuppressLint("InlinedApi")
    private fun applyHighRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val display = windowManager.defaultDisplay
            val modes = display.supportedModes
            if (modes.isNullOrEmpty()) return
            val currentMode = display.mode
            val bestMode = modes
                .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (bestMode.refreshRate > currentMode.refreshRate) {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = bestMode.modeId
                }
            }
        } catch (e: Exception) { /* 忽略 */ }
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var toolbar: Toolbar
    private var lastLoadedUrl: String? = null
    private var lastDesktopMode: Boolean? = null
    // 从「历史记录」页打开链接后，本次 onResume 不得用配置的首页覆盖掉它
    private var openedFromHistory = false
    // 首次启动：尚未配置论坛网址，正等待从设置页返回（期间 onResume 不自动加载）
    private var awaitingSetup = false
    // 是否已弹过首页加载失败提示，避免多次打断
    private var homeErrorShown = false
    // 登录回跳死循环(ERR_TOO_MANY_REDIRECTS)的自动恢复标记：单次导航只自动恢复一次，避免自身再循环
    private var redirectLoopRetried = false

    // 版面列表「自动加载下一页」的返回还原（1.3.19 起改为结构化方案）：
    // 站点是把后续页用 JS 追加进当前 DOM，而 Android WebView 的 goBack 一定会重建文档
    // （WebView 不支持 BFCache），追加内容必然丢失，只剩第一页；
    // 之前试过「记录高度再滚回去触发站点自动加载」和「body 快照还原」，前者依赖站点机制、
    // 加载不全还卡顿，后者会让页面 JS（翻页/formhash/事件）全部失效。
    // 现在改为：从「版面列表」点进「派生页面」（帖子 / 分类 tag / 标签页）时，
    // 一律在新的独立界面里打开，列表界面只被覆盖、不被销毁 —— 返回时 DOM
    // （含已追加的多页）与滚动位置原封不动。（1.3.20 起覆盖到分类 tag 与标签页）
    /** 本实例是否为承载「派生页面」的独立界面（历史命名 threadMode） */
    private var threadMode = false
    /** 刚从这个界面点开帖子（新开了独立界面）：本次 onResume 不重载首页，保住版面列表 */
    private var returningFromThreadScreen = false

    // WebView 只能在主线程访问，而 shouldInterceptRequest 在后台线程运行：
    // 预先在主线程缓存 UA 与最近的内容页地址（作下载 Referer），供后台拦截线程读取
    @Volatile private var cachedUserAgent: String = ""
    @Volatile private var lastContentPageUrl: String? = null
    // 当前页面「书名」（CSS 提取 #thread_subject），供下载命名
    @Volatile private var cachedBookName: String? = null
    // 购买成功后待下载的附件 aid：刷新帖子页后据此找已生效的下载链接
    @Volatile private var pendingDownloadAid: String? = null
    private val popupWindows = mutableListOf<WebView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threadMode = intent?.getBooleanExtra(EXTRA_THREAD_MODE, false) ?: false
        applyHighRefreshRate()
        Prefs.setDesktopMode(this, true)
        Prefs.setAdBlock(this, true)
        DebugLog.setEnabled(Prefs.isDebugMenuVisible(this))
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        // 帖子独立界面：标题栏给一个返回箭头，点它等价于系统返回键（回到版面列表）
        if (threadMode) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setDisplayShowHomeEnabled(true)
        }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()
        // 帖子界面不预热：此时已经有一个 WebView 在跑，避免多创建一次内核
        if (!threadMode) warmUpWebView()

        // 从「历史记录」页带 URL 启动：直接打开该网址
        val openUrl = intent?.getStringExtra(EXTRA_OPEN_URL)
        if (!openUrl.isNullOrBlank()) {
            handleOpenUrl(openUrl)
        } else if (savedInstanceState == null) {
            if (Prefs.getUrl(this).isBlank()) {
                // 首次启动、尚未配置网址：先进入设置页填写，保存返回后加载
                awaitingSetup = true
                startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETUP)
            } else {
                loadHome()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val openUrl = intent?.getStringExtra(EXTRA_OPEN_URL)
        if (!openUrl.isNullOrBlank()) handleOpenUrl(openUrl)
    }

    /** 从历史记录打开指定网址：禁止 onResume 用首页覆盖 */
    private fun handleOpenUrl(url: String) {
        openedFromHistory = true
        loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        // 等待首次设置期间：不自动加载，等 onActivityResult 清除标记后由本方法加载
        if (awaitingSetup) return
        // 从设置页返回后：网址 / 电脑版开关可能已改变，按需重新加载
        val url = Prefs.getUrl(this)
        val desktop = Prefs.isDesktopMode(this)
        val uaChanged = lastDesktopMode != null && lastDesktopMode != desktop
        lastDesktopMode = desktop
        webView.settings.userAgentString = buildUserAgent()
        cachedUserAgent = webView.settings.userAgentString
        // 从历史记录打开、本实例是帖子独立界面、或刚点开帖子又返回本界面：
        // 都不要用「配置的首页」把当前页覆盖掉，否则版面列表会被清掉
        if (openedFromHistory || threadMode || returningFromThreadScreen) {
            openedFromHistory = false
            returningFromThreadScreen = false
            return
        }
        val target = url.ifBlank { Prefs.DEFAULT_FORUM_URL }
        if (uaChanged || target != lastLoadedUrl) {
            loadUrl(target)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SETUP) {
            if (Prefs.getUrl(this).isBlank()) {
                // 首次启动且用户未保存网址：强制再次进入设置页，直到填写/确认网址为止
                awaitingSetup = true
                startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETUP)
            } else {
                awaitingSetup = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        popupWindows.forEach { try { it.destroy() } catch (_: Exception) {} }
        popupWindows.clear()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        // 多窗口 + onCreateWindow 接管：跳转页下载链接常用 window.open / target=_blank，
        // 接管后转发回主 WebView 加载，服务器返回文件即触发 DownloadListener 自动下载
        settings.setSupportMultipleWindows(true)
        @Suppress("DEPRECATION")
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = buildUserAgent()
        cachedUserAgent = settings.userAgentString
        try {
            settings.setGeolocationEnabled(false)
        } catch (e: Exception) { /* 忽略 */ }
        // 安全加固：关闭本地文件访问，防止网页读取本地文件
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false

        // JS 桥接：浏览器通道下载（页面内 fetch）分块回传使用
        webView.addJavascriptInterface(DownloadBridge(), "DiscuzApp")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                if (url != null && !isDownloadCandidate(url)) lastContentPageUrl = url
                DebugLog.log("PAGE", "加载开始: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                redirectLoopRetried = false
                if (DebugLog.isEnabled()) injectClickLogger()
                injectAdBlock()
                injectAutoSign()
                injectAttachPayHook()
                tryAutoPay()
                updateTitle()
                recordHistory(view, url)
                extractBookName()
                // 购买成功后刷新了帖子页：自动找该 aid 已生效的下载链接并下载
                tryAutoDownloadAfterBuy()
                DebugLog.log("PAGE", "加载完成: $url | title=${view?.title}")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                DebugLog.log("NAV", url)
                // 版面列表 → 帖子/分类标签/标签页：新开独立界面承载，让列表留在后台（返回即原样恢复）
                if (openDerivedPageInNewScreen(url)) return true
                // 畸形登录 URL 自愈：拦下死循环地址，改载干净登录页
                selfHealLoginUrl(url)?.let { healed ->
                    webView.loadUrl(healed)
                    return true
                }
                if (interceptExternalNav(url)) return true
                // 付费附件：主动接管购买+下载（站点 showWindow 弹窗失效）
                if (isAttachPayUrl(url)) {
                    handleAttachPay(url)
                    return true
                }
                // 附件：每次点击直接接管下载，去掉“第一次放行、第二次才下载”
                if (isDirectAttachmentUrl(url)) {
                    startDirectAttachment(url)
                    return true
                }
                return handleUrl(url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null) {
                    DebugLog.log("NAV", url)
                    // 版面列表 → 帖子/分类标签/标签页：新开独立界面承载，让列表留在后台（返回即原样恢复）
                    if (openDerivedPageInNewScreen(url)) return true
                    selfHealLoginUrl(url)?.let { healed ->
                        webView.loadUrl(healed)
                        return true
                    }
                    if (interceptExternalNav(url)) return true
                    if (isAttachPayUrl(url)) {
                        handleAttachPay(url)
                        return true
                    }
                    if (isDirectAttachmentUrl(url)) {
                        startDirectAttachment(url)
                        return true
                    }
                }
                return url?.let { handleUrl(it) } ?: false
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
            ) {
                val req = request
                val code = error?.errorCode ?: -1
                val desc = error?.description?.toString() ?: ""
                DebugLog.log("ERROR", "$code $desc @ ${req?.url} main=${req?.isForMainFrame}")
                val errUrl = req?.url?.toString()

                // 登录回跳死循环(ERR_TOO_MANY_REDIRECTS，WebView 内部 302 环)：自动恢复一次，
                // 把畸形 index.php/ 前缀归正后用干净地址重新加载，打断死循环
                if (req != null && req.isForMainFrame &&
                    code == android.webkit.WebViewClient.ERROR_REDIRECT_LOOP &&
                    !redirectLoopRetried && errUrl != null
                ) {
                    redirectLoopRetried = true
                    val healed = selfHealLoginUrl(errUrl) ?: Prefs.getHomeUrl(this@MainActivity)
                    DebugLog.log("NAV", "重定向死循环自动恢复 -> $healed")
                    runOnUiThread { webView.loadUrl(healed) }
                    return
                }

                // 仅主 frame 的网络级失败才提示（子资源/图片失败不打扰）；重定向死循环恢复过一次仍失败也提示
                if (req != null && req.isForMainFrame && !homeErrorShown &&
                    (isEntryNetworkError(code) || code == android.webkit.WebViewClient.ERROR_REDIRECT_LOOP)
                ) {
                    homeErrorShown = true
                    runOnUiThread {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("暂时无法访问")
                            .setMessage("网站暂时打不开，请检查网络后点击「刷新」重试，或稍后再试。")
                            .setCancelable(false)
                            .setPositiveButton("知道了") { _, _ -> homeErrorShown = false }
                            .show()
                    }
                }
            }

            // Discuz 站点常有过期/不匹配证书，放行让其正常加载
            override fun onReceivedSslError(
                view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?
            ) {
                // 安全加固：论坛主站域名证书应有效，出现错误必是中间人攻击 → 拒绝；
                // 非主站域名（中转/资源等）证书过期则放行，保持正常功能
                val errUrl = error?.url ?: ""
                val host = try { Uri.parse(errUrl).host?.lowercase() } catch (e: Exception) { null }
                val isMainHost = host != null && trustedHosts().any { h ->
                    host == h || host.endsWith(".$h")
                }
                if (isMainHost) {
                    DebugLog.log("SSL", "拒绝主站证书错误: $errUrl")
                    handler?.cancel()
                } else {
                    DebugLog.log("SSL", "放行非主站证书错误: $errUrl")
                    handler?.proceed()
                }
            }

            /**
             * 拦截网：附件/文件地址的 GET 请求（含子框架/iframe/XHR）自己发一次请求：
             * - 服务器返回文件 → 直接保存，返回一个结果页给 WebView
             * - 服务器返回网页（跳转页/提示页）→ 原样交回 WebView 渲染，页面 JS 照常执行
             * 注意：本方法运行在后台线程，严禁访问 webView —— UA 与 Referer 用主线程缓存的字段
             */
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                val url = req.url?.toString() ?: return null
                if (req.method != "GET") return null
                if (!isDownloadCandidate(url)) return null
                // 主框架附件页必须完全交给 WebView 原生导航，真正返回文件时由 DownloadListener 接管
                if (req.isForMainFrame) {
                    DebugLog.log("INTERCEPT", "主框架附件放行原生导航: $url")
                    return null
                }
                if (allowNativeOnce(url)) {
                    DebugLog.log("INTERCEPT", "放行原生请求一次: $url")
                    return null
                }
                val ua = cachedUserAgent
                if (ua.isBlank()) return null
                val referer = lastContentPageUrl
                return try {
                    val p = DownloadHelper.probe(this@MainActivity, ua, url, referer)
                    if (!p.isFile) {
                        p.file.delete()
                        handledUrls.remove(url)
                        bypassProbeUrls[url] = System.currentTimeMillis()
                        DebugLog.log("INTERCEPT", "网页，放行 WebView 原生加载: $url")
                        null
                    } else if (!markHandled(url)) {
                        p.file.delete()
                        DebugLog.log("INTERCEPT", "重复请求，跳过: $url")
                        null
                    } else {
                        val bn = currentBookName()
                        val name = DownloadHelper.resolveWithBookName(p.finalUrl, p.disposition, bn)
                        val savedName = DownloadHelper.saveFromFile(this@MainActivity, p.file, name)
                        p.file.delete()
                        DebugLog.log("INTERCEPT", "已保存: $savedName (${p.size}B) | 书名=$bn")
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "下载完成：$savedName\n保存于 Download/${Prefs.getDownloadDir(this@MainActivity)}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        htmlResponse(downloadDonePage(savedName, p.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    }
                } catch (e: Exception) {
                    DebugLog.log("INTERCEPT", "失败: ${e.message}")
                    handledUrls.remove(url)
                    null
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }

            // 诊断：页面 JS 报错/警告也记入日志
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (!DebugLog.isEnabled()) return false
                consoleMessage?.let {
                    DebugLog.log("JS", "${it.messageLevel()}: ${it.message()} @${it.sourceId()}:${it.lineNumber()}")
                }
                return true
            }

            // 接管新窗口请求（window.open / target=_blank）：临时 WebView 捕获地址，转发回主 WebView
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                DebugLog.log("POPUP", "新窗口请求 (gesture=$isUserGesture)")
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.settings.databaseEnabled = true
                popup.settings.javaScriptCanOpenWindowsAutomatically = true
                popup.settings.setSupportMultipleWindows(true)
                popup.settings.userAgentString = cachedUserAgent
                popup.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        DebugLog.log("POPUP", "加载开始: $url")
                        if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                            runOnUiThread {
                                if (isDirectAttachmentUrl(url)) {
                                    v?.stopLoading()
                                    startDirectAttachment(url)
                                } else {
                                    forwardPopupUrl(url)
                                }
                            }
                        }
                    }
                    override fun onPageFinished(v: WebView?, url: String?) {
                        DebugLog.log("POPUP", "加载完成: $url")
                    }
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        val u = req?.url?.toString() ?: return false
                        if (u.startsWith("http://") || u.startsWith("https://")) {
                            runOnUiThread { forwardPopupUrl(u) }
                            return true
                        }
                        return true
                    }
                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                        val u = url ?: return false
                        if (u.startsWith("http://") || u.startsWith("https://")) {
                            runOnUiThread { forwardPopupUrl(u) }
                            return true
                        }
                        return true
                    }
                }
                popup.setDownloadListener { url, _, cd, mime, _ ->
                    DebugLog.log("DOWNLOAD", "Popup 下载监听: $url | mime=$mime | cd=$cd")
                    onDownloadStart(url, cd, mime)
                }
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        child: WebView?, dialog: Boolean, gesture: Boolean, msg: Message?
                    ): Boolean {
                        DebugLog.log("POPUP", "弹出页再次请求新窗口 (gesture=$gesture)")
                        val nested = WebView(this@MainActivity)
                        nested.settings.javaScriptEnabled = true
                        nested.settings.domStorageEnabled = true
                        nested.settings.javaScriptCanOpenWindowsAutomatically = true
                        nested.settings.userAgentString = cachedUserAgent
                        nested.webViewClient = object : WebViewClient() {}
                        nested.setDownloadListener { u, _, cd, mime, _ ->
                            DebugLog.log("DOWNLOAD", "嵌套弹出页下载监听: $u | mime=$mime | cd=$cd")
                            onDownloadStart(u, cd, mime)
                        }
                        popupWindows.add(nested)
                        val transport = msg?.obj as? WebView.WebViewTransport ?: return false
                        transport.webView = nested
                        msg.sendToTarget()
                        return true
                    }
                }
                popupWindows.add(popup)
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        swipeRefresh.setOnRefreshListener { webView.reload() }

        // 主下载通道：附件点击走浏览器导航，服务器返回文件时系统回调此处 → 自动下载
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            DebugLog.log("DOWNLOAD", "触发下载监听: $url | mime=$mimeType | cd=$contentDisposition")
            onDownloadStart(url, contentDisposition, mimeType)
        }
    }

    /** 可能是下载目标的地址：Discuz 附件端点，或常见的文件扩展名 */
    private fun isDownloadCandidate(url: String): Boolean {
        val l = url.lowercase()
        // 只认真正的附件下载端点。注意：`attachpay` 是「付费购买确认」浮层，不能拦截
        if (l.contains("attachpay")) return false
        if (l.contains("mod=attachment") || l.contains("attachment.php")) return true
        val path = l.substringBefore('?')
        return path.endsWith(".txt") || path.endsWith(".zip") || path.endsWith(".rar") ||
            path.endsWith(".7z") || path.endsWith(".pdf") || path.endsWith(".epub")
    }

    /** 同一地址 5 秒内只处理一次，避免拦截通道与 DownloadListener 重复下载 */
    private val handledUrls = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** HTML 探测后放行一次原生导航，防止 shouldInterceptRequest 与 WebView 互相循环 */
    private val bypassProbeUrls = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun markHandled(url: String): Boolean {
        val now = System.currentTimeMillis()
        val it = handledUrls.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > 5000) it.remove()
        }
        val prev = handledUrls[url]
        if (prev != null && now - prev < 5000) return false
        handledUrls[url] = now
        return true
    }

    private fun allowNativeOnce(url: String): Boolean {
        val now = System.currentTimeMillis()
        bypassProbeUrls.entries.removeIf { now - it.value > 10000 }
        return bypassProbeUrls.remove(url) != null
    }

    private fun htmlResponse(html: String): WebResourceResponse =
        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))

    /** 拦截下载成功后的结果页（代替系统下载弹窗） */
    private fun downloadDonePage(name: String, size: Int): String {
        val kb = maxOf(1, size / 1024)
        val safeName = android.text.TextUtils.htmlEncode(name)
        return "<html><head><meta name='viewport' content='width=device-width'>" +
            "<style>" +
            "html,body{background:#121826;color:#F4F7FB;margin:0}" +
            "body{font-family:sans-serif;padding:28px;line-height:1.8}" +
            ".card{max-width:680px;margin:8vh auto;padding:26px;border:1px solid #334155;" +
            "border-radius:16px;background:#1E293B;box-shadow:0 8px 30px #0008}" +
            ".ok{color:#86EFAC;font-size:14px;font-weight:bold}" +
            ".name{margin:14px 0;word-break:break-all;font-size:20px;color:#FFFFFF}" +
            ".meta{color:#CBD5E1;font-size:14px}" +
            ".tip{color:#94A3B8;font-size:13px;margin-top:20px}" +
            "</style></head><body><div class='card'>" +
            "<div class='ok'>下载完成</div>" +
            "<div class='name'>$safeName</div>" +
            "<div class='meta'>$kb KB</div>" +
            "<div class='tip'>可在右上角菜单「下载文件」中查看，返回上一页继续浏览。</div>" +
            "</div></body></html>"
    }

    /** 诊断用点击记录脚本（只读不改页面） */
    private fun injectClickLogger() {
        val js = """
(function(){
  if(window.__dzClickLog) return; window.__dzClickLog=1;
  document.addEventListener('click',function(e){
    try{
      var n=e.target, found=null, hops=0;
      while(n&&n.nodeType===1&&hops++<6){
        if(/^(A|BUTTON|INPUT)$/.test(n.tagName)||n.getAttribute('onclick')){ found=n; break; }
        n=n.parentNode;
      }
      if(!found) found=e.target;
      if(!found||!window.DiscuzApp) return;
      var oc=(found.getAttribute&&found.getAttribute('onclick')||'').slice(0,150);
      var info=found.tagName+' | href='+(found.getAttribute&&found.getAttribute('href')||'')+' | onclick='+oc+' | text='+(found.textContent||'').trim().slice(0,40);
      window.DiscuzApp.logClick(info);
    }catch(err){}
  },true);
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 论坛首页/帖子页「弹窗广告」自动屏蔽。
     *
     * 站点用的是 Discuz「popadv 弹出广告」插件：弹窗容器 #popadv_popmenu、遮罩 #popadv_popmask，
     * 由外部 popadv.js 在 window.load 时调 popadv_load()→popadv_showadv() 显示。
     *
     * 屏蔽手段（三重）：
     *  1) CSS `!important` 强制隐藏 #popadv_popmenu 与 #popadv_popmask（压过 JS 后设的 inline display:block）；
     *  2) 包装 popadv_showadv/popadv_load 为空函数，从源头不让它弹、不创建遮罩；
     *  3) MutationObserver 兜底：一旦遮罩/弹窗 DOM 出现即移除。
     * 另保留对 Discuz 系统 fwin_ 广告浮层的兜底拦截，但不碰登录/购买附件等功能浮层。
     */
    private fun injectAdBlock() {
        val js = """
(function(){
  if(window.__dzAdBlock) return; window.__dzAdBlock=1;

  // 1) CSS 强制隐藏 popadv 弹窗 + 遮罩（!important 压过 JS 的 inline style）
  var css='#popadv_popmenu,#popadv_popmask{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}';
  // 1b) 隐藏帖子正文/回帖里的图片外显（小说论坛正文为文字，图片多为封面/预览/签名图）
  css+='.t_f img,.t_f a[href*="mod=attachment"] img,td.t_f img,img[id^="aimg_"],img.zoom{display:none!important;}';
  try{
    var s=document.createElement('style');
    s.type='text/css';
    s.appendChild(document.createTextNode(css));
    (document.head||document.documentElement).appendChild(s);
  }catch(e){}

  // 2) 包装 popadv 显示函数为空，从源头阻止弹出与遮罩创建
  try{
    window.popadv_showadv=function(){ return false; };
    window.popadv_load=function(){ return false; };
    window.popadv_closeadv=function(){ return false; };
  }catch(e){}

  // 3) 移除已存在的弹窗/遮罩节点 + 监听后续新增
  function removeNode(id){
    var el=document.getElementById(id);
    if(el && el.parentNode){ el.parentNode.removeChild(el); }
  }
  function kill(){
    removeNode('popadv_popmenu');
    removeNode('popadv_popmask');
  }
  kill();
  try{
    var mo=new MutationObserver(function(){
      var p=document.getElementById('popadv_popmenu');
      if(p && p.style && p.style.display!=='none') p.style.display='none';
      var m=document.getElementById('popadv_popmask');
      if(m && m.style && m.style.display!=='none') m.style.display='none';
    });
    mo.observe(document.documentElement||document.body,{childList:true,subtree:true});
  }catch(e){}

  // ---- Discuz 系统 fwin_ 广告浮层兜底（不碰功能浮层）----
  var AD=/(下载|立即下载|douyin|抖音|app\b|推广|download|广告|ad\b)/i;
  var FUNCTIONAL=/fwin_(register|login|logging|connect|attachpay|fwin_dialog_content|message|fwin_idcheck|recommend|secqaa|seccode|activation|credits)/i;
  function killFwin(el){
    if(!el || el.style.display==='none') return;
    var id=el.id||'';
    if(FUNCTIONAL.test(id)) return;
    var txt=(el.textContent||'').slice(0,300);
    if(!AD.test(txt) && !/fwin_(ad|adv|popup|app|download|douyin)/i.test(id)) return;
    el.style.display='none';
    var cover=document.getElementById('fwin_'+id.replace(/^fwin_/,'')+'_cover');
    if(cover) cover.style.display='none';
  }
  function killFwinAll(){
    document.querySelectorAll('[id^="fwin_"]').forEach(killFwin);
  }
  killFwinAll();
  try{
    var mo2=new MutationObserver(killFwinAll);
    mo2.observe(document.documentElement||document.body,{childList:true,subtree:true});
  }catch(e){}
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 「每日签到」自动完成（k_misign 插件，带算术验证）。
     *
     * 流程：点签到入口（顶部 #k_misign_topb 图标 / 完整签到页 .J_chkitot）→ ajaxget
     * 请求 operation=qiandao，服务器若开启算术验证会返回一段含 prompt("X+Y=?") 的脚本。
     * 这里包装 window.prompt：当提示文本里能提取出「数字 运算符 数字」算式时，
     * 自动算出答案返回（不弹真实输入框），签到流程据此自动完成。
     *
     * 触发：任意页面加载后，检测到签到入口且今天未签到时自动点击（全局包装 prompt，
     * 因为首页顶部入口触发算术题也走 prompt）。用 localStorage 记录「当天已签到」避免重复点。
     */
    private fun injectAutoSign() {
        val js = """
(function(){
  if(window.__dzAutoSign) return; window.__dzAutoSign=1;

  // 从提示文本提取算式并计算（支持 + - × x * ÷ /，数字可多位）
  function calcAnswer(msg){
    var m = String(msg||'').replace(/[=\?？\s]/g,'').match(/(\d+)([+\-xX×*÷\/])(\d+)/);
    if(!m) return null;
    var a = parseInt(m[1],10), b = parseInt(m[3],10), op = m[2], r;
    if(op==='+') r = a + b;
    else if(op==='-') r = a - b;
    else if(op==='x'||op==='X'||op==='×'||op==='*') r = a * b;
    else if(op==='÷'||op==='/') r = (b===0 ? 0 : a / b);
    else return null;
    return String(r);
  }

  // 全局包装 prompt：算术验证时自动算答案（首页/签到页都生效）
  try{
    var _origPrompt = window.prompt;
    window.prompt = function(msg, def){
      try{
        var ans = calcAnswer(msg);
        if(ans !== null){
          if(window.DiscuzApp) window.DiscuzApp.signNotice('签到验证已自动计算：'+msg+' = '+ans);
          return ans;
        }
      }catch(e){}
      return (typeof _origPrompt==='function') ? _origPrompt.apply(this, arguments) : null;
    };
  }catch(e){}

  // 是否「今天已签到」：localStorage 记录，避免重复点
  function todayKey(){
    var d=new Date();
    return 'sign_' + d.getFullYear() + '-' + (d.getMonth()+1) + '-' + d.getDate();
  }
  function markSigned(){ try{ localStorage.setItem(todayKey(), '1'); }catch(e){} }
  function isSignedToday(){ try{ return localStorage.getItem(todayKey())==='1'; }catch(e){ return false; } }

  // 找签到入口：完整签到页按钮优先，其次首页顶部图标
  function findEntry(){
    var a = document.querySelector('a.J_chkitot') || document.querySelector('.J_chkitot');
    if(a) return a;
    var top = document.getElementById('k_misign_topb');
    if(top){ var link = top.querySelector('a[href*="operation=qiandao"]'); if(link) return link; }
    var img = document.getElementById('fx_checkin_b');
    if(img){ var p = img.closest('a'); if(p && /qiandao/.test(p.getAttribute('href')||'')) return p; }
    return null;
  }

  // 自动点击签到入口
  var tries = 0;
  function clickSign(){
    if(isSignedToday()) return;
    var a = findEntry();
    if(a){
      // 已签到状态下图标常会变（无 qiandao 链接），findEntry 找不到即不会误点
      if(window.DiscuzApp) window.DiscuzApp.signNotice('自动签到：已触发签到');
      try{ a.click(); }catch(e){}
      markSigned();  // 乐观标记，避免同一页面重复触发；真正结果由服务器决定
      return;
    }
    if(tries++ < 30){ setTimeout(clickSign, 500); }
  }
  setTimeout(clickSign, 1500);
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 付费附件点击拦截：站点的 showWindow('attachpay') 弹窗在本 WebView 失效且会
     * 阻止默认导航，导致 shouldOverrideUrlLoading 收不到 attachpay 请求。故在 JS 层
     * 捕获阶段拦截 attachpay 链接点击，preventDefault 后经桥交原生完成购买+下载。
     */
    private fun injectAttachPayHook() {
        val js = """
(function(){
  if(window.__dzAttachPayHook) return; window.__dzAttachPayHook=1;
  document.addEventListener('click', function(e){
    var n = e.target, hops = 0;
    while(n && n.nodeType===1 && hops++<8){
      var href = (n.getAttribute && n.getAttribute('href')) || '';
      if(href.indexOf('attachpay') >= 0){
        e.preventDefault();
        e.stopPropagation();
        try{
          if(window.DiscuzApp) window.DiscuzApp.buyAttachment(href);
          else if(window.console) console.log('[attachpay-hook] 无桥，href='+href);
        }catch(err){}
        return;
      }
      n = n.parentNode;
    }
  }, true);
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 「自动购买附件」注入：仅当设置里开启时执行。
     *
     * 逻辑：检测 Discuz 的「购买附件」浮层（标题含"购买附件"，含"售价(金币)：N"和
     * "购买后余额(金币)：M"），读取价格与余额，价格 > 0 且未超过用户设定的上限、
     * 且余额够时，自动点击浮层内的"购买附件"按钮，并经 JS 桥回 App 弹 Toast 告知。
     *
     * 安全护栏：
     *  1. 浮层必须同时出现"购买附件"标题 + "售价" + "购买后余额"三个特征才点；
     *  2. 单次售价 <= Prefs.autoPayMaxPrice 才点；
     *  3. 同一浮层只点一次（data-autopaid 标记防重）；
     *  4. 仅作用于最顶层可见浮层（"display!=='none' 且 z-index 较高"）。
     *
     * 站点的购买表单提交与"购买成功"提示由 Discuz 自带 JS 处理；
     * 购买成功后附件区会变成可下载，用户再点一次附件即真下载文件。
     */
    private fun tryAutoPay() {
        if (!Prefs.isAutoPayAttachment(this)) return
        val maxPrice = Prefs.getAutoPayMaxPrice(this)
        if (maxPrice <= 0) return
        val js = """
(function(){
  // 同一页面只注入一次；全局标记防重复
  if(window.__sxsyAutopayActive) return; window.__sxsyAutopayActive=1;
  var MAX=$maxPrice;
  var LIFETIME=0;          // 已存活毫秒
  var lastAttemptTs=0;     // 上次尝试处理浮层时间
  var handledAny=0;        // 是否已处理过浮层

  function visible(el){
    if(!el) return false;
    if(el.style.display==='none') return false;
    if(el.offsetParent===null && el.style.visibility!=='visible') return false;
    return true;
  }
  function findFloat(){
    // Discuz showWindow('attachpay') 生成的购买浮层：外壳 #fwin_attachpay / .fwin 等
    var cands=[];
    var e1=document.getElementById('fwin_attachpay'); if(e1) cands.push(e1);
    var els=document.querySelectorAll('.fwin,[id*="attachpay"],#fwin_layer_body,div[class*="fwin"]');
    for(var i=0;i<els.length;i++){ if(cands.indexOf(els[i])<0) cands.push(els[i]); }
    for(var k=0;k<cands.length;k++){
      var c=cands[k]; if(!c || !visible(c)) continue;
      var t=(c.innerText||c.textContent||'');
      // 购买确认浮层一定有"购买附件"标题；正文会含售价/作者所得等
      if(t.indexOf('购买附件')>=0 && t.indexOf('售价')>=0) return c;
    }
    return null;
  }
  function extractInt(t,re){var m=re.exec(t);return m?parseInt(m[1],10):NaN;}

  // 在浮层内找“确认购买”按钮（Discuz 文案可能是「购买」或「购买附件」），排除「关闭/取消」
  function findPayButton(f){
    var btns=f.querySelectorAll('button,a,input[type="submit"],input[type="button"],input[type="image"]');
    for(var k=0;k<btns.length;k++){
      var b=btns[k];
      if(!visible(b)) continue;
      var bt=((b.innerText||b.textContent||'')+' '+(b.value||'')).replace(/[ \t\r\n]/g,'');
      if(!bt) continue;
      if(bt.indexOf('关闭')>=0 || bt.indexOf('取消')>=0) continue;
      if(bt.indexOf('购买')>=0) return b;
    }
    return null;
  }

  function tryHandle(){
    try{
      var f=findFloat();
      if(!f) return false;
      if(f.getAttribute('data-autopaid')==='1') return true; // 已点过，不必再点
      var txt=(f.innerText||f.textContent||'').replace(/[ \t\r\n]/g,'');
      var price=extractInt(txt,/(?:售价[（(]?金币[）)]?[^0-9]*[:：]?)(\d+)/);
      if(isNaN(price) || price<=0) return true;              // 已购/免费浮层，不处理
      if(price>MAX) return true;                             // 超上限，留给用户手动
      var bal=extractInt(txt,/(?:购买后余额[（(]?金币[）)]?[^0-9]*[:：]?)(\d+)/);
      if(!isNaN(bal) && bal<price) return true;              // 金币不够
      var payBtn=findPayButton(f);
      if(!payBtn) return true;
      f.setAttribute('data-autopaid','1');
      try{if(window.DiscuzApp&&window.DiscuzApp.onAutopayNotice) window.DiscuzApp.onAutopayNotice(price,isNaN(bal)?-1:bal);}catch(_){}
      try{payBtn.click();}catch(_){}
      DebugLogSafe('已自动点购买附件: 售价'+price+'/余额'+bal);
      return true;
    }catch(_){return false;}
  }

  // 页面内日志（经 DiscuzApp 或直接 console，供诊断）
  function DebugLogSafe(msg){
    try{
      if(window.DiscuzApp&&window.DiscuzApp.logClick) window.DiscuzApp.logClick('AUTOPAY|'+msg);
      if(window.console) console.log('[sxsy-autopay]',msg);
    }catch(_){}
  }

  // 持续轮询：购买浮层是用户点附件后由 showWindow AJAX 异步弹出，
  // 因此必须长期监听，直到页面离开。
  setInterval(function(){
    LIFETIME+=400;
    try{
      var hasAttachPayLink=!!document.querySelector('a[href*="attachpay"]');
      if(!handledAny && tryHandle()){
        handledAny=1;
      }
      // 普通页面(无任何付费附件迹象)只轮询 12 秒即停，避免空转
      if(!hasAttachPayLink && LIFETIME>12000){
        window.__sxsyAutopayActive=0;
        return; // 无法真正清掉自身 interval，但置 0 后不再做任何事，可被再次注入
      }
      // 页面可能已被完全重新加载(destroy 本 context)，无需处理
    }catch(_){}
  },400);

  // 初次立刻尝试一次（若浮层已由服务器端直接渲染而非 AJAX）
  try{ if(!handledAny && tryHandle()) handledAny=1; }catch(_){}
})();
""".trimIndent()
        try {
            webView.evaluateJavascript(js, null)
            DebugLog.log("AUTOPAY", "注入自动购买检测脚本（maxPrice=$maxPrice）")
        } catch (e: Exception) {
            DebugLog.log("AUTOPAY", "注入失败: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- 派生页面独立界面

    /**
     * 目标地址是否是「帖子」页：
     * Discuz 三种形态 —— forum.php?mod=viewthread&tid=xx / thread-xx-1-1.html / viewthread.php?tid=xx
     */
    private fun isThreadUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val l = url.lowercase()
        if (!l.startsWith("http")) return false
        if (l.contains("mod=viewthread")) return true
        if (l.contains("viewthread.php")) return true
        return Regex("/thread-\\d+(-[\\d-]+)*\\.html").containsMatchIn(l)
    }

    /**
     * 是否是「派生页面」——从版面列表点进去，会另开一个列表/详情内容的页面。
     *
     * 除了帖子本身，还有两类同样会把列表文档顶掉（返回后只剩第一页）：
     *  - 主题分类筛选：挂在帖子名旁边的分类 tag（如 [纯爱]）
     *    → forum.php?mod=forumdisplay&fid=x&filter=typeid&typeid=y
     *  - 标签页：帖子里的 tag → misc.php?mod=tag&id=x
     * 它们本质上是「另一个列表」，在同一个 WebView 里加载 = 版面列表被销毁；
     * 而 Android WebView 不支持 BFCache，goBack 必然重建文档，站点 JS 追加的后续页必丢。
     *
     * 注意：版块自身的翻页（forum.php?mod=forumdisplay&fid=x&page=2）**不属于**派生页面 ——
     * 那是列表自己换页，必须留在原界面内导航，否则点「下一页」会堆出一摞界面。
     */
    private fun isDerivedPageUrl(url: String?): Boolean =
        isThreadUrl(url) || isDerivedListUrl(url)

    /**
     * 派生页面里「列表型」的那几种：标签页 / 主题分类筛选 / 淘帖专辑。
     *
     * 它们和版面列表一样会自动加载后续页（站点 JS 往 DOM 里追加），同样不能在别的界面里被顶掉，
     * 否则返回时必回第一页。区分开来是为了控制界面堆叠深度，见 openDerivedPageInNewScreen。
     */
    private fun isDerivedListUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (isThreadUrl(url)) return false
        val l = url.lowercase()
        if (!l.startsWith("http")) return false
        // 标签页 / 标签聚合页
        if (l.contains("mod=tag")) return true
        if (Regex("/tag-\\d+").containsMatchIn(l)) return true
        // 主题分类 / 分类信息 / 精华 等筛选出来的「另一个列表」
        if (l.contains("mod=forumdisplay") &&
            (l.contains("filter=typeid") || l.contains("filter=sortid") || l.contains("filter=digest"))
        ) return true
        // 淘帖 / 专辑
        if (l.contains("mod=collection")) return true
        return false
    }

    /**
     * 版面列表 → 帖子 / 分类标签 / 标签页：不在本界面内导航，而是新开一个独立界面承载它。
     *
     * 这样本界面（版面列表）只被覆盖、不被销毁：站点 JS 已经追加进 DOM 的后续页、
     * 以及滚动位置都原样保留，返回时立刻就是离开前的样子 —— 不依赖任何还原脚本，
     * 也就不会出现「只加载几页」或卡顿。
     *
     * 只在「当前不在派生页面」（或当前是列表型派生页而目标是帖子）时新开，
     * 其余情况仍在本界面内导航，避免界面无界堆叠 —— 详见函数内注释。
     * 已新开界面并需要拦截本次导航时返回 true。
     *
     * 命名说明：EXTRA_THREAD_MODE / threadMode / returningFromThreadScreen 是 1.3.19 的
     * 历史命名（当时只用于帖子页），1.3.20 起覆盖全部派生页面（帖子 / 分类筛选 / 标签）。
     */
    private fun openDerivedPageInNewScreen(url: String): Boolean {
        if (threadMode) return false                            // 本身就是独立界面
        if (!isDerivedPageUrl(url)) return false                // 只对派生页面生效
        // 当前已在派生页面时的取舍（既要避免界面无界堆叠，又要保住「列表型」页面）：
        //  - 当前是「帖子」→ 一律本界面内导航（帖子之间互跳是常态，逐个开新界面会堆一摞 WebView）
        //  - 当前是「标签 / 分类筛选 / 淘帖等列表」且目标是帖子 → 再开一层。
        //    这样 版面列表 → 标签列表 → 帖子 这条链路里，返回时标签列表
        //    （站点 JS 已追加的后续页 + 滚动位置）原样还在，而不是重建回第一页。
        //  - 其余（列表 → 列表等）→ 保持本界面内导航。
        // 堆叠深度因此有上限：版面列表 → 列表型派生页 → 帖子，共 3 层，不会无界增长。
        if (isDerivedPageUrl(lastContentPageUrl) &&
            !(isDerivedListUrl(lastContentPageUrl) && isThreadUrl(url))
        ) return false
        DebugLog.log("NAV", "派生页面新开独立界面（版面列表留在后台）: $url")
        return try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_URL, url)
                    .putExtra(EXTRA_THREAD_MODE, true)
            )
            // 独立界面盖上来，本界面稍后会 onResume：标记住，别把版面列表重载成首页
            returningFromThreadScreen = true
            true
        } catch (e: Exception) {
            DebugLog.log("NAV", "新开独立界面失败，改为本界面内导航: ${e.message}")
            false
        }
    }

    /** 预热 WebView 内核，让冷启动后首次浏览更快 */
    private fun warmUpWebView() {
        webView.postDelayed({
            try {
                val warm = WebView(applicationContext)
                warm.settings.javaScriptEnabled = true
                warm.loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                warm.destroy()
            } catch (e: Exception) { /* 预热失败不影响主流程 */ }
        }, 300L)
    }

    /** 打开论坛首页（设置里配置的网址，为空用默认首页） */
    private fun loadHome() {
        homeErrorShown = false
        loadUrl(Prefs.getHomeUrl(this))
    }

    private fun goHome() {
        loadHome()
    }

    /** 判断是否为真实 http(s) 页面（排除 about:blank / data: / 空 url） */
    private fun isRealHttpPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val low = url.lowercase()
        return low.startsWith("http://") || low.startsWith("https://")
    }

    /** 记录浏览历史（仅真实 http(s) 页面） */
    private fun recordHistory(view: WebView?, url: String?) {
        if (url.isNullOrBlank() || !isRealHttpPage(url)) return
        val title = (view?.title ?: webView.title)?.takeIf { it.isNotBlank() } ?: url
        HistoryStore.add(this, url, title)
    }

    /** 用 CSS 定位提取当前页面「书名」标签，缓存供下载命名用 */
    private fun extractBookName() {
        val title = webView.title?.takeIf { it.isNotBlank() }
        if (title != null) {
            parseBookNameFromTitle(title)?.let { parsed ->
                if (cachedBookName.isNullOrBlank()) {
                    cachedBookName = parsed
                    DebugLog.log("BOOK", "同步书名(标题解析,缓存为空): $parsed")
                }
            }
        }
        val js = """
(function(){
  try{
    var el = document.querySelector('#thread_subject') || document.querySelector('h1.ts');
    if(el){
      var t = (el.textContent || el.innerText || '').replace(/\s+/g,' ').trim();
      if(t && t.length >= 2) return t;
    }
    return '';
  }catch(e){ return ''; }
})();
""".trimIndent()
        webView.evaluateJavascript(js) { res ->
            val clean = res?.trim()?.removePrefix("\"")?.removeSuffix("\"") ?: ""
            if (clean.isNotBlank() && clean.length <= 200) {
                cachedBookName = clean
                DebugLog.log("BOOK", "CSS提取到书名(帖子页): $clean")
            } else {
                DebugLog.log("BOOK", "非帖子页，未提取书名 @ ${webView.url}")
            }
        }
    }

    /** 从 document.title 解析书名：取第一个不含站点名/后缀/系统提示词的片段 */
    private fun parseBookNameFromTitle(title: String): String? {
        val parts = title.split(Regex("\\s*-\\s*"))
        for (p in parts) {
            val t = p.trim()
            if (t.isNotEmpty() && !Regex("(?i)Powered by Discuz|尚香|书苑|书院|Discuz|论坛|书吧|网站|提示信息|提示|错误|系统|登录|注册").containsMatchIn(t)) {
                return t
            }
        }
        return null
    }

    /** 判断书名是否可用（过滤系统提示页标题/空值等） */
    private fun isUsableBookName(name: String): Boolean {
        if (name.isBlank()) return false
        val t = name.trim()
        if (t.length < 2) return false
        if (!t.any { it in '\u4e00'..'\u9fff' }) return false
        if (Regex("(?i)^(提示信息|提示|错误提示|系统提示|登录|注册|下载|附件|Powered by Discuz|尚香书苑|尚香书院|论坛|书吧)$").containsMatchIn(t)) return false
        return true
    }

    /** 统一获取当前有效书名 */
    private fun currentBookName(): String? =
        cachedBookName?.takeIf { isUsableBookName(it) }

    /** 判定“打不开”类网络错误码 */
    private fun isEntryNetworkError(code: Int): Boolean {
        return code == android.webkit.WebViewClient.ERROR_UNKNOWN ||
            code == android.webkit.WebViewClient.ERROR_HOST_LOOKUP ||
            code == android.webkit.WebViewClient.ERROR_CONNECT ||
            code == android.webkit.WebViewClient.ERROR_TIMEOUT ||
            code == android.webkit.WebViewClient.ERROR_IO ||
            code == android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
    }

    /** JS 桥接对象：浏览器通道（页面内 fetch）分块回传 */
    private inner class DownloadBridge {

        @JavascriptInterface
        fun logClick(info: String?) {
            if (!info.isNullOrBlank()) DebugLog.log("CLICK", info)
        }

        /** 自动签到结果提示 */
        @JavascriptInterface
        fun signNotice(msg: String?) {
            if (msg.isNullOrBlank()) return
            DebugLog.log("SIGN", msg)
            runOnUiThread {
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        /** JS 拦截到的付费附件链接：交给原生完成购买+下载 */
        @JavascriptInterface
        fun buyAttachment(url: String?) {
            if (url.isNullOrBlank()) return
            if (!isTrustedJsUrl(url)) {
                DebugLog.log("SEC", "拒绝非白名单购买调用: $url")
                return
            }
            runOnUiThread { handleAttachPay(url) }
        }

        /** JS 找到已生效的附件下载链接：直接交原生下载 */
        @JavascriptInterface
        fun directDownload(url: String?) {
            if (url.isNullOrBlank()) return
            if (!isTrustedJsUrl(url)) {
                DebugLog.log("SEC", "拒绝非白名单下载调用: $url")
                return
            }
            runOnUiThread { onDownloadStart(url, null, null) }
        }

        @JavascriptInterface
        fun fetchBegin(cd: String?, mime: String?) {
            val u = pendingFetchUrl ?: return
            val bn = currentBookName()
            fetchFileName = try {
                DownloadHelper.resolveWithBookName(u, if (cd.isNullOrBlank()) null else cd, bn)
            } catch (e: Exception) { "download_" + System.currentTimeMillis() }
            fetchB64.setLength(0)
            DebugLog.log("FETCH", "浏览器通道命名: $fetchFileName | 书名=$bn | cd=$cd | mime=$mime")
        }

        @JavascriptInterface
        fun fetchChunk(part: String?) {
            if (part != null) fetchB64.append(part)
        }

        @JavascriptInterface
        fun fetchEnd() {
            val data = try {
                android.util.Base64.decode(fetchB64.toString(), android.util.Base64.DEFAULT)
            } catch (e: Exception) { null }
            fetchB64.setLength(0)
            runOnUiThread {
                if (data == null) {
                    DebugLog.log("FETCH", "base64 解码失败")
                    Toast.makeText(this@MainActivity, "下载失败：数据解码错误", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                try {
                    val savedName = DownloadHelper.save(this@MainActivity, data, fetchFileName)
                    fetchFileName = savedName
                    DebugLog.log("FETCH", "保存成功: $savedName (${data.size}B)")
                    Toast.makeText(
                        this@MainActivity,
                        "下载完成：$savedName\n保存于 Download/${Prefs.getDownloadDir(this@MainActivity)}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    DebugLog.log("FETCH", "保存失败: ${e.message}")
                    Toast.makeText(this@MainActivity, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun fetchFail(info: String?) {
            DebugLog.log("FETCH", "失败: $info")
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "下载失败：服务器返回网页（${info ?: "未知原因"}）\n请确认已在论坛登录，附件权限足够",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        /**
         * 自动购买回调：页面 JS 在确认要自动点"购买附件"按钮时调用此方法，
         * 弹出 App 端 Toast 让用户知道即将扣金币（用户已在设置里开启才会触发）。
         */
        @JavascriptInterface
        fun onAutopayNotice(price: Int, balance: Int) {
            DebugLog.log("AUTOPAY", "即将自动扣 $price 金币购买附件 / 购买后余额 $balance")
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "自动购买：$price 金币（购买后余额 $balance）",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 浏览器通道状态
    private var pendingFetchUrl: String? = null
    private var fetchFileName: String = ""
    private val fetchB64 = StringBuilder()

    /**
     * 浏览器通道下载：原生请求被返回网页拦截时，改用页面内 fetch 获取文件，
     * 分块 base64 经 JS 桥回传保存。
     */
    private fun runBrowserFetch(url: String) {
        DebugLog.log("FETCH", "启动浏览器通道: $url")
        Toast.makeText(this, "正在通过浏览器通道下载…", Toast.LENGTH_SHORT).show()
        val safe = url.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
(async function(){
  try{
    var seen={};
    async function get(u, depth){
      if(depth>5) throw new Error('下载跳转超过5层');
      if(seen[u]) throw new Error('下载地址循环跳转');
      seen[u]=1;
      var r=await fetch(u,{credentials:'include',redirect:'follow'});
      var ct=r.headers.get('content-type')||'';
      if(/text\/html|xhtml/i.test(ct)){
        var h=await r.text(), title='', target=null;
        var doc=new DOMParser().parseFromString(h,'text/html');
        var titleNode=doc.querySelector('title');
        if(titleNode) title=(titleNode.textContent||'').trim();
        var meta=doc.querySelector('meta[http-equiv="refresh"],meta[http-equiv="Refresh"]');
        if(meta){
          var mc=meta.getAttribute('content')||'';
          var mi=mc.toLowerCase().indexOf('url=');
          if(mi>=0) target=mc.substring(mi+4).trim().replace(/^['\"]|['\"]$/g,'');
        }
        if(!target){
          var scripts=doc.querySelectorAll('script');
          for(var si=0;si<scripts.length&&!target;si++){
            var st=scripts[si].textContent||'';
            var keys=['location.href','location.replace','window.location','window.open','open('];
            for(var ki=0;ki<keys.length&&!target;ki++){
              var pos=st.toLowerCase().indexOf(keys[ki].toLowerCase());
              if(pos<0) continue;
              var q1=st.indexOf("'",pos), q2=st.indexOf('"',pos);
              var q=q1>=0 && (q2<0 || q1<q2) ? q1 : q2;
              if(q>=0){
                var end=st.indexOf(st.charAt(q),q+1);
                if(end>q) target=st.substring(q+1,end);
              }
            }
          }
        }
        if(!target){
          var els=doc.querySelectorAll('a[href],form[action],iframe[src],button[data-url],*[data-href]');
          for(var i=0;i<els.length;i++){
            var el=els[i], x=el.getAttribute('href')||el.getAttribute('action')||el.getAttribute('src')||el.getAttribute('data-url')||el.getAttribute('data-href')||'';
            var tx=((el.textContent||'')+' '+(el.getAttribute('onclick')||'')+' '+x);
            var xl=x.toLowerCase();
            var isSearch=xl.indexOf('search.php')>=0 || xl.indexOf('searchsubmit')>=0;
            var isDownload=/(下载|重新下载|附件|download|attachment|\\.txt(?:[?#]|$)|\\.zip(?:[?#]|$)|\\.rar(?:[?#]|$))/i.test(tx);
            if(x && !/^javascript:/i.test(x) && !isSearch && isDownload){ target=x; break; }
          }
        }
        if(target){
          target=target.replace(/&amp;/g,'&');
          return await get(new URL(target,r.url).href,depth+1);
        }
        throw new Error((title||'服务器返回网页')+'：未找到下一层下载地址');
      }
      var cd=r.headers.get('content-disposition')||'';
      var buf=await r.arrayBuffer(), b=new Uint8Array(buf), bin='';
      for(var k=0;k<b.length;k+=32768){ bin+=String.fromCharCode.apply(null,b.subarray(k,k+32768)); }
      var b64=btoa(bin), P=524288;
      window.DiscuzApp.fetchBegin(cd,ct);
      for(var j=0;j<b64.length;j+=P) window.DiscuzApp.fetchChunk(b64.substr(j,P));
      window.DiscuzApp.fetchEnd();
    }
    await get('$safe',0);
  }catch(e){ window.DiscuzApp.fetchFail(String(e)); }
})();
""".trimIndent()
        pendingFetchUrl = url
        webView.evaluateJavascript(js, null)
    }

    /** 付费附件购买链接：forum.php?mod=misc&action=attachpay&aid=... */
    private fun isAttachPayUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("action=attachpay") || l.contains("mod=attachpay")
    }

    /**
     * 主动接管付费附件：自动购买 → 成功后自动下载。
     * 站点 showWindow('attachpay') 弹窗在本 WebView 失效，故直接原生请求完成购买流程。
     */
    private fun handleAttachPay(url: String) {
        var cleanUrl = url.replace("&amp;", "&")
        if (!Prefs.isAutoPayAttachment(this)) {
            Toast.makeText(this, "已开启自动购买才能下载付费附件", Toast.LENGTH_SHORT).show()
            return
        }
        val maxPrice = Prefs.getAutoPayMaxPrice(this)
        val referer = lastContentPageUrl ?: webView.url
        // JS 桥传进来的是相对路径（如 forum.php?mod=misc&action=attachpay...），
        // 需要以当前帖子页为 base 解析成绝对 URL，否则 URL() 会抛 no protocol
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            val base = referer?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            cleanUrl = if (base != null) {
                try { java.net.URL(java.net.URL(base), cleanUrl).toString() }
                catch (e: Exception) { cleanUrl }
            } else {
                "https://sxsy45.com/$cleanUrl"
            }
        }
        DebugLog.log("BUY", "接管付费附件: $cleanUrl | maxPrice=$maxPrice | referer=$referer")
        Thread {
            val result = DownloadHelper.buyAttachment(
                cachedUserAgent, cleanUrl, referer, maxPrice
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.success && result.attachUrl != null) {
                    // 购买成功后，帖子页附件链接才会变成可下载状态；刷新帖子页后
                    // 由 onPageFinished 自动找该 aid 的下载链接并下载（否则直接拼的
                    // mod=attachment 地址会因购买状态未同步返回「提示信息」错误页）
                    val aid = Regex("aid=(\\d+)").find(result.attachUrl)?.groupValues?.get(1)
                    if (aid != null) {
                        pendingDownloadAid = aid
                        Toast.makeText(this, "购买成功，正在刷新并下载…", Toast.LENGTH_SHORT).show()
                        DebugLog.log("BUY", "购买成功，刷新帖子页后下载 aid=$aid")
                        webView.reload()
                    } else {
                        Toast.makeText(this, "购买成功，正在下载…", Toast.LENGTH_SHORT).show()
                        DebugLog.log("BUY", "购买成功，直接下载: ${result.attachUrl}")
                        onDownloadStart(result.attachUrl, null, null)
                    }
                } else {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 购买成功后刷新了帖子页：自动找该 aid 已生效的下载链接并触发原生下载 */
    private fun tryAutoDownloadAfterBuy() {
        val aid = pendingDownloadAid ?: return
        pendingDownloadAid = null
        // 已购买附件链接在帖子页里是 <span id="attach_<数字aid>"> 内的
        // <a href="...mod=attachment&aid=<base64签名>">（aid 是 base64 加密，非数字）。
        // 故用 attach_<数字aid> 容器定位，再取其中 mod=attachment 的下载链接。
        val js = """
(function(){
  var aid='$aid';
  var tries=0;
  function pickLink(){
    // 1) 精确：attach_<aid> 容器内的 mod=attachment 下载链接
    var box=document.getElementById('attach_'+aid);
    if(box){
      var a=box.querySelector('a[href*="mod=attachment"]');
      if(a){ var h=a.getAttribute('href')||''; if(h){ if(window.DiscuzApp) window.DiscuzApp.directDownload(h); return true; } }
    }
    // 2) 兜底：页面上任意 mod=attachment 下载链接
    var all=document.querySelectorAll('a[href*="mod=attachment"]');
    for(var i=0;i<all.length;i++){
      var href=all[i].getAttribute('href')||'';
      if(href && href.indexOf('attachpay')<0){ if(window.DiscuzApp) window.DiscuzApp.directDownload(href); return true; }
    }
    return false;
  }
  function report(){
    var dump=[];
    var any=document.querySelectorAll('a[href*="mod=attachment"]');
    for(var k=0;k<any.length && k<6;k++) dump.push(any[k].getAttribute('href')||'');
    if(window.DiscuzApp) window.DiscuzApp.signNotice('未找到下载链接，请手动点附件。页内 attachment 链接: '+dump.join(' | '));
  }
  if(!pickLink()){
    if(tries++ < 8){ setTimeout(function(){ if(!pickLink()){ if(tries>=8) report(); } }, 500); }
    else report();
  }
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** 论坛附件端点：不要把附件跳转页交给 WebView 渲染，直接走自研下载器 */
    private fun isDirectAttachmentUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("mod=attachment") || l.contains("attachment.php")
    }

    private fun startDirectAttachment(url: String) {
        DebugLog.log("DOWNLOAD", "直接接管附件: $url")
        onDownloadStart(url, null, null)
    }

    /** 弹窗地址转发：空白页直接吞掉；附件直接下载；普通页面回主 WebView */
    private fun forwardPopupUrl(url: String?) {
        if (url.isNullOrBlank()) return
        val lower = url.lowercase()
        if (lower.startsWith("about:") || lower.startsWith("javascript:")) {
            DebugLog.log("POPUP", "吞掉: $url")
            return
        }
        if (isDirectAttachmentUrl(url)) {
            startDirectAttachment(url)
            return
        }
        // 版面列表 → 帖子/分类标签/标签页（window.open / target=_blank 形式）：同样新开独立界面承载
        if (openDerivedPageInNewScreen(url)) return
        // 畸形登录 URL 自愈：弹窗带出死循环地址时先归正
        selfHealLoginUrl(url)?.let {
            webView.loadUrl(it)
            return
        }
        // 外部站点（弹窗/新窗口里打开的非论坛域名）一律拦截，不放回主 WebView
        if (isExternalNavUrl(url)) {
            DebugLog.log("NAV", "拦截外部站点(弹窗): $url")
            return
        }
        DebugLog.log("POPUP", "转发普通页面到主 WebView: $url")
        webView.loadUrl(url)
    }

    private fun onDownloadStart(url: String?, contentDisposition: String?, mimeType: String?) {
        if (url.isNullOrBlank()) return
        val httpUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            val base = webView.url ?: return run {
                Toast.makeText(this, "暂不支持此类下载链接", Toast.LENGTH_SHORT).show()
            }
            try {
                java.net.URL(java.net.URL(base), url).toString()
            } catch (e: Exception) {
                Toast.makeText(this, "暂不支持此类下载链接", Toast.LENGTH_SHORT).show()
                return
            }
        }
        // Android 9 及以下写公共目录需要存储权限
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            Toast.makeText(this, "请授予存储权限后重新点击下载", Toast.LENGTH_LONG).show()
            return
        }
        val cached = cachedBookName?.takeIf { isUsableBookName(it) }
        val bookName = cached
            ?: webView.title?.takeIf { it.isNotBlank() }?.let { parseBookNameFromTitle(it) }?.takeIf { isUsableBookName(it) }
        val fileName = try {
            DownloadHelper.resolveWithBookName(httpUrl, contentDisposition, bookName)
        } catch (e: Exception) { "download_" + System.currentTimeMillis() }
        DebugLog.log("DL", "开始原生下载: $httpUrl | mime=$mimeType | cd=$contentDisposition | 书名=$bookName | 命名=$fileName")
        val nameDisplay = if (fileName.startsWith("download_")) "自动识别文件名" else fileName
        Toast.makeText(this, "开始下载：$nameDisplay", Toast.LENGTH_SHORT).show()
        DownloadHelper.start(
            this, webView.settings.userAgentString, httpUrl,
            contentDisposition, webView.url,
            fallbackName = bookName
        ) { origUrl ->
            runBrowserFetch(origUrl)
        }
    }

    /** UA 由设置页开关决定，默认电脑版 */
    private fun buildUserAgent(): String {
        return if (Prefs.isDesktopMode(this)) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
    }

    /** 当前论坛的可信域名（含其子域）。取自设置里配置的网址。 */
    private fun trustedHosts(): List<String> {
        val base = try {
            Uri.parse(Prefs.getHomeUrl(this)).host?.lowercase()?.trim()
        } catch (e: Exception) { null }
        if (base.isNullOrBlank()) return emptyList()
        return listOf(base)
    }

    /** JS 桥调用白名单：相对路径放行（来自论坛页面，后续用论坛 referer 补全）；
     *  绝对 URL 仅当 host 属于可信论坛域名时放行，拦截恶意页面诱导下载/扣费 */
    private fun isTrustedJsUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return true
        val host = try { Uri.parse(url).host?.lowercase()?.trim() } catch (e: Exception) { null }
            ?: return false
        return trustedHosts().any { host == it || host.endsWith(".$it") }
    }

    /** URL 是否为「非论坛域名」的外部链接（可能带子域/同域） */
    private fun isExternalNavUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val hosts = trustedHosts()
        if (hosts.isEmpty()) return false   // 未配置可信域（理论上不会走到）则放行
        val h = try { Uri.parse(url).host?.lowercase()?.trim() } catch (e: Exception) { null }
            ?: return false
        return hosts.none { trusted ->
            h == trusted || h.endsWith(".$trusted")
        }
    }

    /** 拦截外部站点导航：命中则提示并 return true（不在 WebView / 系统浏览器打开） */
    private fun interceptExternalNav(url: String): Boolean {
        if (!isExternalNavUrl(url)) return false
        DebugLog.log("NAV", "拦截外部站点: $url")
        Toast.makeText(this, "已拦截外部链接", Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * 畸形 Discuz 登录 URL「自愈」：站内出现形如
     *   https://host/index.php/member.php?mod=logging&action=login&referer=…
     *   （index.php 被当成目录，旧版强制补斜杠后才会产生；Discuz 伪静态下 index.php/xxx
     *   会触发登录回跳 referer 递归，最终 WebView 报 ERR_TOO_MANY_REDIRECTS）时，
     *   去掉错误目录前缀把它归正为干净的登录地址，避免卡死。
     * 返回规整后的 URL；无需处理则返回 null（正常 URL 一律不误伤）。
     */
    private fun selfHealLoginUrl(url: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val hosts = trustedHosts()
        if (hosts.isEmpty()) return null
        val parsed = try { Uri.parse(url) } catch (e: Exception) { return null }
        val h = parsed.host?.lowercase()?.trim() ?: return null
        val trusted = hosts.any { h == it || h.endsWith(".$it") }
        if (!trusted) return null
        val path = parsed.path ?: ""
        // 仅当 index.php 后面还跟着 /xxx（被当目录）才算畸形；末尾纯 /index.php 是正常首页
        if (!Regex("(?i)^/index\\.php/").containsMatchIn(path)) return null
        var fixedPath = path
        while (Regex("(?i)^/index\\.php/").containsMatchIn(fixedPath)) {
            fixedPath = fixedPath.replaceFirst(Regex("(?i)^/index\\.php/"), "/")
        }
        val healed = parsed.scheme + "://" + h + fixedPath +
            (parsed.query?.let { "?$it" } ?: "") +
            (parsed.fragment?.let { "#$it" } ?: "")
        DebugLog.log("NAV", "畸形登录URL自愈: $url\n => $healed")
        return healed
    }

    private fun handleUrl(url: String): Boolean {
        val lower = url.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> false
            lower.startsWith("javascript:") -> true
            lower.startsWith("about:") -> true
            else -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    private fun loadUrl(url: String) {
        val normalized = Prefs.normalizeUrl(url)
        lastLoadedUrl = url
        webView.loadUrl(normalized)
    }

    private fun updateTitle() {
        val t = webView.title
        supportActionBar?.title = if (t.isNullOrBlank()) "尚香书院" else t
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_debug)?.isVisible = DebugLog.isEnabled()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            // 帖子独立界面标题栏的返回箭头 = 系统返回键
            android.R.id.home -> { onBackPressed(); true }
            R.id.action_stop -> { webView.stopLoading(); true }
            R.id.action_refresh -> {
                webView.clearCache(true)
                webView.reload()
                true
            }
            R.id.action_home -> { goHome(); true }
            R.id.action_downloads -> {
                startActivity(Intent(this, DownloadsActivity::class.java))
                true
            }
            R.id.action_history -> {
                startActivity(Intent(this, HistoryActivity::class.java))
                true
            }
            R.id.action_debug -> {
                showDebugLog()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        // 帖子独立界面：只在入口那一页之上还有历史时才后退；退到入口就不再退了，
        // 直接关掉本界面回到版面列表（避免登录回跳等自身重定向把用户困在两层之间）。
        val atEntry = threadMode && (webView.copyBackForwardList()?.currentIndex ?: 0) <= 1
        if (webView.canGoBack() && !atEntry) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** 诊断日志弹窗：查看 / 复制 / 清空 */
    private fun showDebugLog() {
        val tv = android.widget.TextView(this).apply {
            text = DebugLog.dump().ifBlank { "（暂无日志，请先去页面点击一次下载按钮再回来看）" }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("诊断日志")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .setNegativeButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("debug_log", DebugLog.dump()))
                Toast.makeText(this, "已复制，可粘贴发送", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清空") { _, _ -> DebugLog.clear() }
            .show()
    }
}
