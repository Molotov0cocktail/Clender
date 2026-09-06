package com.molotov.clender.data.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException

internal object BackgroundImageDecoder {
    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = BackgroundPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
        if (sample == 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let {
            orient(it, bytes)
        }
    }

    private fun orient(bitmap: Bitmap, bytes: ByteArray): Bitmap =
        applyOrientation(bitmap, readOrientation(bytes))

    internal fun readOrientation(bytes: ByteArray): Int = try {
        ByteArrayInputStream(bytes).use {
            ExifInterface(
                it
            ).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    internal fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)

            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(ROTATION_180)

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(ROTATION_90)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(ROTATION_90)

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-ROTATION_90)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-ROTATION_90)

            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private const val ROTATION_90 = 90f
    private const val ROTATION_180 = 180f
}
