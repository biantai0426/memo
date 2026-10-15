package com.stealth.evidence.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeKeyTriggerService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 今回は画面の読み上げ等ではなく物理キーのみ監視するため空でOK
    }

    override fun onInterrupt() {
        // サービスがシステムから中断された時の処理
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // キーが押し込まれた瞬間（ACTION_DOWN）を検知
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    // 音量アップ：録画開始
                    startCameraService()
                    vibrate(1) // 1回振動
                    return true // trueを返して音量バーのUI表示をブロック
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // 音量ダウン：録画停止
                    stopCameraService()
                    vibrate(2) // 2回振動
                    return true // trueを返して音量バーのUI表示をブロック
                }
            }
        }
        
        // キーを離した瞬間（ACTION_UP）もイベントを消費しないと音が鳴る機種があるためブロック
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return true
            }
        }
        
        // 音量キー以外（電源ボタンなど）は通常通りシステムへ返す
        return super.onKeyEvent(event)
    }

    private fun startCameraService() {
        val intent = Intent(this, StealthCameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopCameraService() {
        val intent = Intent(this, StealthCameraService::class.java)
        stopService(intent)
    }

    private fun vibrate(times: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return

        if (times == 1) {
            // 短く1回振動（録画開始の合図）
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else if (times == 2) {
            // 短く2回振動（録画終了の合図：200ms振動、200ms待機、200ms振動）
            val timings = longArrayOf(0, 200, 200, 200)
            val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        }
    }
}
