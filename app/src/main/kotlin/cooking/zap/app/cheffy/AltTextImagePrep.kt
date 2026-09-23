package cooking.zap.app.cheffy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cooking.zap.app.relay.HttpClientFactory
import cooking.zap.app.souschef.SousChefImagePrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Image-URL → base64 preparation for alt-text generation (alt-text handoff
 * §4): the composer's alt editor hands the uploaded image's Blossom URL to
 * `ask-photo`, which wants raw base64 (NO `data:` prefix) of a reasonably
 * sized JPEG.
 *
 * Reuses [SousChefImagePrep]'s sampling/scaling logic at a smaller edge
 * target — a description needs far less resolution than recipe extraction,
 * and staying well under the ~10 MB wire budget matters more than detail.
 * Fails soft: any fetch/decode failure returns null and the editor falls
 * back to manual entry (hosts that block the fetch must not block the flow).
 */
object AltTextImagePrep {

    /** Longest edge sent to the describer — generous for vision, light on wire. */
    const val MAX_EDGE_PX = 1280

    private const val JPEG_QUALITY = 85

    /**
     * Fetch [url], decode, downsample to [MAX_EDGE_PX], JPEG-encode, and
     * return raw base64. CPU/network work runs off Main; never throws for
     * bad input — null means "describe it manually".
     */
    suspend fun fetchAsBase64(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = HttpClientFactory.getImageClient().newCall(
                okhttp3.Request.Builder().url(url).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes() ?: return@withContext null
            }

            // Bounds pass rejects non-images/corrupt payloads before allocation.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = SousChefImagePrep.computeInSampleSize(
                    bounds.outWidth, bounds.outHeight, MAX_EDGE_PX
                )
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return@withContext null

            val (targetW, targetH) = SousChefImagePrep.targetDimensions(
                decoded.width, decoded.height, MAX_EDGE_PX
            )
            val bitmap = if (targetW == decoded.width && targetH == decoded.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            }

            try {
                val jpeg = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.toByteArray()
                }
                Base64.getEncoder().encodeToString(jpeg)
            } finally {
                bitmap.recycle()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
