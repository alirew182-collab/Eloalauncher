package dev.eloa.launcher.domain.java.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

val LauncherJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

class HttpStatusException(val code: Int, message: String) : IOException(message)

/** Cancellable await for OkHttp calls. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Fetches small text documents (metadata) from trusted sources, bounded in size. */
class MetaClient(
    private val client: OkHttpClient,
    private val policy: SourcePolicy = SourcePolicy.OFFICIAL,
    private val maxBytes: Long = 32L * 1024 * 1024,
) {
    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val parsed = SourcePolicy.check(policy, url)
        client.newCall(Request.Builder().url(parsed).header("Accept", "application/json, application/xml, */*").build()).await().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code} for ${parsed.host}${parsed.encodedPath}")
            val body = resp.body ?: throw IOException("Empty body")
            val len = body.contentLength()
            if (len > maxBytes) throw IOException("Response too large ($len bytes)")
            val source = body.source()
            source.request(maxBytes + 1)
            if (source.buffer.size > maxBytes) throw IOException("Response too large")
            source.buffer.readUtf8()
        }
    }

    suspend fun getBytes(url: String, max: Long = maxBytes): ByteArray = withContext(Dispatchers.IO) {
        val parsed = SourcePolicy.check(policy, url)
        client.newCall(Request.Builder().url(parsed).build()).await().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code}")
            val source = resp.body?.source() ?: throw IOException("Empty body")
            source.request(max + 1)
            if (source.buffer.size > max) throw IOException("Response too large")
            source.buffer.readByteArray()
        }
    }
}
