package com.personal.familysafety.websocket

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenStreamer(
    private val mediaProjection: MediaProjection,
    private val width: Int = 720,
    private val height: Int = 1280,
    private val densityDpi: Int = 320,
    private val targetFps: Int = 18,
    private val jpegQuality: Int = 65
) {
    companion object {
        private const val TAG = "ScreenStreamer"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val isStreaming = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private val frameIntervalMs = 1000L / targetFps

    private var reusableBitmap: Bitmap? = null
    private val outputStream = ByteArrayOutputStream(width * height / 2)

    fun start() {
        if (isStreaming.getAndSet(true)) return

        val thread = HandlerThread("ScreenStreamerThread").apply { start() }
        handlerThread = thread
        val bgHandler = Handler(thread.looper)
        handler = bgHandler

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            if (!isStreaming.get()) {
                ir.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameTime < frameIntervalMs) {
                ir.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastFrameTime = now

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmapWidth = width + rowPadding / pixelStride
                var bitmap = reusableBitmap
                if (bitmap == null || bitmap.width != bitmapWidth || bitmap.height != height) {
                    bitmap?.recycle()
                    bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                    reusableBitmap = bitmap
                }

                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)

                val finalBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height)
                } else {
                    bitmap
                }

                outputStream.reset()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
                val jpegBytes = outputStream.toByteArray()

                if (rowPadding > 0 && finalBitmap != bitmap) {
                    finalBitmap.recycle()
                }

                WebSocketStreamManager.getInstance().sendBinaryFrame(jpegBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen frame: ${e.message}")
            } finally {
                image.close()
            }
        }, bgHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenStreamerDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            bgHandler
        )
    }

    fun stop() {
        if (!isStreaming.getAndSet(false)) return

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            handlerThread?.quitSafely()
            handlerThread = null
            handler = null

            reusableBitmap?.recycle()
            reusableBitmap = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ScreenStreamer: ${e.message}")
        }
    }
}