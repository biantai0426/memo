package com.stealth.evidence.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stealth.evidence.R

class SecretMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secret_menu)

        // クライアントモードへ遷移
        findViewById<Button>(R.id.btnClientMode).setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }

        // 動画金庫（Vault）へ遷移
        findViewById<Button>(R.id.btnVideoVault).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }

        // 自爆コマンド（今後のステップで実装）
        findViewById<Button>(R.id.btnSelfDestruct).setOnClickListener {
            Toast.makeText(this, "データ完全消去（自爆）を実行します", Toast.LENGTH_LONG).show()
        }
    }
}
