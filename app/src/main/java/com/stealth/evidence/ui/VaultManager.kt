package com.stealth.evidence.ui

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VaultManager(private val context: Context) {
    private val vaultDir = File(context.filesDir, "vault")
    // ★修正：StealthCameraService側と暗号化キーを完全に一致させる (0x5A -> 0xAA)
    private val xorKey: Byte = 0xAA.toByte()

    init {
        // vaultディレクトリの作成
        if (!vaultDir.exists()) {
            vaultDir.mkdirs()
        }
        // ギャラリーやファイラーから隠蔽するための .nomedia 生成
        val nomedia = File(vaultDir, ".nomedia")
        if (!nomedia.exists()) {
            nomedia.createNewFile()
        }
    }

    // 録画用の一時ファイルを作成
    fun createTempVideoFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(vaultDir, "temp_video_${timestamp}.mp4")
    }

    // 録画完了後、先頭256バイトをXOR暗号化して.datとして偽装保存
    fun finalizeVideoFile(tempFile: File): File? {
        if (!tempFile.exists()) return null
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val finalFile = File(vaultDir, "sync_data_${timestamp}.dat")

        try {
            FileInputStream(tempFile).use { fis ->
                FileOutputStream(finalFile).use { fos ->
                    val buffer = ByteArray(4096)
                    var bytesRead = fis.read(buffer)
                    var xoredCount = 0

                    while (bytesRead != -1) {
                        // 先頭256バイトのみXOR処理
                        if (xoredCount < 256) {
                            for (i in 0 until bytesRead) {
                                if (xoredCount < 256) {
                                    buffer[i] = (buffer[i].toInt() xor xorKey.toInt()).toByte()
                                    xoredCount++
                                }
                            }
                        }
                        fos.write(buffer, 0, bytesRead)
                        bytesRead = fis.read(buffer)
                    }
                }
            }
            // 元のMP4は削除
            tempFile.delete()
            return finalFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // --- 追加：保存されている .dat ファイルの一覧を取得（新しい順） ---
    fun getVaultFiles(): List<File> {
        return vaultDir.listFiles { _, name -> name.endsWith(".dat") }
            ?.toList()
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    // --- 追加：再生用に .dat ファイルを復号して一時的な .mp4 ファイルを作成 ---
    fun decryptVideoFile(datFile: File): File? {
        if (!datFile.exists()) return null
        // 証拠隠滅を容易にするため、復号されたファイルはキャッシュディレクトリに一時展開
        val tempPlaybackFile = File(context.cacheDir, "playback_${System.currentTimeMillis()}.mp4")

        try {
            FileInputStream(datFile).use { fis ->
                FileOutputStream(tempPlaybackFile).use { fos ->
                    val buffer = ByteArray(4096)
                    var bytesRead = fis.read(buffer)
                    var xoredCount = 0

                    while (bytesRead != -1) {
                        // もう一度XOR処理を行うことで元のバイトデータに復元
                        if (xoredCount < 256) {
                            for (i in 0 until bytesRead) {
                                if (xoredCount < 256) {
                                    buffer[i] = (buffer[i].toInt() xor xorKey.toInt()).toByte()
                                    xoredCount++
                                }
                            }
                        }
                        fos.write(buffer, 0, bytesRead)
                        bytesRead = fis.read(buffer)
                    }
                }
            }
            return tempPlaybackFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // 自爆機能：vault内の全.datファイルをゼロ埋め上書きしてから削除
    fun executeSelfDestruct() {
        val files = vaultDir.listFiles { _, name -> name.endsWith(".dat") } ?: return
        val zeroBuffer = ByteArray(4096) { 0 }

        for (file in files) {
            try {
                val size = file.length()
                var written = 0L
                // 確実にデータを破壊するためゼロで上書き
                FileOutputStream(file).use { fos ->
                    while (written < size) {
                        val toWrite = if (size - written > zeroBuffer.size) zeroBuffer.size else (size - written).toInt()
                        fos.write(zeroBuffer, 0, toWrite)
                        written += toWrite
                    }
                }
                // ゼロ埋め後に削除
                file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
