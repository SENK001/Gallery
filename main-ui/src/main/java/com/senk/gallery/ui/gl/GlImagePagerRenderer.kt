package com.senk.gallery.ui.gl

import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.senk.gallery.data.entity.MediaItem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

class GlImagePagerRenderer(
    private val videoManager: VideoPlayManager,
) : GLSurfaceView.Renderer {

    val textureStore = MediaTextureStore(MAX_TEXTURE_BYTES)

    @Volatile
    var items: List<MediaItem> = emptyList()

    @Volatile
    var currentIndex = 0

    @Volatile
    var offsetPx = 0f

    @Volatile
    var lightBackground = true

    @Volatile
    var scale = 1f

    @Volatile
    var panX = 0f

    @Volatile
    var panY = 0f

    var onRequestImage: ((MediaItem, Int, Int) -> Unit)? = null

    var onRequestRender: (() -> Unit)? = null

    @Volatile
    var maxTextureSize = 4096
        private set

    @Volatile
    private var motionVideoId = 0L

    @Volatile
    private var motionVideoFrameReady = false

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0
    private var uMatrix = 0
    private var videoProgram = 0
    private var videoAPosition = 0
    private var videoATexCoord = 0
    private var videoUTexture = 0
    private var videoUProjection = 0
    private var videoUMatrix = 0
    private var videoTextureId = 0
    private val videoMatrix = FloatArray(16)
    private val videoRect = RectF()
    private var projection = FloatArray(16)
    private val buffer: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private var placeholderLight = 0
    private var placeholderDark = 0
    private val fitRect = RectF()
    private val iconRect = RectF()
    private val neighborIconRect = RectF()
    private val badgeCache = BadgeCache()
    private var viewportWidth = 0
    private var viewportHeight = 0

    init {
        videoManager.listener = object : VideoPlayManager.Listener {
            override fun onPrepared(durationMs: Long) {
                onRequestRender?.invoke()
            }

            override fun onCompletion() {
                onRequestRender?.invoke()
            }

            override fun onError(message: String) {
                android.util.Log.e("GlImagePager", "motion video error: $message")
                motionVideoId = 0L
                motionVideoFrameReady = false
                onRequestRender?.invoke()
            }

            override fun onFrameAvailable() {
                onRequestRender?.invoke()
            }
        }
    }

    fun startMotionVideo(item: MediaItem) {
        val uri = item.uri ?: return
        if (item.motionVideoLength <= 0L) {
            return
        }
        motionVideoFrameReady = false
        motionVideoId = item.id
        videoManager.setSource(
            VideoPlayManager.VideoSource.Embedded(uri, item.motionVideoLength),
        )
    }

    fun stopMotionVideo() {
        if (motionVideoId == 0L) {
            return
        }
        motionVideoId = 0L
        motionVideoFrameReady = false
        videoManager.stop()
        onRequestRender?.invoke()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = GlUtils.compileProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uMatrix = GLES20.glGetUniformLocation(program, "uMatrix")
        videoProgram = GlUtils.compileProgram(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
        videoAPosition = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        videoATexCoord = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        videoUTexture = GLES20.glGetUniformLocation(videoProgram, "uTexture")
        videoUProjection = GLES20.glGetUniformLocation(videoProgram, "uProjection")
        videoUMatrix = GLES20.glGetUniformLocation(videoProgram, "uMatrix")
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        textureStore.clear()
        badgeCache.evictAll()
        placeholderLight = createPlaceholderTexture(0xFFEEEEEE.toInt())
        placeholderDark = createPlaceholderTexture(0xFF222222.toInt())
        maxTextureSize = GlUtils.maxTextureSize()
        videoTextureId = createExternalTexture()
        videoManager.attachTexture(videoTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        android.opengl.Matrix.orthoM(
            projection,
            0,
            0f,
            width.toFloat(),
            height.toFloat(),
            0f,
            -1f,
            1f,
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        if (lightBackground) {
            GLES20.glClearColor(1f, 1f, 1f, 1f)
        } else {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        textureStore.drainUploads()
        badgeCache.drainDeletes()
        if (motionVideoId != 0L) {
            updateVideoFrame()
        }
        if (program == 0 || viewportWidth <= 0) {
            return
        }
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, projection, 0)
        val snapshot = items
        val index = currentIndex
        val pageWidth = viewportWidth.toFloat()
        if (index - 1 >= 0) {
            drawPage(snapshot[index - 1], -pageWidth + offsetPx, false)
        }
        if (index + 1 < snapshot.size) {
            drawPage(snapshot[index + 1], pageWidth + offsetPx, false)
        }
        if (index in snapshot.indices) {
            drawPage(snapshot[index], offsetPx, true)
        }
    }

    private fun drawPage(item: MediaItem, pageOffsetX: Float, isCurrent: Boolean) {
        val entry = textureStore.textureOf(item.id)
        if (entry == null && !textureStore.isFailed(item.id)) {
            onRequestImage?.invoke(item, viewportWidth, viewportHeight)
        }
        if (isCurrent && !item.isVideo) {
            iconRect.setEmpty()
        }
        val textureId = entry?.textureId
            ?: if (lightBackground) placeholderLight else placeholderDark
        val texWidth = entry?.width ?: 2
        val texHeight = entry?.height ?: 2
        fitRect.set(0f, 0f, texWidth.toFloat(), texHeight.toFloat())
        val fitScale = min(
            viewportWidth.toFloat() / texWidth,
            viewportHeight.toFloat() / texHeight,
        )
        val fitWidth = texWidth * fitScale
        val fitHeight = texHeight * fitScale
        val left = (viewportWidth - fitWidth) / 2f + pageOffsetX
        val top = (viewportHeight - fitHeight) / 2f
        fitRect.set(left, top, left + fitWidth, top + fitHeight)
        if (isCurrent && (scale != 1f || panX != 0f || panY != 0f)) {
            applyTransform(fitRect)
        }
        drawQuad(fitRect, textureId)
        if (item.isVideo) {
            val iconSize = min(viewportWidth, viewportHeight) * 0.22f
            val cx = fitRect.centerX()
            val cy = fitRect.centerY()
            val target = if (isCurrent) iconRect else neighborIconRect
            target.set(
                cx - iconSize / 2f,
                cy - iconSize / 2f,
                cx + iconSize / 2f,
                cy + iconSize / 2f,
            )
            drawQuad(target, badgeCache.playIcon())
        }
        if (isCurrent && item.id == motionVideoId && motionVideoFrameReady) {
            drawMotionVideo(pageOffsetX)
        }
    }

    private fun updateVideoFrame() {
        val texture = videoManager.surfaceTexture ?: return
        texture.updateTexImage()
        texture.getTransformMatrix(videoMatrix)
        if (videoManager.videoWidth > 0 && videoManager.videoHeight > 0) {
            motionVideoFrameReady = true
        }
    }

    private fun drawMotionVideo(pageOffsetX: Float) {
        if (videoProgram == 0 || videoTextureId == 0) {
            return
        }
        val texWidth = videoManager.videoWidth
        val texHeight = videoManager.videoHeight
        if (texWidth <= 0 || texHeight <= 0) {
            return
        }
        val fitScale = min(
            viewportWidth.toFloat() / texWidth,
            viewportHeight.toFloat() / texHeight,
        )
        val fitWidth = texWidth * fitScale
        val fitHeight = texHeight * fitScale
        val left = (viewportWidth - fitWidth) / 2f + pageOffsetX
        val top = (viewportHeight - fitHeight) / 2f
        videoRect.set(left, top, left + fitWidth, top + fitHeight)
        if (scale != 1f || panX != 0f || panY != 0f) {
            applyTransform(videoRect)
        }
        drawVideoQuad(videoRect)
    }

    private fun drawVideoQuad(rect: RectF) {
        buffer.clear()
        buffer.put(rect.left)
        buffer.put(rect.top)
        buffer.put(0f)
        buffer.put(1f)
        buffer.put(rect.right)
        buffer.put(rect.top)
        buffer.put(1f)
        buffer.put(1f)
        buffer.put(rect.left)
        buffer.put(rect.bottom)
        buffer.put(0f)
        buffer.put(0f)
        buffer.put(rect.right)
        buffer.put(rect.bottom)
        buffer.put(1f)
        buffer.put(0f)
        buffer.position(0)
        GLES20.glUseProgram(videoProgram)
        GLES20.glUniformMatrix4fv(videoUProjection, 1, false, projection, 0)
        GLES20.glUniformMatrix4fv(videoUMatrix, 1, false, videoMatrix, 0)
        GLES20.glEnableVertexAttribArray(videoAPosition)
        GLES20.glEnableVertexAttribArray(videoATexCoord)
        buffer.position(0)
        GLES20.glVertexAttribPointer(videoAPosition, 2, GLES20.GL_FLOAT, false, 16, buffer)
        buffer.position(2)
        GLES20.glVertexAttribPointer(videoATexCoord, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(videoUTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(videoAPosition)
        GLES20.glDisableVertexAttribArray(videoATexCoord)
        GLES20.glUseProgram(program)
    }

    fun isPlayIconHit(x: Float, y: Float): Boolean = iconRect.contains(x, y)

    private fun applyTransform(rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val s = scale
        fun transform(x: Float, y: Float): FloatArray {
            val tx = (x - cx) * s + cx + panX
            val ty = (y - cy) * s + cy + panY
            return floatArrayOf(tx, ty)
        }
        val topLeft = transform(rect.left, rect.top)
        val bottomRight = transform(rect.right, rect.bottom)
        rect.set(topLeft[0], topLeft[1], bottomRight[0], bottomRight[1])
        normalize(rect)
    }

    private fun normalize(rect: RectF) {
        if (rect.left > rect.right) {
            val temp = rect.left
            rect.left = rect.right
            rect.right = temp
        }
        if (rect.top > rect.bottom) {
            val temp = rect.top
            rect.top = rect.bottom
            rect.bottom = temp
        }
    }

    private fun drawQuad(rect: RectF, textureId: Int) {
        buffer.clear()
        buffer.put(rect.left)
        buffer.put(rect.top)
        buffer.put(0f)
        buffer.put(0f)
        buffer.put(rect.right)
        buffer.put(rect.top)
        buffer.put(1f)
        buffer.put(0f)
        buffer.put(rect.left)
        buffer.put(rect.bottom)
        buffer.put(0f)
        buffer.put(1f)
        buffer.put(rect.right)
        buffer.put(rect.bottom)
        buffer.put(1f)
        buffer.put(1f)
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, buffer)
        buffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private fun createPlaceholderTexture(color: Int): Int {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val textureId = GlUtils.uploadTexture(bitmap)
        bitmap.recycle()
        return textureId
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return textureId
    }

    private companion object {
        const val MAX_TEXTURE_BYTES = 96 * 1024 * 1024

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMatrix * vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        const val VIDEO_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uProjection;
            uniform mat4 uMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uProjection * vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
