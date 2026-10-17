package com.stealth.evidence.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stealth.evidence.R
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class SecretMenuActivity : AppCompatActivity() {

    private lateinit var btnServerMode: Button
    private lateinit var btnClientMode: Button
    private lateinit var btnClientCamoMode: Button
    private lateinit var btnVault: Button
    
    private var isServerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secret_menu)

        btnServerMode = findViewById(R.id.btnServerMode)
        btnClientMode = findViewById(R.id.btnClientMode)
        btnClientCamoMode = findViewById(R.id.btnClientCamoMode)
        btnVault = findViewById(R.id.btnVault)

        // 1. サーバーモードの起動／停止
        btnServerMode.setOnClickListener {
            if (!isServerRunning) {
                val intent = Intent(this, StealthCameraService::class.java).apply {
                    action = StealthCameraService.ACTION_START_STANDBY
                }
                startService(intent)
                
                val ipAddress = getLocalIpAddress()
                btnServerMode.text = "■ サーバーモード 停止\n(動作中 IP: $ipAddress)"
                isServerRunning = true
                Toast.makeText(this, "サーバー起動完了\n手元の端末から $ipAddress に接続してください", Toast.LENGTH_LONG).show()
            } else {
                stopService(Intent(this, StealthCameraService::class.java))
                btnServerMode.text = "サーバーモード 起動\n(現場設置用：カメラと通信待機)"
                isServerRunning = false
                Toast.makeText(this, "サーバーモードを停止しました。", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. クライアントモード (シンプルUI) へ遷移
        btnClientMode.setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }

        // 3. クライアントモード (音楽プレイヤー偽装UI) へ遷移
        btnClientCamoMode.setOnClickListener {
            // 次のステップで作成する新しいActivityを呼び出します
            startActivity(Intent(this, ClientCamoActivity::class.java))
        }

        // 4. 動画一覧（Vault）画面へ遷移
        btnVault.setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in Collections.list(interfaces)) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "127.0.0.1"
    }
}
