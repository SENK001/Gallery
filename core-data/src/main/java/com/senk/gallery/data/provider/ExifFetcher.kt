package com.senk.gallery.data.provider

import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import androidx.exifinterface.media.ExifInterface
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.pojo.ExifData

object ExifFetcher {

    fun queryExif(resolver: ContentResolver, id: Long): Cursor {
        val item = MediaStoreFetcher.queryItems(
            resolver,
            "${android.provider.MediaStore.MediaColumns._ID} = ?",
            arrayOf(id.toString()),
            limit = 1,
        ).firstOrNull()
        val exif = if (item != null && !item.isVideo) {
            readExif(resolver, item)
        } else {
            null
        }
        val cursor = MatrixCursor(GalleryContract.ExifColumns.ALL)
        cursor.addRow(rowOf(exif, item))
        return cursor
    }

    fun readExif(resolver: ContentResolver, item: MediaItem): ExifData? {
        val uri = item.uri ?: return null
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                val exifInterface = ExifInterface(stream)
                ExifData().apply {
                    make = exifInterface.getAttribute(ExifInterface.TAG_MAKE)
                    model = exifInterface.getAttribute(ExifInterface.TAG_MODEL)
                    lensModel = exifInterface.getAttribute(ExifInterface.TAG_LENS_MODEL)
                    fNumber = exifInterface.getAttribute(ExifInterface.TAG_F_NUMBER)
                    exposureTime = exifInterface.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                    shutterSpeed = exifInterface.getAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE)
                    aperture = exifInterface.getAttribute(ExifInterface.TAG_APERTURE_VALUE)
                    iso = exifInterface.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    focalLength = exifInterface.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    focalLength35mm = exifInterface.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
                    datetimeOriginal = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    flash = exifInterface.getAttribute(ExifInterface.TAG_FLASH)
                    whiteBalance = exifInterface.getAttribute(ExifInterface.TAG_WHITE_BALANCE)
                    software = exifInterface.getAttribute(ExifInterface.TAG_SOFTWARE)
                    artist = exifInterface.getAttribute(ExifInterface.TAG_ARTIST)
                    copyright = exifInterface.getAttribute(ExifInterface.TAG_COPYRIGHT)
                    imageWidth = exifInterface.getAttributeInt(
                        ExifInterface.TAG_IMAGE_WIDTH,
                        item.width,
                    )
                    imageHeight = exifInterface.getAttributeInt(
                        ExifInterface.TAG_IMAGE_LENGTH,
                        item.height,
                    )
                    orientation = exifInterface.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                    exifInterface.latLong?.let { latLong ->
                        if (latLong.size == 2) {
                            latitude = latLong[0]
                            longitude = latLong[1]
                            setHasLocation(true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun rowOf(exif: ExifData?, item: MediaItem?): Array<Any?> = arrayOf(
        exif?.make,
        exif?.model,
        exif?.lensModel,
        exif?.fNumber,
        exif?.exposureTime,
        exif?.shutterSpeed,
        exif?.aperture,
        exif?.iso,
        exif?.focalLength,
        exif?.focalLength35mm,
        exif?.datetimeOriginal,
        exif?.flash,
        exif?.whiteBalance,
        exif?.software,
        exif?.artist,
        exif?.copyright,
        exif?.latitude ?: 0.0,
        exif?.longitude ?: 0.0,
        if (exif?.hasLocation() == true) 1 else 0,
        exif?.imageWidth ?: item?.width ?: 0,
        exif?.imageHeight ?: item?.height ?: 0,
        exif?.orientation ?: item?.orientation ?: 0,
    )
}
