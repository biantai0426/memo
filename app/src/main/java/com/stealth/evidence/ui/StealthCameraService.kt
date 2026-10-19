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
    private var mediaRecorder: MediaRecorder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var webServer: EmbeddedServer? = null

    private var isRecording = false
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
        isFrontCamera = !isFrontCamera
        closeCamera()
        openCamera(isFrontCamera)
    }

    private fun startRecording() {
        if (isRecording) return

        // マイク権限チェック（MediaRecorderには必須）
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission is missing.")
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

            // ★本物のMP4動画としてハードウェアエンコード設定を行う
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(currentVideoFile!!.absolutePath)
                setVideoEncodingBitRate(5000000) // 5Mbps
                setVideoFrameRate(30)
                setVideoSize(640, 480)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                val rotation = if (isFrontCamera) 270 else 90
                setOrientationHint(rotation)

                prepare()
            }

            // 録画用Surfaceを含めてカメラセッションを再構築
            startCaptureSession(includeRecording = true)

        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            // 開始直後に停止した場合などの例外回避
            e.printStackTrace()
            currentVideoFile?.delete()
        }

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
        }

        // 録画完了後、ファイルの先頭ヘッダーを暗号化
        currentVideoFile?.takeIf { it.exists() }?.let { encryptFileHeader(it) }
        Log.d(TAG, "Recording stopped: ${currentVideoFile?.name}")
        currentVideoFile = null

        // ストリーミング専用のセッションに戻す
        if (cameraDevice != null) {
            startCaptureSession(includeRecording = false)
        }
    }

    // ★重要変更：巨大な動画ファイルをメモリに読み込まず、ディスク上で直接先頭だけ暗号化する（OOM対策）
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

                raf.seek(0) // 先頭に戻る
                raf.write(buffer) // 上書き
            }
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

            // ストリーミング用のImageReaderは一度だけ生成する
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

                // 常にストリーミング用のJPEGサーフェスは含める
                imageReader?.surface?.let {
                    requestBuilder.addTarget(it)
                    surfaces.add(it)
                }

                // 録画フラグが立っている場合はMP4エンコーダーのサーフェスも追加
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
                                
                                // セッション構築完了後に録画をスタートさせる
                                if (includeRecording) {
                                    mediaRecorder?.start()
                                    isRecording = true
                                    Log.d(TAG, "MediaRecorder successfully started.")
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure capture session")
                        }
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
