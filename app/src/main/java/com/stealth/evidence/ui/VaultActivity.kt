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
    
    private var currentPlaybackFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)

        vaultManager = VaultManager(this)
        vaultListView = findViewById(R.id.vaultListView)
        videoPlayerContainer = findViewById(R.id.videoPlayerContainer)
        videoView = findViewById(R.id.videoView)
        btnCloseVideo = findViewById(R.id.btnCloseVideo)

        // 動画プレイヤーのコントローラー（再生/一時停止/シークバー）を設定
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        loadVaultFiles()

        // 閉じるボタンでプレイヤーを隠し、一時ファイルを削除
        btnCloseVideo.setOnClickListener {
            closeVideoPlayer()
        }
    }

    private fun loadVaultFiles() {
        val files = vaultManager.getVaultFiles()
        val fileNames = files.map { it.name }

        // ダークテーマ用のカスタムレイアウトを適用
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
        
        // 長押しでファイルの削除ダイアログ
        vaultListView.setOnItemLongClickListener { _, _, position, _ ->
            val selectedFile = files[position]
            AlertDialog.Builder(this)
                .setTitle("ファイルの削除")
                .setMessage("${selectedFile.name} を完全に削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    selectedFile.delete()
                    Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
                    loadVaultFiles() // リストを更新
                }
                .setNegativeButton("キャンセル", null)
                .show()
            true
        }
    }

    private fun playVideo(datFile: File) {
        Toast.makeText(this, "復号化中...", Toast.LENGTH_SHORT).show()
        
        // UIがフリーズしないように別スレッドでXOR復号を実行
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
        
        // 再生が終わったら一時ファイルを確実に削除（証拠隠滅）
        currentPlaybackFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        currentPlaybackFile = null
    }

    // スマホの「戻る」ボタンを押したときの処理
    override fun onBackPressed() {
        if (videoPlayerContainer.visibility == View.VISIBLE) {
            closeVideoPlayer() // プレイヤー表示中なら閉じるだけ
        } else {
            super.onBackPressed() // 通常の戻る動作
        }
    }
    
    // アプリが裏に回ったり閉じられたりした際の安全対策
    override fun onDestroy() {
        super.onDestroy()
        closeVideoPlayer() 
    }
}
