package com.stealth.evidence.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class EmbeddedServer(private val appContext: Context) {
    private var server: NettyApplicationEngine? = null
    private val TAG = "EmbeddedServer"

    fun start() {
        try {
            // ★修正1: host = "0.0.0.0" を明記し、LAN内の別端末やブラウザからのアクセスを許可する
            server = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
                routing {
                    get("/") {
                        call.respondText("StealthVault Server is running in STANDBY")
                    }

                    // --- カメラ・録画操作 ---
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

                    get("/notify") {
                        sendCamouflageNotification()
                        call.respondText("Camouflage notification sent")
                    }

                    // --- 動画データ（Vault）操作 ---
                    get("/videos") {
                        val vaultManager = VaultManager(appContext)
                        val files = vaultManager.getVaultFiles()

                        val jsonList = files.joinToString(prefix = "[", postfix = "]") { file ->
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
                            "{\"name\":\"${file.name}\", \"size\":${file.length()}, \"date\":\"$dateStr\"}"
                        }
                        call.respondText(jsonList, ContentType.Application.Json)
                    }

                    get("/download") {
                        val fileName = call.request.queryParameters["name"]
                        if (fileName == null) {
                            call.respondText("Missing file name", status = HttpStatusCode.BadRequest)
                            return@get
                        }

                        val vaultManager = VaultManager(appContext)
                        val targetDat = vaultManager.getVaultFiles().find { it.name == fileName }

                        if (targetDat == null) {
                            call.respondText("File not found", status = HttpStatusCode.NotFound)
                            return@get
                        }

                        val decryptedTemp = vaultManager.decryptVideoFile(targetDat)
                        if (decryptedTemp == null) {
                            call.respondText("Decryption failed", status = HttpStatusCode.InternalServerError)
                            return@get
                        }

                        val downloadFileName = fileName.replace(".dat", ".mp4")
                        call.response.header("Content-Disposition", "attachment; filename=\"$downloadFileName\"")

                        call.respondOutputStream(ContentType.parse("video/mp4")) {
                            try {
                                decryptedTemp.inputStream().use { it.copyTo(this) }
                            } finally {
                                if (decryptedTemp.exists()) {
                                    decryptedTemp.delete()
                                }
                            }
                        }
                    }

                    get("/kill") {
                        call.respondText("Executing Kill Switch... Wiping all Vault data.")
                        val vaultManager = VaultManager(appContext)
                        vaultManager.executeSelfDestruct()

                        Thread.sleep(1000)
                        exitProcess(0)
                    }

                    // --- 映像ストリーミング（MJPEG配信） ---
                    get("/stream") {
                        call.response.header("Cache-Control", "no-cache")
                        call.respondOutputStream(ContentType.parse("multipart/x-mixed-replace; boundary=--BoundaryString")) {
                            val out: OutputStream = this
                            try {
                                while (true) {
                                    val jpeg = StealthCameraService.latestJpeg
                                    if (jpeg != null) {
                                        out.write("--BoundaryString\r\n".toByteArray())
                                        out.write("Content-Type: image/jpeg\r\n".toByteArray())
                                        out.write("Content-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                                        out.write(jpeg)
                                        out.write("\r\n".toByteArray())
                                        out.flush()
                                    }
                                    Thread.sleep(33)
                                }
                            } catch (e: Exception) {
                                // 接続切断
                            }
                        }
                    }
                }
            }
            // ★修正2: コルーチンを使わず、Ktorの標準機能で非同期バックグラウンド起動させる（wait = false）
            server?.start(wait = false)
            Log.d(TAG, "Ktor Server successfully started on 0.0.0.0:8080")

        } catch (e: Exception) {
            // ★起動失敗時にLogcatへエラーを出力させる
            Log.e(TAG, "Failed to start Ktor Server: ${e.message}", e)
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
