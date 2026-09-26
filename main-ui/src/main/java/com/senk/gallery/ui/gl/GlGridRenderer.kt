package com.senk.gallery.ui.gl

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.util.DateFormats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GlGridRenderer(private val density: Float = 1.5f) : GLSurfaceView.Renderer {

    val geometry = GridGeometry()
    val textureStore = MediaTextureStore()

    @Volatile
    var items: List<MediaItem> = emptyList()

    @Volatile
    var scrollY = 0f

    var emptyText: String = ""

    var onRequestThumbnail: ((MediaItem, Int) -> Unit)? = null
    var onNearEnd: (() -> Unit)? = null

    private val badgeCache = BadgeCache(density)
    private val projection = FloatArray(16)
    private val vertices = FloatArray(16)
    private var positionBuffer: FloatBuffer? = null
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0
    private var uMatrix = 0
    private var nearEndNotified = false
    private val cellRect = RectF()
    private val badgeRect = RectF()
    private var placeholderTexture = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = GlUtils.compileProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uMatrix = GLES20.glGetUniformLocation(program, "uMatrix")
        positionBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        textureStore.clear()
        badgeCache.evictAll()
        placeholderTexture = createPlaceholderTexture()
        nearEndNotified = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        geometry.viewportWidth = width
        geometry.viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        Matrix.orthoM(
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
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        textureStore.drainUploads()
        badgeCache.drainDeletes()
        val snapshot = items
        geometry.itemCount = snapshot.size
        if (program == 0) {
            return
        }
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, projection, 0)
        if (snapshot.isEmpty()) {
            drawEmptyState()
            return
        }
        if (geometry.cellSize <= 0f) {
            return
        }
        val scrolled = scrollY
        val size = geometry.cellSize
        val firstRow = ((scrolled - geometry.padding) / (size + geometry.gap)).toInt()
            .coerceAtLeast(0)
        val lastRow = ((scrolled + geometry.viewportHeight) / (size + geometry.gap)).toInt()
            .coerceAtMost(geometry.rowCount - 1)
        for (row in firstRow..lastRow) {
            for (column in 0 until geometry.columns) {
                val index = row * geometry.columns + column
                if (index >= snapshot.size) {
                    break
                }
                val item = snapshot[index]
                geometry.cellRect(index, scrolled, cellRect)
                drawCell(item, cellRect)
            }
        }
        if (!nearEndNotified && geometry.itemCount > 0 &&
            scrolled + geometry.viewportHeight >= geometry.contentHeight -
            geometry.viewportHeight * 0.5f
        ) {
            nearEndNotified = true
            onNearEnd?.invoke()
        }
    }

    fun resetNearEnd() {
        nearEndNotified = false
    }

    private fun drawEmptyState() {
        if (emptyText.isEmpty() || geometry.viewportWidth <= 0) {
            return
        }
        val texture = badgeCache.emptyText(emptyText)
        val left = (geometry.viewportWidth - texture.width) / 2f
        val top = (geometry.viewportHeight - texture.height) / 2f
        cellRect.set(left, top, left + texture.width, top + texture.height)
        drawQuad(cellRect, 0f, 0f, 1f, 1f, texture.textureId)
    }

    private fun drawCell(item: MediaItem, rect: RectF) {
        val key = item.id
        val entry = textureStore.textureOf(key)
        if (entry == null && !textureStore.isFailed(key)) {
            onRequestThumbnail?.invoke(item, geometry.cellSize.toInt())
        }
        if (entry == null) {
            drawQuad(rect, 0f, 0f, 1f, 1f, placeholderTexture)
        } else {
            val aspect = entry.width.toFloat() / entry.height.toFloat()
            var u0 = 0f
            var v0 = 0f
            var u1 = 1f
            var v1 = 1f
            if (aspect >= 1f) {
                val visible = 1f / aspect
                u0 = (1f - visible) / 2f
                u1 = 1f - u0
            } else {
                val visible = aspect
                v0 = (1f - visible) / 2f
                v1 = 1f - v0
            }
            drawQuad(rect, u0, v0, u1, v1, entry.textureId)
        }
        val size = rect.width()
        val isVideo = item.isVideo && item.duration > 0L
        if (isVideo || item.isMotionPhoto) {
            badgeRect.set(rect.left, rect.bottom - size * 0.5f, rect.right, rect.bottom)
            drawQuad(badgeRect, 0f, 0f, 1f, 1f, badgeCache.bottomGradient())
        }
        if (isVideo) {
            val info = badgeCache.videoInfo(DateFormats.formatDuration(item.duration))
            val margin = size * 0.06f
            var infoWidth = info.width.toFloat()
            var infoHeight = info.height.toFloat()
            val maxWidth = size * 0.6f
            if (infoWidth > maxWidth) {
                val scale = maxWidth / infoWidth
                infoWidth *= scale
                infoHeight *= scale
            }
            badgeRect.set(
                rect.left + margin,
                rect.bottom - margin - infoHeight,
                rect.left + margin + infoWidth,
                rect.bottom - margin,
            )
            drawQuad(badgeRect, 0f, 0f, 1f, 1f, info.textureId)
        }
        if (item.isMotionPhoto) {
            val motion = badgeCache.motionBadge()
            val motionMargin = size * 0.06f
            badgeRect.set(
                rect.left + motionMargin,
                rect.bottom - motionMargin - motion.height,
                rect.left + motionMargin + motion.width,
                rect.bottom - motionMargin,
            )
            drawQuad(badgeRect, 0f, 0f, 1f, 1f, motion.textureId)
        }
    }

    private fun createPlaceholderTexture(): Int {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(238, 238, 238))
        val textureId = GlUtils.uploadTexture(bitmap)
        bitmap.recycle()
        return textureId
    }

    private fun drawQuad(rect: RectF, u0: Float, v0: Float, u1: Float, v1: Float, textureId: Int) {
        val buffer = positionBuffer ?: return
        buffer.clear()
        buffer.put(rect.left)
        buffer.put(rect.top)
        buffer.put(u0)
        buffer.put(v0)
        buffer.put(rect.right)
        buffer.put(rect.top)
        buffer.put(u1)
        buffer.put(v0)
        buffer.put(rect.left)
        buffer.put(rect.bottom)
        buffer.put(u0)
        buffer.put(v1)
        buffer.put(rect.right)
        buffer.put(rect.bottom)
        buffer.put(u1)
        buffer.put(v1)
        buffer.position(0)
        val stride = 4 * 4
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private companion object {

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
    }
}
