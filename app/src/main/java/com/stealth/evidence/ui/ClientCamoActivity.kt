package com.stealth.evidence.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stealth.evidence.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

class ClientCamoActivity : AppCompatActivity() {

    private lateinit var tvTargetIpCamo: TextView
    private lateinit var jacketContainer: FrameLayout
    private lateinit var imgJacket: TextView
    private lateinit var webViewCamo: WebView
    private lateinit var progressBarCamo: ProgressBar
    private lateinit var btnPrevCamo: TextView
    private lateinit var btnPlayPauseCamo: TextView
    private lateinit var btnNextCamo: TextView
    private lateinit var lvVaultCamo: ListView

    private val httpClient = OkHttpClient()
    private var targetUrl = "http://127.0.0.1:8080"

    private var isRecording = false
    private var isPreviewVisible = false

    // シークバーを動かすためのタイマー（録画中＝再生中）
    private val handler = Handler(Looper.getMainLooper())
    private var progressValue = 0
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                progressValue = (progressValue + 1) % 100
                progressBarCamo.progress = progressValue
                handler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client_camo)

        tvTargetIpCamo = findViewById(R.id.tvTargetIpCamo)
        jacketContainer = findViewById(R.id.jacketContainer)
        imgJacket = findViewById(R.id.imgJacket)
        webViewCamo = findViewById(R.id.webViewCamo)
        progressBarCamo = findViewById(R.id.progressBarCamo)
        btnPrevCamo = findViewById(R.id.btnPrevCamo)
        btnPlayPauseCamo = findViewById(R.id.btnPlayPauseCamo)
        btnNextCamo = findViewById(R.id.btnNextCamo)
        lvVaultCamo = findViewById(R.id.lvVaultCamo)

        webViewCamo.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webViewCamo.setBackgroundColor(0)

        // ジャケット領域タップ：プレビューのON/OFF
        jacketContainer.setOnClickListener {
            isPreviewVisible = !isPreviewVisible
            if (isPreviewVisible) {
                imgJacket.visibility = View.GONE
                webViewCamo.visibility = View.VISIBLE
                reloadStream()
            } else {
                imgJacket.visibility = View.VISIBLE
                webViewCamo.visibility = View.GONE
                webViewCamo.loadUrl("about:blank")
            }
        }

        // 戻るボタン(⏮)：内カメ/外カメの切り替え
        btnPrevCamo.setOnClickListener {
            sendServerRequest("$targetUrl/switch")
            if (isPreviewVisible) {
                webViewCamo.postDelayed({ reloadStream() }, 1500)
            }
        }

        // 再生ボタン(▶)：録画の開始/停止
        btnPlayPauseCamo.setOnClickListener {
            isRecording = !isRecording
            if (isRecording) {
                btnPlayPauseCamo.text = "⏸"
                sendServerRequest("$targetUrl/start")
                handler.post(progressRunnable) // ダミーでシークバーを進める
            } else {
                btnPlayPauseCamo.text = "▶"
                sendServerRequest("$targetUrl/stop")
                handler.removeCallbacks(progressRunnable)
            }
        }

        // 次へボタン(⏭)：カモフラージュ通知送信（画面点灯）
        btnNextCamo.setOnClickListener {
            sendServerRequest("$targetUrl/notify")
        }

        // 起動時に自動で現場端末のIPをスキャン
        autoScanServer()
    }

    private fun reloadStream() {
        val streamUrl = "$targetUrl/stream"
        val html = "<html><body style='margin:0;padding:0;background-color:black;display:flex;justify-content:center;align-items:center;'><img src='$streamUrl' width='100%' /></body></html>"
        webViewCamo.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun autoScanServer() {
        val possibleIps = mutableListOf("192.168.43.1", "192.168.49.1", "127.0.0.1")
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val ip = dhcp.gateway
                val gatewayIp = String.format("%d.%d.%d.%d",
                    (ip and 0xff), (ip shr 8 and 0xff), (ip shr 16 and 0xff), (ip shr 24 and 0xff))
                if (!possibleIps.contains(gatewayIp)) {
                    possibleIps.add(0, gatewayIp)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Thread {
            var found = false
            val testClient = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build()

            for (ip in possibleIps) {
                try {
                    val req = Request.Builder().url("http://$ip:8080/").build()
                    val response = testClient.newCall(req).execute()
                    if (response.isSuccessful) {
                        found = true
                        runOnUiThread {
                            targetUrl = "http://$ip:8080"
                            tvTargetIpCamo.text = "♪" // 接続成功時は小さく表示
                            fetchVideoList() // ボトムシート用のリストを取得
                            if (isPreviewVisible) reloadStream()
                        }
                        break
                    }
                } catch (e: Exception) {}
            }
            if (!found) {
                runOnUiThread { tvTargetIpCamo.text = "♪ 接続待機" }
            }
        }.start()
    }

    // 偽装画面のため、エラーのToastなどは出さない
    private fun sendServerRequest(url: String) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    // ボトムシート用の動画リスト取得
    private fun fetchVideoList() {
        val request = Request.Builder().url("$targetUrl/videos").build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val jsonStr = response.body?.string() ?: ""
                runOnUiThread {
                    try {
                        val jsonArray = JSONArray(jsonStr)
                        if (jsonArray.length() == 0) return@runOnUiThread
                        
                        val fileNames = mutableListOf<String>()
                        val displayItems = mutableListOf<String>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val name = obj.getString("name")
                            val date = obj.getString("date")
                            val sizeMb = obj.getLong("size") / (1024 * 1024)
                            fileNames.add(name)
                            // 音楽の曲名っぽく偽装して表示
                            displayItems.add("Track ${i+1}: $date (${sizeMb}MB)")
                        }
                        
                        // ダークテーマ用のシンプルなリストレイアウトを利用（今回は標準を利用）
                        val adapter = ArrayAdapter(this@ClientCamoActivity, android.R.layout.simple_list_item_1, displayItems)
                        lvVaultCamo.adapter = adapter
                        
                        // タップでダウンロード
                        lvVaultCamo.setOnItemClickListener { _, _, position, _ ->
                            val downloadUrl = "$targetUrl/download?name=${fileNames[position]}"
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                            Toast.makeText(this@ClientCamoActivity, "DL...", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {}
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        webViewCamo.destroy()
        handler.removeCallbacks(progressRunnable)
    }
}
