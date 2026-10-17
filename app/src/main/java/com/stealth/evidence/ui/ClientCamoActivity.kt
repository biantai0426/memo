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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private lateinit var tvSongTitle: TextView // 隠しトリガー用

    private val httpClient = OkHttpClient()
    private var targetUrl = "http://192.168.43.1:8080" // デフォルト

    private var isRecording = false
    private var isPreviewVisible = false

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

        // 曲名ラベル（「なんもねえ」の部分）を取得
        tvSongTitle = findViewById(R.id.tvSongTitle) ?: run {
            // 万が一レイアウト側にIDがない場合の安全策としてレイアウト側を更新するコードを追加しなくてもいいように
            // ここではダミーとして直接見つからない場合の処理をしておきます
            findViewById<TextView>(android.R.id.text1)
        }

        webViewCamo.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webViewCamo.setBackgroundColor(0)

        // 🎵 【隠し機能】曲名（「なんもねえ」）の文字を長押しすると、IP手動設定ダイアログが起動します！
        val titleView = findViewById<View>(android.R.id.content).findViewById<TextView>(R.id.tvTargetIpCamo).parent as? View
        // 代わりに画面上の曲名テキストビューを安全に見つけるため、直接リスナーを仕込みます
        // ※レイアウト内の「なんもねえ」と書かれたTextViewを長押しできるようにします
        setupLongClickForIpConfig()

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
                handler.post(progressRunnable)
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

        autoScanServer()
    }

    // 曲名部分を長押ししたときにIP設定ダイアログを開く隠しギミック
    private fun setupLongClickForIpConfig() {
        // 画面内のすべてのTextViewを走査し、「なんもねえ」というテキストを持つものを探して長押しを有効にする
        val rootView = findViewById<View>(android.R.id.content)
        findAndSetLongClick(rootView)
    }

    private fun findAndSetLongClick(view: View) {
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findAndSetLongClick(view.getChildAt(i))
            }
        } else if (view is TextView && view.text.toString() == "なんもねえ") {
            view.setOnLongClickListener {
                showIpConfigDialog()
                true
            }
        }
    }

    // IP手動設定ダイアログ
    private fun showIpConfigDialog() {
        val input = EditText(this).apply {
            hint = "例: 192.168.43.1"
            val currentIp = targetUrl.replace("http://", "").replace(":8080", "")
            setText(currentIp)
        }

        AlertDialog.Builder(this)
            .setTitle("IPアドレスの手動設定")
            .setMessage("現場端末の画面に表示されているIPアドレスを入力してください。")
            .setView(input)
            .setPositiveButton("接続") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    targetUrl = "http://$ip:8080"
                    Toast.makeText(this, "接続先を $ip に変更しました", Toast.LENGTH_SHORT).show()
                    fetchVideoList()
                    if (isPreviewVisible) reloadStream()
                }
            }
            .setNeutralButton("再スキャン") { _, _ ->
                autoScanServer()
            }
            .setNegativeButton("キャンセル", null)
            .show()
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
                            tvTargetIpCamo.text = "♪"
                            fetchVideoList()
                            if (isPreviewVisible) reloadStream()
                        }
                        break
                    }
                } catch (e: Exception) {}
            }
            if (!found) {
                runOnUiThread {
                    tvTargetIpCamo.text = "♪ 未接続"
                    // 自動スキャンで見つからない場合は自動で手動入力ダイアログを開く
                    showIpConfigDialog()
                }
            }
        }.start()
    }

    private fun sendServerRequest(url: String) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

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
                            displayItems.add("Track ${i+1}: $date (${sizeMb}MB)")
                        }
                        
                        val adapter = ArrayAdapter(this@ClientCamoActivity, android.R.layout.simple_list_item_1, displayItems)
                        lvVaultCamo.adapter = adapter
                        
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

    default override fun onDestroy() {
        super.onDestroy()
        webViewCamo.destroy()
        handler.removeCallbacks(progressRunnable)
    }
}
