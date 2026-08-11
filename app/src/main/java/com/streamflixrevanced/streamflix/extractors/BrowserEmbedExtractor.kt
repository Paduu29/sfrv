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

open class BrowserEmbedExtractor(
    final override val name: String,
    final override val mainUrl: String,
    final override val aliasUrls: List<String> = emptyList(),
) : Extractor() {

    override suspend fun extract(link: String): Video = captureManifest(link)

    @SuppressLint("SetJavaScriptEnabled")
    protected suspend fun captureManifest(
        link: String,
        subtitles: List<Video.Subtitle> = emptyList(),
    ): Video = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val resolved = AtomicBoolean(false)
            val pageUri = Uri.parse(link)
            val pageOrigin = "${pageUri.scheme}://${pageUri.host}"
            var currentPageOrigin = pageOrigin
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
                        Exception("Timeout waiting for $name stream"),
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
                        return request?.isForMainFrame == true && request.hasGesture()
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val streamUrl = request?.url?.toString().orEmpty()
                        if (manifestType(streamUrl) != null && resolved.compareAndSet(false, true)) {
                            handler.removeCallbacks(timeout)
                            if (continuation.isActive) {
                                val requestHeaders = request?.requestHeaders.orEmpty()
                                val referer = requestHeaders.header("Referer")
                                    ?: "$currentPageOrigin/"
                                val origin = requestHeaders.header("Origin") ?: currentPageOrigin
                                val cookies = CookieManager.getInstance()
                                    .getCookie(streamUrl).orEmpty()
                                continuation.resume(
                                    Video(
                                        source = streamUrl,
                                        subtitles = subtitles,
                                        type = manifestType(streamUrl),
                                        headers = buildMap {
                                            put("Referer", referer)
                                            put("Origin", origin)
                                            put("User-Agent", NetworkClient.USER_AGENT)
                                            if (cookies.isNotBlank()) put("Cookie", cookies)
                                        },
                                    ),
                                )
                            }
                            destroyWebView()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        Uri.parse(url).let { uri ->
                            if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                                currentPageOrigin = "${uri.scheme}://${uri.host}"
                            }
                        }
                        listOf(500L, 1_500L, 3_000L, 6_000L).forEach { delay ->
                            handler.postDelayed({
                                if (!resolved.get()) {
                                    view?.evaluateJavascript(PLAY_SCRIPT, null)
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

    private fun manifestType(url: String): String? {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            return null
        }
        val lower = url.substringBefore('#').lowercase()
        if (BLOCKED_URL_PARTS.any(lower::contains)) return null
        return when {
            lower.substringBefore('?').endsWith(".m3u8") || lower.contains(".m3u8?") ->
                MimeTypes.APPLICATION_M3U8
            lower.substringBefore('?').endsWith(".mpd") || lower.contains(".mpd?") ->
                "application/dash+xml"
            else -> null
        }
    }

    private fun Map<String, String>.header(name: String): String? {
        return entries.firstOrNull { it.key.equals(name, true) }?.value
    }

    private companion object {
        const val STREAM_TIMEOUT_MS = 45_000L
        val BLOCKED_URL_PARTS = listOf("doubleclick", "googleads", "/ads/", "vast")
        val PLAY_SCRIPT =
            """
            (function() {
                try { document.querySelector('video')?.play(); } catch (_) {}
                try { window.jwplayer && jwplayer().play(true); } catch (_) {}
                try {
                    document.querySelector(
                        '[aria-label*="play" i], .vjs-big-play-button, .plyr__control--overlaid'
                    )?.click();
                } catch (_) {}
            })();
            """.trimIndent()
    }
}

class VideasyPlayerExtractor : BrowserEmbedExtractor(
    name = "Videasy Player",
    mainUrl = "https://player.videasy.net",
    aliasUrls = listOf("https://player.videasy.to"),
) {
    override suspend fun extract(link: String): Video = VideasyExtractor().extract(link)
}

class PeachifyExtractor : BrowserEmbedExtractor("Peachify", "https://peachify.top")

class VidCoreExtractor : BrowserEmbedExtractor("VidCore", "https://vidcore.net")

class VidZenExtractor : BrowserEmbedExtractor("VidZen", "https://vidzen.fun")

class EmbedMasterExtractor : BrowserEmbedExtractor(
    name = "EmbedMaster",
    mainUrl = "https://embedmaster.link",
    aliasUrls = listOf("https://embdmstrplayer.com"),
)

class VidnestFunExtractor : BrowserEmbedExtractor("VidNest", "https://vidnest.fun")

class VidPlusExtractor : BrowserEmbedExtractor("VidPlus", "https://player.vidplus.to")

class StreamCastHubExtractor : BrowserEmbedExtractor(
    name = "StreamCastHub",
    mainUrl = "https://watch.streamcasthub.store",
)

class MappleExtractor : BrowserEmbedExtractor(
    name = "Mapple",
    mainUrl = "https://mapple.uk",
    aliasUrls = listOf("https://mapple.rip"),
)
