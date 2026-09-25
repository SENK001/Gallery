package com.senk.gallery.ui.gl

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * 动态照片的内嵌视频数据源：视频是文件尾部的最后 [videoLength] 字节
 * （Google Motion Photo 容器格式 Item:Length，或旧版 MicroVideoOffset）。
 * 用 pread 直接读取该范围，无需先把视频拷出来。
 */
internal class EmbeddedVideoDataSource(
    context: Context,
    uri: Uri,
    private val videoLength: Long,
) : MediaDataSource() {

    private val descriptor: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("Cannot open $uri")

    private val start: Long = descriptor.statSize - videoLength

    init {
        if (videoLength <= 0L || start < 0L) {
            descriptor.close()
            throw IOException("Invalid embedded video range: length=$videoLength")
        }
    }

    override fun getSize(): Long = videoLength

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L || position >= videoLength || size <= 0) {
            return -1
        }
        val count = min(size.toLong(), videoLength - position).toInt()
        val target = ByteBuffer.wrap(buffer, offset, count)
        val read = try {
            Os.pread(descriptor.fileDescriptor, target, start + position)
        } catch (e: Exception) {
            -1
        }
        return if (read <= 0) -1 else read
    }

    override fun close() {
        descriptor.close()
    }
}
