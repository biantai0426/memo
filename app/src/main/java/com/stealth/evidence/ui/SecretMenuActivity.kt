package com.stealth.evidence.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stealth.evidence.R

class SecretMenuActivity : AppCompatActivity() {

    private lateinit var btnServerMode: Button
    private lateinit var btnClientMode: Button
    private lateinit var btnVault: Button
    
    private var isServerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secret_menu)

        btnServerMode = findViewById(R.id.btnServerMode)
        btnClientMode = findViewById(R.id.btnClientMode)
        btnVault = findViewById(R.id.btnVault)

        // 1. サーバーモードの起動／停止
        btnServerMode.setOnClickListener {
            if (!isServerRunning) {
                val intent = Intent(this, StealthCameraService::class.java).apply {
                    action = StealthCameraService.ACTION_START_STANDBY
                }
                startService(intent)
                btnServerMode.text = "■ サーバーモード 停止"
                isServerRunning = true
                Toast.makeText(this, "サーバーモードを起動しました。この端末を現場に設置してください。", Toast.LENGTH_LONG).show()
            } else {
                stopService(Intent(this, StealthCameraService::class.java))
                btnServerMode.text = "サーバーモード 起動\n(現場設置用：カメラと通信待機)"
                isServerRunning = false
                Toast.makeText(this, "サーバーモードを停止しました。", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. クライアントモードへ遷移
        btnClientMode.setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }

        // 3. 動画一覧（Vault）画面へ遷移（修正箇所）
        btnVault.setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }
    }
}
