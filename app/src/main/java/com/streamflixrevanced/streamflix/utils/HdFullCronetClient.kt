package com.streamflixrevanced.streamflix.utils

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Small Cronet transport dedicated to HDFull.
 *
 * Cronet does not share WebView's cookie store, so callers send the current CookieManager cookie
 * header explicitly. Response cookies are copied back into CookieManager, which keeps native
 * requests, the Cloudflare WebView fallback, and image requests on one session.
 */
object HdFullCronetClient {
    private const val CACHE_SIZE_BYTES = 20L * 1024L * 1024L
    private const val READ_BUFFER_SIZE = 32 * 1024

    data class Response(
        val statusCode: Int,
        val finalUrl: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299
        fun bodyAsString(): String = body.toString(Charsets.UTF_8)
    }

    class Call internal constructor() {
        @Volatile
        private var request: UrlRequest? = null
        private val cancelled = AtomicBoolean(false)

        internal fun attach(request: UrlRequest) {
            this.request = request
            if (cancelled.get()) request.cancel()
        }

        fun cancel() {
            cancelled.set(true)
            request?.cancel()
        }

        internal fun isCancelled(): Boolean = cancelled.get()
    }

    @Volatile
    private var engine: CronetEngine? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hdfull-cronet").apply { isDaemon = true }
    }

    fun init(context: Context) {
        engine(context)
    }

    suspend fun request(
        context: Context,
        url: String,
        method: String = "GET",
        headers: Map<String, String>,
        body: ByteArray? = null,
        useCache: Boolean = method.equals("GET", ignoreCase = true),
    ): Response = suspendCancellableCoroutine { continuation ->
        val call = request(context, url, method, headers, body, useCache) { result ->
            if (!continuation.isActive) return@request
            result.fold(continuation::resume, continuation::resumeWithException)
        }
        continuation.invokeOnCancellation { call.cancel() }
    }

    private fun request(
        context: Context,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        useCache: Boolean,
        callback: (Result<Response>) -> Unit,
    ): Call {
        val call = Call()
        val completed = AtomicBoolean(false)
        val output = ByteArrayOutputStream()

        fun complete(result: Result<Response>) {
            if (!call.isCancelled() && completed.compareAndSet(false, true)) callback(result)
        }

        val requestCallback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String,
            ) {
                persistCookies(info)
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                persistCookies(info)
                request.read(ByteBuffer.allocateDirect(READ_BUFFER_SIZE))
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer,
            ) {
                byteBuffer.flip()
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                output.write(bytes)
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                persistCookies(info)
                complete(
                    Result.success(
                        Response(
                            statusCode = info.httpStatusCode,
                            finalUrl = info.url,
                            headers = info.allHeaders,
                            body = output.toByteArray(),
                        )
                    )
                )
            }

            override fun onFailed(
                request: UrlRequest,
                info: UrlResponseInfo,
                error: CronetException,
            ) = complete(Result.failure(error))

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo) = Unit
        }

        val builder = engine(context).newUrlRequestBuilder(url, requestCallback, executor)
            .setHttpMethod(method)
        if (!useCache) builder.disableCache()
        headers.forEach { (name, value) ->
            // Cronet negotiates and decodes compression itself. Supplying this header is ignored
            // and makes Cronet emit a warning with a full stack trace for every request.
            if (value.isNotBlank() && !name.equals("Accept-Encoding", ignoreCase = true)) {
                builder.addHeader(name, value)
            }
        }
        if (body != null) {
            builder.setUploadDataProvider(UploadDataProviders.create(body), executor)
        }
        val request = builder.build()
        call.attach(request)
        request.start()
        return call
    }

    @Synchronized
    private fun engine(context: Context): CronetEngine {
        engine?.let { return it }
        return CronetEngine.Builder(context.applicationContext)
            .enableHttp2(true)
            .enableQuic(true)
            .enableBrotli(true)
            .setStoragePath(context.cacheDir.resolve("hdfull-cronet").apply { mkdirs() }.absolutePath)
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, CACHE_SIZE_BYTES)
            .build()
            .also { engine = it }
    }

    private fun persistCookies(info: UrlResponseInfo) {
        val cookies = info.allHeaders.entries
            .filter { (name, _) -> name.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
        if (cookies.isEmpty()) return

        CookieManager.getInstance().apply {
            cookies.forEach { cookie -> setCookie(info.url, cookie) }
            flush()
        }
    }
}
