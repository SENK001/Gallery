package com.senk.gallery.ui.gl

import android.content.Context
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

class GlVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    interface Listener {
        fun onPrepared(durationMs: Long)
        fun onCompletion()
        fun onError(message: String)
    }

    private val renderer = VideoRenderer()
    private val playManager = VideoPlayManager(context)

    var listener: Listener? = null

    init {
        setEGLContextClientVersion(2)
        setZOrderOnTop(false)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        playManager.listener = object : VideoPlayManager.Listener {
            override fun onPrepared(durationMs: Long) {
                listener?.onPrepared(durationMs)
                requestRender()
            }

            override fun onCompletion() {
                listener?.onCompletion()
            }

            override fun onError(message: String) {
                listener?.onError(message)
            }

            override fun onFrameAvailable() {
                requestRender()
            }
        }
    }

    fun setVideoUri(uri: Uri) {
        playManager.setSource(VideoPlayManager.VideoSource.File(uri))
    }

    fun start() {
        playManager.start()
    }

    fun pause() {
        playManager.pause()
    }

    fun isPlaying(): Boolean = playManager.isPlaying()

    fun duration(): Long = playManager.duration()

    fun currentPosition(): Long = playManager.currentPosition()

    fun seekTo(positionMs: Long) {
        playManager.seekTo(positionMs)
    }

    fun release() {
        playManager.release()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private inner class VideoRenderer : GLSurfaceView.Renderer {

        private var program = 0
        private var aPosition = 0
        private var aTexCoord = 0
        private var uTexture = 0
        private var uMatrix = 0
        private var textureId = 0
        private val stMatrix = FloatArray(16)
        private val buffer: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = GlUtils.compileProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
            uMatrix = GLES20.glGetUniformLocation(program, "uMatrix")
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
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
            playManager.attachTexture(textureId)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val texture = playManager.surfaceTexture ?: return
            texture.updateTexImage()
            texture.getTransformMatrix(stMatrix)
            val width = playManager.videoWidth
            val height = playManager.videoHeight
            if (width <= 0 || height <= 0 || program == 0) {
                return
            }
            val viewWidth = this@GlVideoView.width.toFloat()
            val viewHeight = this@GlVideoView.height.toFloat()
            if (viewWidth <= 0f || viewHeight <= 0f) {
                return
            }
            val fit = min(viewWidth / width, viewHeight / height)
            val halfW = width * fit / viewWidth
            val halfH = height * fit / viewHeight
            buffer.clear()
            buffer.put(-halfW)
            buffer.put(-halfH)
            buffer.put(0f)
            buffer.put(0f)
            buffer.put(halfW)
            buffer.put(-halfH)
            buffer.put(1f)
            buffer.put(0f)
            buffer.put(-halfW)
            buffer.put(halfH)
            buffer.put(0f)
            buffer.put(1f)
            buffer.put(halfW)
            buffer.put(halfH)
            buffer.put(1f)
            buffer.put(1f)
            buffer.position(0)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMatrix, 1, false, stMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            buffer.position(0)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, buffer)
            buffer.position(2)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
        }
    }

    private companion object {

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_SHADER = """
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
