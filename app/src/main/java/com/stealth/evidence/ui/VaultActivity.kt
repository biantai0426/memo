package com.stealth.evidence.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.MediaController
import android.widget.RelativeLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.stealth.evidence.R
import java.io.File

class VaultActivity : AppCompatActivity() {

    private lateinit var vaultManager: VaultManager
    private lateinit var vaultListView: ListView
    private lateinit var videoPlayerContainer: RelativeLayout
    private lateinit var videoView: VideoView
    private lateinit var btnCloseVideo: Button
    private lateinit var btnSelfDestruct: Button
    private var currentPlaybackFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)

        vaultManager = VaultManager(this)
        vaultListView = findViewById(R.id.vaultListView)
        videoPlayerContainer = findViewById(R.id.videoPlayerContainer)
        videoView = findViewById(R.id.videoView)
        btnCloseVideo = findViewById(R.id.btnCloseVideo)
        btnSelfDestruct = findViewById(R.id.btnSelfDestruct)

        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        loadVaultFiles()

        btnCloseVideo.setOnClickListener {
            closeVideoPlayer()
        }

        // 🚨 自爆ボタン（誤操作を防ぐため長押しで発動） 🚨
        btnSelfDestruct.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("🚨 最終警告 🚨")
                .setMessage("Vault内の全ての証拠データを完全に破壊（ゼロ埋め消去）します。元には戻せません。本当に実行しますか？")
                .setPositiveButton("完全消去を実行") { _, _ ->
                    vaultManager.executeSelfDestruct()
                    loadVaultFiles() // リストを空に更新
                    Toast.makeText(this, "全ての証拠データを完全に破壊しました", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
            true
        }
    }

    private fun loadVaultFiles() {
        val files = vaultManager.getVaultFiles()
        val fileNames = files.map { it.name }

        val adapter = ArrayAdapter(
            this,
            R.layout.item_vault_file,
            fileNames
        )
        vaultListView.adapter = adapter

        // タップで復号化＆再生開始
        vaultListView.setOnItemClickListener { _, _, position, _ ->
            val selectedFile = files[position]
            playVideo(selectedFile)
        }

        // 長押しでファイルの個別削除
        vaultListView.setOnItemLongClickListener { _, _, position, _ ->
            val selectedFile = files[position]
            AlertDialog.Builder(this)
                .setTitle("ファイルの削除")
                .setMessage("${selectedFile.name} を完全に削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    selectedFile.delete()
                    Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
                    loadVaultFiles()
                }
                .setNegativeButton("キャンセル", null)
                .show()
            true
        }
    }

    private fun playVideo(datFile: File) {
        Toast.makeText(this, "復号化中...", Toast.LENGTH_SHORT).show()

        Thread {
            val decryptedFile = vaultManager.decryptVideoFile(datFile)
            runOnUiThread {
                if (decryptedFile != null && decryptedFile.exists()) {
                    currentPlaybackFile = decryptedFile
                    videoPlayerContainer.visibility = View.VISIBLE
                    videoView.setVideoPath(decryptedFile.absolutePath)
                    videoView.start()
                } else {
                    Toast.makeText(this, "復号化に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun closeVideoPlayer() {
        videoView.stopPlayback()
        videoPlayerContainer.visibility = View.GONE

        currentPlaybackFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        currentPlaybackFile = null
    }

    override fun onBackPressed() {
        if (videoPlayerContainer.visibility == View.VISIBLE) {
            closeVideoPlayer()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeVideoPlayer()
    }
}
