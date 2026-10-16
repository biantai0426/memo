package com.stealth.evidence.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stealth.evidence.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ClientActivity : AppCompatActivity() {

    private lateinit var previewToggleSwitch: Switch
    private lateinit var switchCameraButton: Button
    private lateinit var startRecordButton: Button
    private lateinit var stopRecordButton: Button
    private lateinit var sendNotificationButton: Button
    private lateinit var viewFinder: PreviewView

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        previewToggleSwitch = findViewById(R.id.previewToggleSwitch)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        startRecordButton = findViewById(R.id.startRecordButton)
        stopRecordButton = findViewById(R.id.stopRecordButton)
        sendNotificationButton = findViewById(R.id.sendNotificationButton)
        viewFinder = findViewById(R.id.viewFinder)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. プレビューのON/OFFスイッチ
        previewToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewFinder.visibility = View.VISIBLE
                startCameraPreview()
            } else {
                viewFinder.visibility = View.GONE
                unbindPreview()
            }
        }

        // 2. 内カメラ / 外カメラ切替
        switchCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            if (previewToggleSwitch.isChecked) {
                startCameraPreview()
            }
            Toast.makeText(this, "カメラを切り替えました", Toast.LENGTH_SHORT).show()
        }

        // 3. 録画開始
        startRecordButton.setOnClickListener {
            val intent = android.content.Intent(this, StealthCameraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "遠隔：録画を開始しました", Toast.LENGTH_SHORT).show()
        }

        // 4. 録画停止
        stopRecordButton.setOnClickListener {
            val intent = android.content.Intent(this, StealthCameraService::class.java)
            stopService(intent)
            Toast.makeText(this, "遠隔：録画を停止しました", Toast.LENGTH_SHORT).show()
        }

        // 5. ダミー（カモフラージュ）通知送信
        sendNotificationButton.setOnClickListener {
            sendCamouflageNotification()
            Toast.makeText(this, "カモフラージュ通知を送信しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview
                )
            } catch (exc: Exception) {
                // プレビューバインド失敗時の処理
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindPreview() {
        cameraProvider?.unbindAll()
    }

    private fun sendCamouflageNotification() {
        val channelId = "camouflage_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Background Sync",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("システム同期")
            .setContentText("同期を開始しました (バックグラウンド処理中)")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setAutoCancel(true)
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            notificationManager.notify(999, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
