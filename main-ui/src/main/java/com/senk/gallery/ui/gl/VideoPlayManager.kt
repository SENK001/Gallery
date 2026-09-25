package com.senk.gallery.ui.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * 视频播放封装：MediaPlayer + SurfaceTexture(GL_TEXTURE_EXTERNAL_OES) + 播放状态机。
 *
 * - 独立视频文件用 [VideoSource.File]；
 * - 动态照片内嵌视频（位于文件尾部的 MP4）用 [VideoSource.Embedded]。
 *
 * 调用方（GL 渲染器）在 GL 上下文创建后调用 [attachTexture] 绑定 OES 纹理名，
 * 绘制时用 [surfaceTexture] 做 updateTexImage / 取变换矩阵，尺寸读 [videoWidth]/[videoHeight]。
 * 所有播放控制方法可在主线程调用；[attachTexture] 需在 GL 线程调用。
 */
class VideoPlayManager(private val context: Context) {

    interface Listener {
        fun onPrepared(durationMs: Long)
        fun onCompletion()
        fun onError(message: String)
        fun onFrameAvailable()
    }

    sealed class VideoSource {

        data class File(val uri: Uri) : VideoSource()

        data class Embedded(val uri: Uri, val videoLength: Long) : VideoSource()
    }

    var listener: Listener? = null

    @Volatile
    var surfaceTexture: SurfaceTexture? = null
        private set

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var mediaDataSource: MediaDataSource? = null
    private var surface: Surface? = null
    private var source: VideoSource? = null
    private var prepared = false
    private var playWhenReady = false
    private var released = false

    /** GL 线程：GL 上下文（重建）后，用新生成的 OES 纹理名重新绑定。 */
    fun attachTexture(textureId: Int) {
        val oldTexture = surfaceTexture
        val newTexture = SurfaceTexture(textureId)
        newTexture.setOnFrameAvailableListener { listener?.onFrameAvailable() }
        surfaceTexture = newTexture
        handler.post {
            oldTexture?.release()
            surface?.release()
            surface = Surface(newTexture)
            if (mediaPlayer != null) {
                releasePlayer()
            }
            createPlayerIfPossible()
        }
    }

    fun setSource(newSource: VideoSource) {
        handler.post {
            if (released) {
                return@post
            }
            source = newSource
            playWhenReady = true
            videoWidth = 0
            videoHeight = 0
            releasePlayer()
            createPlayerIfPossible()
        }
    }

    fun start() {
        handler.post {
            playWhenReady = true
            if (prepared) {
                mediaPlayer?.start()
            }
        }
    }

    fun pause() {
        handler.post {
            playWhenReady = false
            if (prepared) {
                mediaPlayer?.pause()
            }
        }
    }

    fun isPlaying(): Boolean = prepared && (mediaPlayer?.isPlaying == true)

    fun duration(): Long = if (prepared) (mediaPlayer?.duration?.toLong() ?: 0L) else 0L

    fun currentPosition(): Long =
        if (prepared) (mediaPlayer?.currentPosition?.toLong() ?: 0L) else 0L

    fun seekTo(positionMs: Long) {
        handler.post {
            if (prepared) {
                mediaPlayer?.seekTo(positionMs.toInt())
            }
        }
    }

    /** 停止并释放播放器（保留 OES 纹理与 Surface，供下次 [setSource] 复用）。 */
    fun stop() {
        handler.post {
            source = null
            playWhenReady = false
            videoWidth = 0
            videoHeight = 0
            releasePlayer()
        }
    }

    fun release() {
        handler.post {
            released = true
            source = null
            playWhenReady = false
            videoWidth = 0
            videoHeight = 0
            releasePlayer()
            surfaceTexture?.release()
            surfaceTexture = null
            surface?.release()
            surface = null
        }
    }

    private fun createPlayerIfPossible() {
        if (released) {
            return
        }
        val currentSource = source ?: return
        val targetSurface = surface ?: return
        if (mediaPlayer != null) {
            return
        }
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            when (currentSource) {
                is VideoSource.File -> player.setDataSource(context, currentSource.uri)
                is VideoSource.Embedded -> {
                    val dataSource = EmbeddedVideoDataSource(
                        context,
                        currentSource.uri,
                        currentSource.videoLength,
                    )
                    mediaDataSource = dataSource
                    player.setDataSource(dataSource)
                }
            }
            player.setSurface(targetSurface)
            player.setOnPreparedListener { mp ->
                prepared = true
                videoWidth = mp.videoWidth
                videoHeight = mp.videoHeight
                if (playWhenReady) {
                    mp.start()
                }
                listener?.onPrepared(mp.duration.toLong())
            }
            player.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
            }
            player.setOnCompletionListener {
                listener?.onCompletion()
            }
            player.setOnErrorListener { _, what, extra ->
                listener?.onError("MediaPlayer error: what=$what extra=$extra")
                true
            }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            player.release()
            listener?.onError(e.message ?: "Failed to play video")
        }
    }

    private fun releasePlayer() {
        prepared = false
        mediaPlayer?.let { player ->
            try {
                player.stop()
            } catch (ignored: IllegalStateException) {
            }
            try {
                player.reset()
            } catch (ignored: Exception) {
            }
            player.release()
        }
        mediaPlayer = null
        mediaDataSource?.let { dataSource ->
            try {
                dataSource.close()
            } catch (ignored: Exception) {
            }
        }
        mediaDataSource = null
    }
}
