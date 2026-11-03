package com.ivy.commands.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import com.ivy.base.threading.DispatchersProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max

/**
 * Receipt photos for the assistant: files for the camera to save to,
 * and photos shrunk to a JPEG small enough to send to Gemini.
 */
class ReceiptImages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider,
) {
    /**
     * @return a content uri the camera app can write a new photo to
     */
    fun newPhotoUri(): Uri {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, "receipt_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.assistant.files", file)
    }

    /**
     * @return the photo as a JPEG at most [MAX_SIZE] pixels on its longer side, or null if it can't be read
     */
    suspend fun jpeg(uri: String): ByteArray? = withContext(dispatchers.io) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longer = max(info.size.width, info.size.height)
                if (longer > MAX_SIZE) {
                    val scale = MAX_SIZE.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        private const val DIRECTORY = "receipts"
        private const val MAX_SIZE = 1600
        private const val JPEG_QUALITY = 85
    }
}
