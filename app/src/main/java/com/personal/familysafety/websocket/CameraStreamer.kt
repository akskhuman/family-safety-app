package com.personal.familysafety.websocket

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import java.util.concurrent.atomic.AtomicBoolean

class CameraStreamer(
    private val context: Context,
    private val isFrontCamera: Boolean = true,
    private val targetWidth: Int = 640,
    private val targetHeight: Int = 480,
    private val targetFps: Int = 20
) {
    companion object {
        private const val TAG = "CameraStreamer"
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val isStreaming = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private val frameIntervalMs = 1000L / targetFps

    @SuppressLint("MissingPermission")
    fun start() {
        if (isStreaming.getAndSet(true)) return

        val thread = HandlerThread("CameraStreamerThread").apply { start() }
        backgroundThread = thread
        val bgHandler = Handler(thread.looper)
        backgroundHandler = bgHandler

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = if (isFrontCamera) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        var selectedCameraId: String? = null
        var selectedSize = Size(targetWidth, targetHeight)

        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    selectedCameraId = id
                    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
                    if (!jpegSizes.isNullOrEmpty()) {
                        selectedSize = jpegSizes.minByOrNull {
                            val diffW = Math.abs(it.width - targetWidth)
                            val diffH = Math.abs(it.height - targetHeight)
                            diffW + diffH
                        } ?: jpegSizes[0]
                    }
                    break
                }
            }

            val cameraId = selectedCameraId ?: cameraManager.cameraIdList.firstOrNull() ?: run {
                isStreaming.set(false)
                return
            }

            val reader = ImageReader.newInstance(selectedSize.width, selectedSize.height, ImageFormat.JPEG, 2)
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
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    WebSocketStreamManager.getInstance().sendBinaryFrame(bytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring camera frame: ${e.message}")
                } finally {
                    image.close()
                }
            }, bgHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, reader, bgHandler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    isStreaming.set(false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    isStreaming.set(false)
                }
            }, bgHandler)

        } catch (e: Exception) {
            isStreaming.set(false)
        }
    }

    private fun createCaptureSession(camera: CameraDevice, reader: ImageReader, handler: Handler) {
        try {
            val surface = reader.surface
            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isStreaming.get()) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start camera preview request: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera configuration failed!")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera capture session: ${e.message}", e)
        }
    }

    fun stop() {
        if (!isStreaming.getAndSet(false)) return

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null

            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping CameraStreamer: ${e.message}")
        }
    }
}