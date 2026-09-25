package com.senk.gallery.ui

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.data.provider.GalleryCursorReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaQuery {

    private const val TAG = "GalleryQuery"

    suspend fun media(resolver: ContentResolver, pageUri: Uri): List<MediaItem> =
        withContext(Dispatchers.IO) {
            try {
                resolver.query(pageUri, null, null, null, null)?.use {
                    GalleryCursorReader.readMediaList(it)
                } ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "media query failed: $pageUri", e)
                emptyList()
            }
        }

    suspend fun albums(
        resolver: ContentResolver,
        category: String,
    ): List<GalleryCursorReader.AlbumInfo> =
        withContext(Dispatchers.IO) {
            try {
                resolver.query(
                    GalleryContract.albumsUri(category),
                    null,
                    null,
                    null,
                    null,
                )?.use { GalleryCursorReader.readAlbumList(it) } ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "albums query failed: $category", e)
                emptyList()
            }
        }
}
