package cooking.zap.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ImageResult
import coil3.video.VideoFrameDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import cooking.zap.app.db.WispObjectBox
import cooking.zap.app.nostr.BlossomUri
import cooking.zap.app.relay.HttpClientFactory
import cooking.zap.app.repo.BlossomRepository
import cooking.zap.app.repo.BlossomUriResolver
import cooking.zap.app.repo.DiagnosticLogger
import cooking.zap.app.repo.ExchangeRateRepository
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.repo.ZapSender
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WispApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        DiagnosticLogger.init(this)
        WispObjectBox.init(this)
        ZapSender.init(this)
        ExchangeRateRepository.init(this)
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        // Resolves BUD-10 `blossom:` URIs before the network fetcher sees the
        // request. One KeyRepository, read per resolution, so the account
        // switcher is picked up without rebuilding the loader.
        val keyRepo = KeyRepository(context)
        val resolver = BlossomUriResolver(
            httpClient = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build(),
            accountServers = {
                BlossomRepository.readServersFromPrefs(context, keyRepo.getPubkeyHex())
            },
        )
        return ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                add(OkHttpNetworkFetcherFactory(callFactory = { HttpClientFactory.getImageClient() }))
                add(BlossomUriInterceptor(resolver))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.15)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    /** Rewrites `blossom:` model strings to their resolved https URL; every other request passes through. */
    private class BlossomUriInterceptor(private val resolver: BlossomUriResolver) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val data = chain.request.data as? String
            if (data == null || !data.startsWith(BlossomUri.SCHEME, ignoreCase = true)) {
                return chain.proceed()
            }
            val resolved = resolver.resolve(data)
                // No server answered — proceed and let the fetch fail as a normal image error.
                ?: return chain.proceed()
            return chain.withRequest(chain.request.newBuilder().data(resolved).build()).proceed()
        }
    }
}
