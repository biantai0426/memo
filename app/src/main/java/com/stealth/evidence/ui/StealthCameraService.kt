package com.stealth.evidence.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StealthCameraService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioTrack: AudioTrack? = null
    private var isPlayingSilence = false

    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    
    private lateinit var vaultManager: VaultManager

    override fun onCreate() {
        super.onCreate()
        vaultManager = VaultManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startForegroundServiceNotification()
        acquireWakeLock()
        startSilentAudioLoop()
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // サービスがOSにKillされても自動で再起動を試みる
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
        // カモフラージュ用のシステム通知
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("システム同期")
            .setContentText("バックグラウンドでデータを同期しています")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(1, notification)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // デフォルトは背面カメラ
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, videoCapture)
                startRecording()
            } catch (exc: Exception) {
                Log.e("StealthCameraService", "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return
        val tempFile = vaultManager.createTempVideoFile()
        
        val outputOptions = FileOutputOptions.Builder(tempFile).build()
        recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.d("StealthCameraService", "Recording started")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            // 録画終了時にXOR暗号化と偽装保存を実行
                            vaultManager.finalizeVideoFile(tempFile)
                            Log.d("StealthCameraService", "Recording finalized and encrypted")
                        } else {
                            tempFile.delete()
                            Log.e("StealthCameraService", "Recording error: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        recording = null
        cameraExecutor.shutdown()
        
        isPlayingSilence = false
        audioTrack?.stop()
        audioTrack?.release()
        
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
