package com.stealth.evidence.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stealth.evidence.R
import java.io.IOException
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
    private lateinit var webView: WebView

    private val httpClient = OkHttpClient()
    private val serverBaseUrl = "http://127.0.0.1:8080"
    private val streamUrl = "$serverBaseUrl/stream"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        previewToggleSwitch = findViewById(R.id.previewToggleSwitch)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        startRecordButton = findViewById(R.id.startRecordButton)
        stopRecordButton = findViewById(R.id.stopRecordButton)
        sendNotificationButton = findViewById(R.id.sendNotificationButton)
        webView = findViewById(R.id.webView)

        // WebViewの設定（ストリーム画像を表示するため）
        webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.setBackgroundColor(0) // 背景を黒/透明に

        // 1. プレビューのON/OFF（サーバーからのライブ監視ストリームを受信）
        previewToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                webView.visibility = View.VISIBLE
                val html = "<html><body style='margin:0;padding:0;background-color:black;display:flex;justify-content:center;align-items:center;'><img src='$streamUrl' width='100%' /></body></html>"
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            } else {
                webView.visibility = View.GONE
                webView.loadUrl("about:blank")
            }
        }

        // 2. 内カメラ / 外カメラ切替（サーバーの /switch を叩く）
        switchCameraButton.setOnClickListener {
            sendServerRequest("$serverBaseUrl/switch", "カメラの向きを切り替えました")
            // 切り替え直後は映像が止まる可能性があるため、プレビュー表示中なら少し待ってリロード
            if (previewToggleSwitch.isChecked) {
                webView.postDelayed({
                    val html = "<html><body style='margin:0;padding:0;background-color:black;display:flex;justify-content:center;align-items:center;'><img src='$streamUrl' width='100%' /></body></html>"
                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }, 1500)
            }
        }

        // 3. 録画（Vaultへの保存）開始（サーバーの /start を叩く）
        startRecordButton.setOnClickListener {
            sendServerRequest("$serverBaseUrl/start", "Vaultへの証拠保存を開始しました")
        }

        // 4. 録画（Vaultへの保存）停止（サーバーの /stop を叩く）
        stopRecordButton.setOnClickListener {
            sendServerRequest("$serverBaseUrl/stop", "Vaultへの証拠保存を停止しました")
        }

        // 5. ダミー通知送信（サーバーの /notify を叩く）
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

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
