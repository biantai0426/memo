package com.stealth.evidence.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ClientCamoActivity : AppCompatActivity() {

    private lateinit var tvTargetIpCamo: TextView
    private lateinit var btnConfigIpCamo: TextView
    private lateinit var jacketContainer: FrameLayout
    private lateinit var imgJacket: ImageView
    private lateinit var webViewCamo: WebView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvTrackArtist: TextView
    private lateinit var tvMixListTitle: TextView
    private lateinit var progressBarCamo: ProgressBar
    private lateinit var btnPrevCamo: TextView
    private lateinit var btnPlayPauseCamo: TextView
    private lateinit var btnNextCamo: TextView
    private lateinit var lvVaultCamo: ListView

    private val httpClient = OkHttpClient()
    private var targetUrl = "http://127.0.0.1:8080"

    private var isRecording = false
    private var isPreviewVisible = false
    private var currentTrackName = "なんもねえ"

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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client_camo)

        tvTargetIpCamo = findViewById(R.id.tvTargetIpCamo)
        btnConfigIpCamo = findViewById(R.id.btnConfigIpCamo)
        jacketContainer = findViewById(R.id.jacketContainer)
        imgJacket = findViewById(R.id.imgJacket)
        webViewCamo = findViewById(R.id.webViewCamo)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvTrackArtist = findViewById(R.id.tvTrackArtist)
        tvMixListTitle = findViewById(R.id.tvMixListTitle)
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

        // Apple Musicから最新のトレンド曲を取得してUIを偽装
        fetchAppleMusicData()

        btnConfigIpCamo.setOnClickListener { showIpConfigDialog() }

        jacketContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isPreviewVisible = true
                    imgJacket.visibility = View.GONE
                    webViewCamo.visibility = View.VISIBLE
                    reloadStream()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPreviewVisible = false
                    imgJacket.visibility = View.VISIBLE
                    webViewCamo.visibility = View.GONE
                    webViewCamo.loadUrl("about:blank")
                    true
                }
                else -> false
            }
        }

        btnPrevCamo.setOnClickListener {
            sendServerRequest("$targetUrl/switch")
            if (isPreviewVisible) webViewCamo.postDelayed({ reloadStream() }, 1500)
        }

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

        btnNextCamo.setOnClickListener { sendServerRequest("$targetUrl/notify") }

        autoScanServer()
    }

    // 匿名でApple Musicの公開RSSを取得し、本物の曲データでカモフラージュする
    private fun fetchAppleMusicData() {
        val url = "https://itunes.apple.com/jp/rss/topsongs/limit=20/json"
        val request = Request.Builder().url(url).build()
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // オフライン時はXMLに定義したデフォルトのまま（何もしない）
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val jsonStr = response.body?.string() ?: return
                    val jsonObject = JSONObject(jsonStr)
                    val entries = jsonObject.getJSONObject("feed").getJSONArray("entry")
                    
                    if (entries.length() > 0) {
                        // 20曲の中からランダムに1曲選ぶ
                        val randomIndex = Random.nextInt(entries.length())
                        val entry = entries.getJSONObject(randomIndex)
                        
                        val title = entry.getJSONObject("im:name").getString("label")
                        val artist = entry.getJSONObject("im:artist").getString("label")
                        
                        // 画像URLの取得（一番大きいサイズのものを選択）
                        val images = entry.getJSONArray("im:image")
                        val imageUrl = images.getJSONObject(images.length() - 1).getString("label")
                        
                        currentTrackName = title

                        runOnUiThread {
                            tvTrackTitle.text = title
                            tvTrackArtist.text = artist
                            tvMixListTitle.text = "━\n「$title」を元にしたミックスリスト"
                        }
                        
                        // 画像のダウンロードと適用
                        downloadAndSetImage(imageUrl)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    private fun downloadAndSetImage(imageUrl: String) {
        val request = Request.Builder().url(imageUrl).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                try {
                    val inputStream = response.body?.byteStream()
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        runOnUiThread {
                            imgJacket.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    private fun reloadStream() {
        val streamUrl = "$targetUrl/stream"
        val html = "<html><body style='margin:0;padding:0;background-color:black;display:flex;justify-content:center;align-items:center;'><img src='$streamUrl' width='100%' /></body></html>"
        webViewCamo.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun showIpConfigDialog() {
        val input = EditText(this).apply {
            hint = "例: 192.168.43.1"
            val currentIp = targetUrl.replace("http://", "").replace(":8080", "")
            setText(currentIp)
        }

        AlertDialog.Builder(this)
            .setTitle("接続先IPアドレス設定")
            .setMessage("現場端末のIPを入力してください")
            .setView(input)
            .setPositiveButton("接続") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) setTargetIp(ip)
            }
            .setNeutralButton("再スキャン") { _, _ -> autoScanServer() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun setTargetIp(ip: String) {
        targetUrl = "http://$ip:8080"
        tvTargetIpCamo.text = "♪" 
        Toast.makeText(this, "接続先を $ip に設定しました", Toast.LENGTH_SHORT).show()
        fetchVideoList()
        if (isPreviewVisible) reloadStream()
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
        } catch (e: Exception) {}

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
                        runOnUiThread { setTargetIp(ip) }
                        break
                    }
                } catch (e: Exception) {}
            }
            if (!found) {
                runOnUiThread { tvTargetIpCamo.text = "♪ 接続待機" }
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
                            val sizeMb = obj.getLong("size") / (1024 * 1024)
                            fileNames.add(name)
                            // 音楽のプレイリストっぽく偽装
                            displayItems.add("Track ${i+1} : $currentTrackName - Remix (${sizeMb}MB)")
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

    override fun onDestroy() {
        super.onDestroy()
        webViewCamo.destroy()
        handler.removeCallbacks(progressRunnable)
    }
}
