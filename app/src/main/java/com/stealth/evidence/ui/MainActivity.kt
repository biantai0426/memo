package com.stealth.evidence.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stealth.evidence.R

class MainActivity : AppCompatActivity() {

    private lateinit var memoEditText: EditText
    private lateinit var saveButton: Button
    private var failCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 13 (API 33) 以上の場合、起動時に通知権限をリクエストする
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        memoEditText = findViewById(R.id.memoEditText)
        saveButton = findViewById(R.id.saveButton)

        // 通常のタップ：ダミーの保存処理
        saveButton.setOnClickListener {
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        }

        // 長押し：入力されたテキスト（PIN）を判定して裏モードを起動
        saveButton.setOnLongClickListener {
            val input = memoEditText.text.toString().trim()
            
            when (input) {
                "1111" -> {
                    failCount = 0
                    Toast.makeText(this, "ダミーモード（買い物リスト）起動", Toast.LENGTH_SHORT).show()
                    memoEditText.setText("")
                }
                "9999" -> {
                    failCount = 0
                    Toast.makeText(this, "シークレット動画金庫 起動", Toast.LENGTH_SHORT).show()
                    memoEditText.setText("")
                }
                "8888" -> {
                    failCount = 0
                    Toast.makeText(this, "クライアント（遠隔操作）モード 起動", Toast.LENGTH_SHORT).show()
                    memoEditText.setText("")
                    
                    // クライアント画面へ遷移
                    val intent = Intent(this@MainActivity, ClientActivity::class.java)
                    startActivity(intent)
                }
                // 自爆用PIN（例：0000）
                "0000" -> {
                    Toast.makeText(this, "データ完全消去（自爆）を実行します", Toast.LENGTH_LONG).show()
                    memoEditText.setText("")
                }
                else -> {
                    // PIN以外の文字が入力されている、または空欄での長押し
                    if (input.length == 4 && input.all { it.isDigit() }) {
                        failCount++
                        if (failCount >= 3) {
                            Toast.makeText(this, "エラー：データ破壊シーケンス起動", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // バックグラウンド録画の開始トリガー
                        Toast.makeText(this, "録画を開始します", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this@MainActivity, StealthCameraService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    }
                }
            }
            true // 長押しイベントをここで消費
        }
    }
}
