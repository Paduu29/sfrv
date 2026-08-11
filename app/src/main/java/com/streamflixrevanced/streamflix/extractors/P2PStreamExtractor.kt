package com.streamflixrevanced.streamflix.extractors

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import com.streamflixrevanced.streamflix.StreamFlixApp
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class P2PStreamExtractor : Extractor() {

    override val name = "P2PStream"
    override val mainUrl = "https://streamp2p.top"
    override val aliasUrls = listOf("https://upnshare.top")

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun extract(link: String): Video = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val resolved = AtomicBoolean(false)
            val pageUri = Uri.parse(link)
            val pageOrigin = "${pageUri.scheme}://${pageUri.host}"
            lateinit var webView: WebView

            fun destroyWebView() {
                handler.post {
                    runCatching {
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    }
                }
            }

            val timeout = Runnable {
                if (resolved.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(
                        Exception("Timeout waiting for ${pageUri.host} HLS stream"),
                    )
                }
                destroyWebView()
            }

            webView = WebView(StreamFlixApp.instance.applicationContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = NetworkClient.USER_AGENT
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean = false
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        if (request?.isForMainFrame != true) return false
                        val targetHost = request.url.host.orEmpty()
                        return targetHost.isNotBlank() &&
                                !targetHost.equals(pageUri.host, ignoreCase = true)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val streamUrl = request?.url?.toString().orEmpty()
                        if (isHlsManifest(streamUrl) && resolved.compareAndSet(false, true)) {
                            handler.removeCallbacks(timeout)
                            if (continuation.isActive) {
                                val requestHeaders = request?.requestHeaders.orEmpty()
                                val referer = requestHeaders.header("Referer") ?: "$pageOrigin/"
                                val origin = requestHeaders.header("Origin") ?: pageOrigin
                                val cookies = CookieManager.getInstance().getCookie(streamUrl).orEmpty()
                                val headers = buildMap {
                                    put("Referer", referer)
                                    put("Origin", origin)
                                    put("User-Agent", NetworkClient.USER_AGENT)
                                    if (cookies.isNotBlank()) put("Cookie", cookies)
                                }
                                continuation.resume(
                                    Video(
                                        source = streamUrl,
                                        type = MimeTypes.APPLICATION_M3U8,
                                        headers = headers,
                                    ),
                                )
                            }
                            destroyWebView()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        listOf(1_000L, 3_000L, 6_000L).forEach { delay ->
                            handler.postDelayed({
                                if (!resolved.get()) {
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            try { document.body && document.body.click(); } catch (_) {}
                                            try { document.querySelector('video')?.play(); } catch (_) {}
                                            try { window.jwplayer && jwplayer().play(true); } catch (_) {}
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                }
                            }, delay)
                        }
                    }
                }
            }

            handler.postDelayed(timeout, STREAM_TIMEOUT_MS)
            continuation.invokeOnCancellation {
                resolved.set(true)
                handler.removeCallbacks(timeout)
                destroyWebView()
            }
            webView.loadUrl(
                link,
                mapOf(
                    "Referer" to "$pageOrigin/",
                    "User-Agent" to NetworkClient.USER_AGENT,
                ),
            )
        }
    }

    private fun isHlsManifest(url: String): Boolean {
        val normalized = url.substringBefore('#').lowercase()
        return normalized.substringBefore('?').endsWith(".m3u8") ||
                normalized.contains(".m3u8?")
    }

    private fun Map<String, String>.header(name: String): String? {
        return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private companion object {
        const val STREAM_TIMEOUT_MS = 45_000L
    }
}
