package com.stealth.evidence.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Switch
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

class ClientActivity : AppCompatActivity() {

    private lateinit var previewToggleSwitch: Switch
    private lateinit var switchCameraButton: Button
    private lateinit var startRecordButton: Button
    private lateinit var stopRecordButton: Button
    private lateinit var sendNotificationButton: Button
    private lateinit var downloadVideoButton: Button
    private lateinit var webView: WebView

    private val httpClient = OkHttpClient()
    
    // 現場（サーバー）のIPアドレス。テザリング時は192.168.43.1などが一般的。
    // ※今回は同じ端末でテストできるようローカルホストにしていますが、
    // 実運用の際は設定画面等でIPを変更できるようにすると便利です。
    private val serverBaseUrl = "http://192.168.43.1:8080" 
    private val localTestUrl = "http://127.0.0.1:8080"
    
    // 接続先URL（テスト時は localTestUrl、実運用時は serverBaseUrl に切り替え）
    private var targetUrl = localTestUrl 

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

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

        // 1. プレビューのON/OFF
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

        // 2. カメラ向き切替
        switchCameraButton.setOnClickListener {
            sendServerRequest("$targetUrl/switch", "カメラの向きを切り替えました")
            if (previewToggleSwitch.isChecked) {
                webView.postDelayed({
                    val streamUrl = "$targetUrl/stream"
                    val html = "<html><body style='margin:0;padding:0;background-color:black;display:flex;justify-content:center;align-items:center;'><img src='$streamUrl' width='100%' /></body></html>"
                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }, 1500)
            }
        }

        // 3. 録画開始
        startRecordButton.setOnClickListener {
            sendServerRequest("$targetUrl/start", "Vaultへの証拠保存を開始しました")
        }

        // 4. 録画停止
        stopRecordButton.setOnClickListener {
            sendServerRequest("$targetUrl/stop", "Vaultへの証拠保存を停止しました")
        }

        // 5. 通知送信
        sendNotificationButton.setOnClickListener {
            sendServerRequest("$targetUrl/notify", "カモフラージュ通知を送信しました")
        }

        // 6. 遠隔ダウンロード（ファイル一覧を取得して選択ダイアログを表示）
        downloadVideoButton.setOnClickListener {
            fetchVideoList()
        }
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
                            Toast.makeText(this@ClientActivity, "現場に保存された動画はありません", Toast.LENGTH_SHORT).show()
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
                val selectedFile = fileNames[which]
                downloadVideoFile(selectedFile)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun downloadVideoFile(fileName: String) {
        // 端末標準のブラウザ/ダウンローダーにURLを投げてダウンロードを任せるのが一番安全で確実です
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
