package sh.hnet.comfychair.connection

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.ServerImage
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Dedicated credential; plaintext HTTP requires explicit per-origin LAN consent. No redirects/retries. */
class ServerMediaDelete(client: OkHttpClient, private val base: HttpUrl, private val secret: String, private val allowLan: Boolean = false) {
    val credentialTransportAllowed: Boolean get() = base.isHttps || allowLan || base.host in setOf("localhost", "127.0.0.1", "::1")
    class HttpFailure(val status: Int) : IOException("Media HTTP $status")
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
    data class Item(val index: Int, val status: String)
    data class Manifest(val token: String, val results: List<Item>)
    private suspend fun request(route: String, payload: JSONObject? = null): JSONObject = suspendCancellableCoroutine { c ->
        if (route != "capabilities") require(secret.isNotBlank()) { "Dedicated deletion key missing" }
        val builder = Request.Builder().url(base.newBuilder().addPathSegments("comfychair/v1/media/$route").build())
        if (secret.isNotEmpty()) {
            require(credentialTransportAllowed) { "Plain HTTP deletion requires explicit trusted-LAN consent" }
            builder.header("X-ComfyChair-Delete", secret)
        }
        if (payload != null) builder.post(payload.toString().toRequestBody("application/json".toMediaType()))
        val call = http.newCall(builder.build())
        c.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { c.resumeWith(Result.failure(IOException("Media request failed"))) }
            override fun onResponse(call: Call, response: Response) {
                c.resumeWith(runCatching { response.use {
                    if (!it.isSuccessful) throw HttpFailure(it.code)
                    val body = it.body ?: throw IOException("Empty media response")
                    require(body.contentLength() <= 1024 * 1024)
                    val bytes = body.byteStream().readNBytes(1024 * 1024 + 1)
                    require(bytes.size <= 1024 * 1024)
                    JSONObject(bytes.toString(Charsets.UTF_8))
                } })
            }
        })
    }
    suspend fun available(): Boolean = request("capabilities").let {
        secret.isNotBlank() && it.getInt("version") == 1 && it.getBoolean("delete") && it.getInt("max_batch") == 50 &&
            (it.optString("auth_mode") != "lan" || allowLan)
    }
    suspend fun missing(): Set<ServerImage> {
        val a = request("missing").getJSONArray("items")
        require(a.length() <= 5000)
        return (0 until a.length()).mapNotNull { i ->
            val v = a.getJSONObject(i)
            sh.hnet.comfychair.model.ServerImageDiscovery.descriptor(
                v.getString("subfolder").let { if (it.isEmpty()) "" else "$it/" } + v.getString("filename"), v.getString("type"))
        }.toSet()
    }
    suspend fun prepare(images: List<ServerImage>): Manifest {
        require(images.size in 1..50)
        val r = request("prepare", JSONObject().put("items", JSONArray().apply { images.forEach { image ->
            put(JSONObject().put("type", image.type).put("subfolder", image.subfolder).put("filename", image.filename))
        } }))
        return Manifest(r.getString("token").also { require(it.length in 32..128) }, results(r, images.size))
    }
    suspend fun delete(manifest: Manifest): List<Item> = results(request("delete", JSONObject().put("token", manifest.token)), manifest.results.size)
    private fun results(r: JSONObject, count: Int): List<Item> {
        val a = r.getJSONArray("items")
        require(a.length() == count)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Item(o.getInt("index"), o.getString("status")).also {
                require(it.index == i && it.status in setOf("ready", "success", "not_found", "conflict", "busy", "invalid", "failed"))
            }
        }
    }
}
