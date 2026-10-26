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
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StealthCameraService : Service() {

    enum class RecordState {
        IDLE,
        STARTING,
        RECORDING,
        STOPPING
    }

    companion object {
        const val ACTION_START_STANDBY = "ACTION_START_STANDBY"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val ACTION_SWITCH_CAMERA = "ACTION_SWITCH_CAMERA"
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "stealth_camera_channel"
        private const val TAG = "StealthService"

        @Volatile
        var latestJpeg: ByteArray? = null
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var webServer: EmbeddedServer? = null

    @Volatile
    private var recordState = RecordState.IDLE
    private var currentVideoFile: File? = null

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
        if (recordState != RecordState.IDLE) return
        isFrontCamera = !isFrontCamera
        closeCamera()
        openCamera(isFrontCamera)
    }

    @Synchronized
    private fun startRecording() {
        if (recordState != RecordState.IDLE) {
            Log.w(TAG, "startRecording ignored: state is $recordState")
            return
        }
        recordState = RecordState.STARTING

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission missing")
            recordState = RecordState.IDLE
            return
        }

        try {
            val dir = File(filesDir, "vault")
            if (!dir.exists()) dir.mkdirs()

            val sdf = SimpleDateFormat("HHmmss", Locale.getDefault())
            val timeString = sdf.format(Date())
            val fileName = "$timeString.dat"
            currentVideoFile = File(dir, fileName)

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(currentVideoFile!!.absolutePath)
                setVideoEncodingBitRate(5000000)
                setVideoFrameRate(30)
                setVideoSize(640, 480)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                val rotation = if (isFrontCamera) 270 else 90
                setOrientationHint(rotation)

                prepare()
            }

            startCaptureSession(includeRecording = true)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to startRecording", e)
            mediaRecorder?.release()
            mediaRecorder = null
            currentVideoFile = null
            recordState = RecordState.IDLE
        }
    }

    @Synchronized
    private fun stopRecording() {
        if (recordState != RecordState.RECORDING) {
            Log.w(TAG, "stopRecording ignored: state is $recordState")
            return
        }
        recordState = RecordState.STOPPING

        backgroundHandler?.post {
            try {
                // 1. カメラからの新規フレーム流入を遮断
                try {
                    captureSession?.stopRepeating()
                } catch (e: Exception) {
                    Log.w(TAG, "stopRepeating failed", e)
                }

                // 2. バッファ書き出しの猶予
                Thread.sleep(100)

                // 3. MediaRecorderを正常停止（moovアトムの書き込み完了）
                try {
                    mediaRecorder?.stop()
                } catch (e: RuntimeException) {
                    Log.e(TAG, "mediaRecorder.stop failed", e)
                    currentVideoFile?.delete()
                }

                try {
                    mediaRecorder?.reset()
                    mediaRecorder?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "mediaRecorder release error", e)
                } finally {
                    mediaRecorder = null
                }

                // 4. ファイルクローズ後に先頭256バイトを暗号化
                val savedFile = currentVideoFile
                if (savedFile != null && savedFile.exists() && savedFile.length() > 0L) {
                    encryptFileHeader(savedFile)
                    Log.d(TAG, "Recording saved and encrypted: ${savedFile.name} (${savedFile.length()} bytes)")
                }
                currentVideoFile = null

                // 5. プレビュー単独セッションに復帰
                if (cameraDevice != null) {
                    startCaptureSession(includeRecording = false)
                } else {
                    recordState = RecordState.IDLE
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in stopRecording sequence", e)
                recordState = RecordState.IDLE
            }
        }
    }

    private fun encryptFileHeader(file: File) {
        if (!file.exists() || file.length() == 0L) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val headerSize = 256.coerceAtMost(raf.length().toInt())
                val buffer = ByteArray(headerSize)
                raf.readFully(buffer)

                val xorKey = 0xAA.toByte()
                for (i in 0 until headerSize) {
                    buffer[i] = (buffer[i].toInt() xor xorKey.toInt()).toByte()
                }

                raf.seek(0)
                raf.write(buffer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "encryptFileHeader failed", e)
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

            if (imageReader != null) {
                imageReader?.close()
            }
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        latestJpeg = bytes
                        image.close()
                    }
                }, backgroundHandler)
            }

            manager.openCamera(finalCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession(includeRecording = false)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startCaptureSession(includeRecording: Boolean) {
        cameraDevice?.let { camera ->
            captureSession?.close()
            captureSession = null

            try {
                val template = if (includeRecording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                val requestBuilder = camera.createCaptureRequest(template)

                val rotation = if (isFrontCamera) 270 else 90
                requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotation)

                val surfaces = mutableListOf<android.view.Surface>()

                imageReader?.surface?.let {
                    requestBuilder.addTarget(it)
                    surfaces.add(it)
                }

                if (includeRecording && mediaRecorder != null) {
                    val recordSurface = mediaRecorder!!.surface
                    requestBuilder.addTarget(recordSurface)
                    surfaces.add(recordSurface)
                }

                camera.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)

                                if (includeRecording) {
                                    mediaRecorder?.start()
                                    recordState = RecordState.RECORDING
                                    Log.d(TAG, "Recording started successfully")
                                } else {
                                    recordState = RecordState.IDLE
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed in onConfigured", e)
                                recordState = RecordState.IDLE
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Configure failed")
                            recordState = RecordState.IDLE
                        }
                    }, backgroundHandler
                )
            } catch (e: CameraAccessException) {
                Log.e(TAG, "startCaptureSession error", e)
                recordState = RecordState.IDLE
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
