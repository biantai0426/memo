package com.stealth.evidence.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stealth.evidence.R
import java.net.NetworkInterface

class SecretMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secret_menu)

        // サーバーのIPアドレスを取得して表示
        val tvServerInfo = findViewById<TextView>(R.id.tvServerInfo)
        val ipAddress = getLocalIpAddress()
        tvServerInfo.text = "【ブラウザ遠隔操作URL】\nhttp://$ipAddress:8080"

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

    // Wi-Fi接続時のローカルIPアドレスを取得する関数
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces.toList()) {
                val addrs = intf.inetAddresses.toList()
                for (addr in addrs) {
                    // ループバックアドレス以外で、IPv4のものを探す
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress ?: "不明"
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "未接続 または 取得失敗"
    }
}
