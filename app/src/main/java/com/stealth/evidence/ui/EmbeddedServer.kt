package com.stealth.evidence.ui

import android.content.Context
import android.content.Intent
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class EmbeddedServer(private val context: Context) {
    private var server: NettyApplicationEngine? = null

    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            server = embeddedServer(Netty, port = 8080) {
                routing {
                    // サーバー稼働確認用
                    get("/") {
                        call.respondText("StealthVault Server is running")
                    }
                    // 遠隔：録画開始
                    get("/start") {
                        val intent = Intent(context, StealthCameraService::class.java)
                        context.startService(intent)
                        call.respondText("Recording started via remote")
                    }
                    // 遠隔：録画停止
                    get("/stop") {
                        val intent = Intent(context, StealthCameraService::class.java)
                        context.stopService(intent)
                        call.respondText("Recording stopped via remote")
                    }
                    // 遠隔：アプリ強制キル（証拠隠滅・タスクキル）
                    get("/kill") {
                        call.respondText("Executing Kill Switch...")
                        // 数秒待ってからプロセスを完全に落とす
                        Thread.sleep(1000)
                        exitProcess(0)
                    }
                }
            }
            server?.start(wait = true)
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
    }
}
