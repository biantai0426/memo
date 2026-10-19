package com.stealth.evidence.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StealthCameraService : Service() {

    companion object {
        const val ACTION_START_STANDBY = "ACTION_START_STANDBY"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val ACTION_SWITCH_CAMERA = "ACTION_SWITCH_CAMERA"
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "stealth_camera_channel"
        private const val TAG = "StealthService"

        // EmbeddedServerのストリーミング配信用に最新のJPEGフレームを保持
        @Volatile
        var latestJpeg: ByteArray? = null
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // ★修正：Ktorベースの新しいサーバーを保持する変数を追加
    private var webServer: EmbeddedServer? = null

    private var isRecording = false
    private var currentVideoFile: File? = null
    private var videoOutputStream: FileOutputStream? = null

    private var isFrontCamera = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STANDBY -> {
                val notification = createNotification("システム 待機中...")
                startForeground(NOTIFICATION_ID, notification)
                openCamera(isFrontCamera)
                
                // ★修正：カメラ起動と同時にサーバーを起動する処理を復活
                if (webServer == null) {
                    webServer = EmbeddedServer(applicationContext)
                    webServer?.start()
                }
            }
            ACTION_START_RECORDING -> {
                startRecording()
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
            }
            ACTION_SWITCH_CAMERA -> {
                switchCamera()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // ★修正：サービス終了時にサーバーも安全にシャットダウンする
        webServer?.stop()
        
        closeCamera()
        stopBackgroundThread()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "システムサービス",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "バックグラウンドで動作するために必要な通知です。"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("システムサービス")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun switchCamera() {
        isFrontCamera = !isFrontCamera
        closeCamera()
        openCamera(isFrontCamera)
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true

        try {
            val dir = File(filesDir, "vault")
            if (!dir.exists()) dir.mkdirs()

            val sdf = SimpleDateFormat("HHmmss", Locale.getDefault())
            val timeString = sdf.format(Date())
            val fileName = "$timeString.dat"

            currentVideoFile = File(dir, fileName)
            videoOutputStream = FileOutputStream(currentVideoFile)

            Log.d(TAG, "Recording started: $fileName")
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            videoOutputStream?.flush()
            videoOutputStream?.close()

            currentVideoFile?.let { encryptFileHeader(it) }

            Log.d(TAG, "Recording stopped: ${currentVideoFile?.name}")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            videoOutputStream = null
            currentVideoFile = null
        }
    }

    private fun encryptFileHeader(file: File) {
        if (!file.exists()) return
        try {
            val data = file.readBytes()
            val headerSize = 256.coerceAtMost(data.size)
            val xorKey = 0xAA.toByte()
            for (i in 0 until headerSize) {
                data[i] = (data[i].toInt() xor xorKey.toInt()).toByte()
            }
            file.writeBytes(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(useFront: Boolean = false) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var targetCameraId: String? = null
            for (cameraId in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val targetFacing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                if (facing == targetFacing) {
                    targetCameraId = cameraId
                    break
                }
            }

            val finalCameraId = targetCameraId ?: manager.cameraIdList[0]

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            manager.openCamera(finalCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startCaptureSession() {
        cameraDevice?.let { camera ->
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        // EmbeddedServer から取得できるようにCompanion objectの変数に格納
                        latestJpeg = bytes

                        if (isRecording && videoOutputStream != null) {
                            try {
                                videoOutputStream?.write(bytes)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        image.close()
                    }
                }, backgroundHandler)
            }

            try {
                val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                requestBuilder.addTarget(imageReader!!.surface)

                val rotation = if (isFrontCamera) 270 else 90
                requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotation)

                camera.createCaptureSession(
                    listOf(imageReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                            } catch (e: CameraAccessException) { e.printStackTrace() }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, null
                )
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }
}
