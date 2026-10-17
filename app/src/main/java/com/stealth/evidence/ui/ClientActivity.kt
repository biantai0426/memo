package com.stealth.evidence.ui

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
import androidx.core.content.ContextCompat
import com.stealth.evidence.R
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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
    
    private val httpClient = OkHttpClient()
    private val serverBaseUrl = "http://127.0.0.1:8080"

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

        // 1. プレビューのON/OFFスイッチ（ローカル描画）
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
            Toast.makeText(this, "カメラ向きを切り替えました", Toast.LENGTH_SHORT).show()
        }

        // 3. 録画開始（サーバーの /start エンドポイントを叩く）
        startRecordButton.setOnClickListener {
            val facingStr = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"
            sendServerRequest("$serverBaseUrl/start?facing=$facingStr", "遠隔録画を開始しました")
        }

        // 4. 録画停止（サーバーの /stop エンドポイントを叩く）
        stopRecordButton.setOnClickListener {
            sendServerRequest("$serverBaseUrl/stop", "遠隔録画を停止しました")
        }

        // 5. ダミー通知送信（サーバーの /notify エンドポイントを叩く）
        sendNotificationButton.setOnClickListener {
            sendServerRequest("$serverBaseUrl/notify", "カモフラージュ通知を送信しました")
        }
    }

    private fun sendServerRequest(url: String, successMessage: String) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@ClientActivity, "サーバー通信失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    Toast.makeText(this@ClientActivity, successMessage, Toast.LENGTH_SHORT).show()
                }
            }
        })
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
