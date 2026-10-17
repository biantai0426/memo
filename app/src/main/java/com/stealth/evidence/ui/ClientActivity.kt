package com.stealth.evidence.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
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

class ClientActivity : AppCompatActivity() {

    private lateinit var tvTargetIp: TextView
    private lateinit var btnConfigIp: Button
    private lateinit var previewToggleSwitch: Switch
    private lateinit var switchCameraButton: Button
    private lateinit var startRecordButton: Button
    private lateinit var stopRecordButton: Button
    private lateinit var sendNotificationButton: Button
    private lateinit var downloadVideoButton: Button
    private lateinit var webView: WebView

    private val httpClient = OkHttpClient()
    
    // 現在の接続先
    private var targetUrl = "http://127.0.0.1:8080"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        tvTargetIp = findViewById(R.id.tvTargetIp)
        btnConfigIp = findViewById(R.id.btnConfigIp)
        previewToggleSwitch = findViewById(R.id.previewToggleSwitch)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        startRecordButton = findViewById(R.id.startRecordButton)
        stopRecordButton = findViewById(R.id.stopRecordButton)
        sendNotificationButton = findViewById(R.id.sendNotificationButton)
        downloadVideoButton = findViewById(R.id.downloadVideoButton)
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.setBackgroundColor(0)

        // IP手動設定ボタン
        btnConfigIp.setOnClickListener {
            showIpConfigDialog()
        }

        // 各種ボタンのイベント
        previewToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                webView.visibility = View.VISIBLE
                val streamUrl = "$targetUrl/stream"
                val html = "<html><body style='margin:0;padding:0;background-color:black;display:flex;justify-content:center;align-items:center;'><img src='$streamUrl' width='100%' /></body></html>"
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            } else {
                webView.visibility = View.GONE
                webView.loadUrl("about:blank")
            }
        }

        switchCameraButton.setOnClickListener {
            sendServerRequest("$targetUrl/switch", "カメラの向きを切り替えました")
            if (previewToggleSwitch.isChecked) {
                webView.postDelayed({ reloadStream() }, 1500)
            }
        }

        startRecordButton.setOnClickListener { sendServerRequest("$targetUrl/start", "Vaultへの証拠保存を開始") }
        stopRecordButton.setOnClickListener { sendServerRequest("$targetUrl/stop", "Vaultへの証拠保存を停止") }
        sendNotificationButton.setOnClickListener { sendServerRequest("$targetUrl/notify", "カモフラージュ通知を送信") }
        downloadVideoButton.setOnClickListener { fetchVideoList() }

        // 画面起動時にネットワークを自動スキャン（クラッシュ対策済み）
        autoScanServer()
    }

    private fun reloadStream() {
        val streamUrl = "$targetUrl/stream"
        val html = "<html><body style='margin:0;padding:0;background-color:black;display:flex;justify-content:center;align-items:center;'><img src='$streamUrl' width='100%' /></body></html>"
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    // IP手動設定ダイアログ
    private fun showIpConfigDialog() {
        val input = EditText(this).apply {
            hint = "例: 192.168.43.1"
            val currentIp = targetUrl.replace("http://", "").replace(":8080", "")
            setText(currentIp)
        }

        AlertDialog.Builder(this)
            .setTitle("サーバーのIPアドレスを入力")
            .setMessage("現場端末の画面に表示されたIPアドレスを入力してください。")
            .setView(input)
            .setPositiveButton("接続") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    setTargetIp(ip)
                }
            }
            .setNeutralButton("再スキャン") { _, _ ->
                autoScanServer()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun setTargetIp(ip: String) {
        targetUrl = "http://$ip:8080"
        tvTargetIp.text = "接続先: $ip"
        Toast.makeText(this, "接続先を $ip に設定しました", Toast.LENGTH_SHORT).show()
        sendServerRequest("$targetUrl/", "通信テスト: $ip に接続成功！")
        
        if (previewToggleSwitch.isChecked) {
            reloadStream()
        }
    }

    // テザリングやローカルホストを自動スキャンして接続
    private fun autoScanServer() {
        tvTargetIp.text = "スキャン中..."
        
        val possibleIps = mutableListOf("192.168.43.1", "192.168.49.1", "127.0.0.1")
        
        // ★修正：権限エラーなどでクラッシュしないようtry-catchで完全に保護
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
            // エラーが発生してもアプリは落とさず、あらかじめ用意した possibleIps のみでスキャンを続行する
            e.printStackTrace()
        }

        Thread {
            var found = false
            // タイムアウトを1秒にして高速スキャン
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
                        runOnUiThread { setTargetIp(ip) }
                        break
                    }
                } catch (e: Exception) {
                    // 繋がらなければ次へ
                }
            }
            
            if (!found) {
                runOnUiThread {
                    tvTargetIp.text = "未接続"
                    Toast.makeText(this@ClientActivity, "サーバーが見つかりません。手動で入力してください。", Toast.LENGTH_LONG).show()
                    showIpConfigDialog()
                }
            }
        }.start()
    }

    private fun sendServerRequest(url: String, successMessage: String) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@ClientActivity, "通信失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    Toast.makeText(this@ClientActivity, successMessage, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun fetchVideoList() {
        val request = Request.Builder().url("$targetUrl/videos").build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@ClientActivity, "一覧取得失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val jsonStr = response.body?.string() ?: ""
                runOnUiThread {
                    try {
                        val jsonArray = JSONArray(jsonStr)
                        if (jsonArray.length() == 0) {
                            Toast.makeText(this@ClientActivity, "動画はありません", Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        val fileNames = mutableListOf<String>()
                        val displayItems = mutableListOf<String>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val name = obj.getString("name")
                            val date = obj.getString("date")
                            val sizeMb = obj.getLong("size") / (1024 * 1024)
                            fileNames.add(name)
                            displayItems.add("撮影: $date\nサイズ: ${sizeMb}MB")
                        }
                        showDownloadDialog(fileNames, displayItems)
                    } catch (e: Exception) {
                        Toast.makeText(this@ClientActivity, "データ解析エラー", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun showDownloadDialog(fileNames: List<String>, displayItems: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayItems)
        AlertDialog.Builder(this)
            .setTitle("ダウンロードする動画を選択")
            .setAdapter(adapter) { _, which ->
                downloadVideoFile(fileNames[which])
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun downloadVideoFile(fileName: String) {
        val downloadUrl = "$targetUrl/download?name=$fileName"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
        startActivity(intent)
        Toast.makeText(this, "ダウンロードを開始します", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
