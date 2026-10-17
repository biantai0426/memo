package com.stealth.evidence.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.stealth.evidence.R

class MainActivity : AppCompatActivity() {

    private lateinit var localServer: EmbeddedServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 通知権限のリクエスト (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // アプリ起動時（MainActivity生成時）にKtorサーバーを自動起動
        localServer = EmbeddedServer(applicationContext)
        localServer.start()

        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)

        // 通常タップ時のダミー動作
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_memo -> Toast.makeText(this, "メモ一覧を表示します", Toast.LENGTH_SHORT).show()
                R.id.nav_task -> Toast.makeText(this, "タスクはありません", Toast.LENGTH_SHORT).show()
                R.id.nav_settings -> Toast.makeText(this, "設定項目はありません", Toast.LENGTH_SHORT).show()
            }
            true
        }

        // BottomNavigationViewのレイアウト描画完了を待ってから長押しイベントをセット
        bottomNavigation.post {
            setupLongClicks(bottomNavigation)
        }
    }

    private fun setupLongClicks(bottomNavigation: BottomNavigationView) {
        val navMemo = bottomNavigation.findViewById<View>(R.id.nav_memo)
        val navTask = bottomNavigation.findViewById<View>(R.id.nav_task)
        val navSettings = bottomNavigation.findViewById<View>(R.id.nav_settings)

        // 1. メモ長押し：外カメラ（背面）の録画切り替え
        navMemo?.setOnLongClickListener {
            Toast.makeText(this, "外カメラの録画を切り替えます", Toast.LENGTH_SHORT).show()
            toggleRecording(CameraSelector.LENS_FACING_BACK)
            true
        }

        // 2. タスク長押し：内カメラ（前面）の録画切り替え
        navTask?.setOnLongClickListener {
            Toast.makeText(this, "内カメラの録画を切り替えます", Toast.LENGTH_SHORT).show()
            toggleRecording(CameraSelector.LENS_FACING_FRONT)
            true
        }

        // 3. 設定長押し：パスワード要求ダイアログ表示
        navSettings?.setOnLongClickListener {
            showPasswordDialog()
            true
        }
    }

    private fun toggleRecording(lensFacing: Int) {
        val intent = Intent(this, StealthCameraService::class.java).apply {
            putExtra("EXTRA_CAMERA_FACING", lensFacing)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showPasswordDialog() {
        val editText = EditText(this).apply {
            hint = "パスワード"
            // パスワード用の入力キーボード（数字のみ）を指定
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        
        AlertDialog.Builder(this)
            .setTitle("プライベートメモ")
            .setMessage("パスワードを入力してください")
            .setView(editText)
            .setPositiveButton("確認") { _, _ ->
                val password = editText.text.toString()
                if (password == "9999") { 
                    // SecretMenuActivity（ハブ画面）へ遷移
                    val intent = Intent(this, SecretMenuActivity::class.java)
                    startActivity(intent)
                    Toast.makeText(this, "認証成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "パスワードが違います", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // アプリ画面が完全に閉じられた際にサーバーも停止する
        localServer.stop()
    }
}
