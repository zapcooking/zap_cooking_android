package cooking.zap.app.repo

import android.util.LruCache
import cooking.zap.app.nostr.BlossomUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Turns a BUD-10 `blossom:` URI into a concrete https URL by probing the
 * servers that might hold the blob — the URI's own `xs` hints first, then the
 * signed-in account's configured Blossom servers — with HEAD requests on the
 * BUD-01 route (`https://<server>/<sha256>`); the first 200 wins. Mirrors
 * amethyst's coil-path resolver. Resolutions are cached per URI — Coil, the
 * media pager, and the full-screen viewer can each ask for the same URI in
 * one render — and misses for five minutes, so an unreachable blob doesn't
 * re-probe on every scroll.
 */
class BlossomUriResolver(
    private val httpClient: OkHttpClient,
    private val accountServers: () -> List<String>,
) {
    private val resolved = LruCache<String, String>(CACHE_SIZE)
    private val misses = LruCache<String, Long>(CACHE_SIZE)

    suspend fun resolve(rawUri: String): String? {
        resolved.get(rawUri)?.let { return it }
        misses.get(rawUri)?.let { at -> if (System.currentTimeMillis() - at < MISS_TTL_MS) return null }
        val uri = BlossomUri.parse(rawUri) ?: return null

        val servers = (uri.serverHints + accountServers()).distinct()
        val hit = withContext(Dispatchers.IO) {
            coroutineScope {
                servers.map { server -> async { probe(uri.candidateUrls(server)) } }
                    .awaitAll()
                    .firstOrNull { it != null }
            }
        }
        if (hit != null) {
            resolved.put(rawUri, hit)
        } else {
            misses.put(rawUri, System.currentTimeMillis())
        }
        return hit
    }

    private fun probe(url: String): String? = runCatching {
        httpClient.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            if (response.isSuccessful) url else null
        }
    }.getOrNull()

    private companion object {
        const val CACHE_SIZE = 200
        const val MISS_TTL_MS = 5 * 60 * 1000L
    }
}
