package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.UrlSanitizerAndSearch
import com.example.engine.VellumShieldEngine
import com.example.viewmodel.BrowserTab
import com.example.viewmodel.BrowserViewModel
import com.example.viewmodel.WebViewCommand

private const val MAX_ACTIVE_WEBVIEWS = 3

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

class VellumJsBridge(
    private val mainHandler: Handler,
    private val onElementZapped: (String) -> Unit,
    private val onReaderExtracted: (String, String, String) -> Unit
) {
    @JavascriptInterface
    fun onElementZapped(selector: String) {
        mainHandler.post {
            onElementZapped.invoke(selector)
        }
    }

    @JavascriptInterface
    fun onReaderArticleExtracted(title: String, byline: String, joinedParagraphs: String) {
        mainHandler.post {
            onReaderExtracted.invoke(title, byline, joinedParagraphs)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VellumWebViewViewport(
    activeTab: BrowserTab,
    viewModel: BrowserViewModel,
    hideCookieBanners: Boolean,
    stripTrackingParams: Boolean,
    isZapModeActive: Boolean,
    webViewCommand: WebViewCommand?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Maintain an LRU pool of up to 3 WebViews keyed by tabId so memory stays bounded
    val webViewPool = remember { LinkedHashMap<String, WebView>(MAX_ACTIVE_WEBVIEWS, 0.75f, true) }
    val lastLoadedUrlByTab = remember { mutableMapOf<String, String>() }

    DisposableEffect(Unit) {
        onDispose {
            webViewPool.values.forEach { wv ->
                runCatching { wv.destroy() }
            }
            webViewPool.clear()
        }
    }

    val currentWebView = remember(activeTab.id) {
        val existing = webViewPool[activeTab.id]
        if (existing != null) {
            existing
        } else {
            // Evict oldest WebView if pool exceeds MAX_ACTIVE_WEBVIEWS
            if (webViewPool.size >= MAX_ACTIVE_WEBVIEWS) {
                val eldestKey = webViewPool.entries.firstOrNull()?.key
                if (eldestKey != null) {
                    val evicted = webViewPool.remove(eldestKey)
                    lastLoadedUrlByTab.remove(eldestKey)
                    runCatching { evicted?.destroy() }
                }
            }
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    setSupportMultipleWindows(false) // Prevents malicious pop-under windows
                    javaScriptCanOpenWindowsAutomatically = false
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

                addJavascriptInterface(
                    VellumJsBridge(
                        mainHandler = mainHandler,
                        onElementZapped = { selector ->
                            viewModel.onElementZappedFromPage(selector)
                        },
                        onReaderExtracted = { title, byline, paragraphs ->
                            viewModel.onReaderArticleExtractedFromDom(title, byline, paragraphs)
                        }
                    ),
                    "VellumBridge"
                )

                // Long-press link for Vellum Peek Preview
                setOnLongClickListener { view ->
                    val wv = view as? WebView ?: return@setOnLongClickListener false
                    val hitResult = wv.hitTestResult
                    val extra = hitResult.extra
                    if ((hitResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                            hitResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                        !extra.isNullOrBlank()
                    ) {
                        viewModel.openPeekPreview(extra)
                        true
                    } else {
                        false
                    }
                }

                // File Download Handler -> Vellum Downloads Vault
                setDownloadListener(
                    DownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        viewModel.recordFileDownload(
                            fileName = fileName,
                            url = url,
                            mimeType = mimeType ?: "application/octet-stream",
                            contentLength = contentLength
                        )
                    }
                )
            }.also { created ->
                webViewPool[activeTab.id] = created
            }
        }
    }

    // Keep WebViewClient & WebChromeClient updated with current tab + shield rules
    LaunchedEffect(activeTab.id, activeTab.domain, activeTab.isDesktopMode, hideCookieBanners, stripTrackingParams) {
        val defaultUa = WebSettings.getDefaultUserAgent(context)
        currentWebView.settings.userAgentString = if (activeTab.isDesktopMode) {
            DESKTOP_USER_AGENT
        } else {
            defaultUa
        }

        currentWebView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUri = request?.url ?: return null
                // Never block top-level main frame navigation
                if (request.isForMainFrame) return null

                val pageDomain = activeTab.domain
                val shieldEnabled = viewModel.isShieldEnabledForDomain(pageDomain)
                val strictMode = viewModel.isStrictModeForDomain(pageDomain)

                val decision = VellumShieldEngine.evaluateNetworkRequest(
                    requestUri = reqUri,
                    pageUrl = activeTab.url,
                    shieldEnabled = shieldEnabled,
                    strictMode = strictMode
                )

                if (decision.shouldBlock) {
                    mainHandler.post {
                        viewModel.onRequestBlocked(
                            tabId = activeTab.id,
                            matchedDomain = decision.matchedDomain,
                            category = decision.category
                        )
                    }
                    return decision.response
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val targetUri = request?.url ?: return false
                val targetUrl = targetUri.toString()

                if (targetUrl.startsWith("vellum://shield-test", ignoreCase = true)) {
                    viewModel.navigateToInput("vellum://shield-test")
                    return true
                }

                if (stripTrackingParams && targetUrl.contains("?")) {
                    val sanitized = UrlSanitizerAndSearch.sanitizeUrl(targetUrl)
                    if (sanitized.strippedParamsCount > 0 && sanitized.finalUrl != targetUrl) {
                        view?.loadUrl(sanitized.finalUrl)
                        viewModel.showBanner("Stripped ${sanitized.strippedParamsCount} tracking param(s)")
                        return true
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val effectiveUrl = if (lastLoadedUrlByTab[activeTab.id] == "vellum://shield-test") {
                    "vellum://shield-test"
                } else {
                    url.orEmpty()
                }
                viewModel.onTabUrlAndStateUpdated(
                    tabId = activeTab.id,
                    url = effectiveUrl,
                    title = view?.title,
                    isLoading = true,
                    progress = 20,
                    canGoBack = view?.canGoBack() == true,
                    canGoForward = view?.canGoForward() == true
                )
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                injectCosmeticShield(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectCosmeticShield(view)
                val effectiveUrl = if (lastLoadedUrlByTab[activeTab.id] == "vellum://shield-test") {
                    "vellum://shield-test"
                } else {
                    url.orEmpty()
                }
                viewModel.onTabUrlAndStateUpdated(
                    tabId = activeTab.id,
                    url = effectiveUrl,
                    title = view?.title,
                    isLoading = false,
                    progress = 100,
                    canGoBack = view?.canGoBack() == true,
                    canGoForward = view?.canGoForward() == true
                )
                viewModel.onPageFinishedRecordHistory(
                    tabId = activeTab.id,
                    url = effectiveUrl,
                    title = view?.title
                )
            }

            private fun injectCosmeticShield(view: WebView?) {
                val domain = activeTab.domain
                val shieldOn = viewModel.isShieldEnabledForDomain(domain)
                val zapped = viewModel.getZappedSelectorsForDomain(domain)
                val script = VellumShieldEngine.buildCosmeticInjectionScript(
                    shieldEnabled = shieldOn,
                    hideCookieBanners = hideCookieBanners,
                    customZappedSelectors = zapped
                )
                if (script.isNotBlank()) {
                    view?.evaluateJavascript(script, null)
                }
            }
        }

        currentWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                val effectiveUrl = if (lastLoadedUrlByTab[activeTab.id] == "vellum://shield-test") {
                    "vellum://shield-test"
                } else {
                    view?.url ?: activeTab.url
                }
                viewModel.onTabUrlAndStateUpdated(
                    tabId = activeTab.id,
                    url = effectiveUrl,
                    title = view?.title,
                    isLoading = newProgress < 100,
                    progress = newProgress,
                    canGoBack = view?.canGoBack() == true,
                    canGoForward = view?.canGoForward() == true
                )
            }
        }
    }

    // Trigger URL loading when activeTab.url changes
    LaunchedEffect(activeTab.id, activeTab.url) {
        val targetUrl = activeTab.url
        if (targetUrl.isNotBlank() && lastLoadedUrlByTab[activeTab.id] != targetUrl) {
            lastLoadedUrlByTab[activeTab.id] = targetUrl
            if (targetUrl == "vellum://shield-test") {
                val shieldOn = viewModel.isShieldEnabledForDomain("vellum://shield-test")
                val html = VellumShieldEngine.buildShieldTestBenchHtml(shieldOn)
                currentWebView.loadDataWithBaseURL(
                    "https://vellum.diagnostic.local/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            } else {
                currentWebView.loadUrl(targetUrl)
            }
        }
    }

    // Activate or deactivate Zap Distraction element picker in the live DOM
    LaunchedEffect(isZapModeActive, activeTab.id) {
        val script = VellumShieldEngine.buildZapModeActivationScript(isZapModeActive)
        currentWebView.evaluateJavascript(script, null)
    }

    // Handle explicit WebView commands (Back, Forward, Reload, Reader extraction, Find in page)
    LaunchedEffect(webViewCommand) {
        when (val cmd = webViewCommand) {
            is WebViewCommand.GoBack -> {
                if (currentWebView.canGoBack()) {
                    currentWebView.goBack()
                } else {
                    viewModel.returnToStartPage()
                }
                viewModel.consumeWebViewCommand()
            }
            is WebViewCommand.GoForward -> {
                if (currentWebView.canGoForward()) {
                    currentWebView.goForward()
                }
                viewModel.consumeWebViewCommand()
            }
            is WebViewCommand.Reload -> {
                if (activeTab.url == "vellum://shield-test") {
                    val shieldOn = viewModel.isShieldEnabledForDomain("vellum://shield-test")
                    val html = VellumShieldEngine.buildShieldTestBenchHtml(shieldOn)
                    currentWebView.loadDataWithBaseURL(
                        "https://vellum.diagnostic.local/",
                        html,
                        "text/html",
                        "UTF-8",
                        null
                    )
                } else {
                    currentWebView.reload()
                }
                viewModel.consumeWebViewCommand()
            }
            is WebViewCommand.ExtractReaderArticle -> {
                currentWebView.evaluateJavascript(
                    VellumShieldEngine.buildReaderExtractionScript(),
                    null
                )
                viewModel.consumeWebViewCommand()
            }
            is WebViewCommand.FindText -> {
                if (cmd.query.isNotBlank()) {
                    currentWebView.findAllAsync(cmd.query)
                } else {
                    currentWebView.clearMatches()
                }
                viewModel.consumeWebViewCommand()
            }
            is WebViewCommand.FindNext -> {
                currentWebView.findNext(cmd.forward)
                viewModel.consumeWebViewCommand()
            }
            is WebViewCommand.ClearFind -> {
                currentWebView.clearMatches()
                viewModel.consumeWebViewCommand()
            }
            null -> Unit
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { currentWebView },
            update = { /* Managed via LaunchedEffects to avoid redundant recomposition reloads */ },
            modifier = Modifier
                .fillMaxSize()
                .testTag("browser_webview")
        )

        // Floating HUD Banner when Zap Distraction Mode is active
        if (isZapModeActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = "Zap Mode",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Zap Mode: Tap any element on the page to hide it permanently",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = { viewModel.cancelZapDistractionMode() },
                    modifier = Modifier.testTag("cancel_zap_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Cancel Zap Mode",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
