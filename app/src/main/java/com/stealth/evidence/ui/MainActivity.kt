package com.stealth.evidence.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stealth.evidence.R

class MainActivity : AppCompatActivity() {

    private lateinit var memoEditText: EditText
    private lateinit var saveButton: Button
    private var failCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
                        // バックグラウンド録画の開始/停止トリガー
                        Toast.makeText(this, "録画サービスを切り替えます", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true // 長押しイベントをここで消費
        }
    }
}
