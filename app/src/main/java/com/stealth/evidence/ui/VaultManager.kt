package com.stealth.evidence.ui

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VaultManager(private val context: Context) {
    private val vaultDir = File(context.filesDir, "vault")
    private val xorKey: Byte = 0xAA.toByte()

    init {
        if (!vaultDir.exists()) {
            vaultDir.mkdirs()
        }
        val nomedia = File(vaultDir, ".nomedia")
        if (!nomedia.exists()) {
            nomedia.createNewFile()
        }
    }

    fun createTempVideoFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(vaultDir, "temp_video_${timestamp}.mp4")
    }

    fun finalizeVideoFile(tempFile: File): File? {
        if (!tempFile.exists()) return null
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val finalFile = File(vaultDir, "sync_data_${timestamp}.dat")

        try {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()

            RandomAccessFile(finalFile, "rw").use { raf ->
                val headerSize = 256.coerceAtMost(raf.length().toInt())
                val buffer = ByteArray(headerSize)
                raf.readFully(buffer)
                for (i in 0 until headerSize) {
                    buffer[i] = (buffer[i].toInt() xor xorKey.toInt()).toByte()
                }
                raf.seek(0)
                raf.write(buffer)
            }
            return finalFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun getVaultFiles(): List<File> {
        return vaultDir.listFiles { _, name -> name.endsWith(".dat") }
            ?.toList()
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun decryptVideoFile(datFile: File): File? {
        if (!datFile.exists() || datFile.length() == 0L) return null
        val tempPlaybackFile = File(context.cacheDir, "playback_${System.currentTimeMillis()}.mp4")

        try {
            // 一時ファイルにコピー
            datFile.copyTo(tempPlaybackFile, overwrite = true)

            // 先頭256バイトのみをピンポイントで復号（再XORで元データに戻す）
            RandomAccessFile(tempPlaybackFile, "rw").use { raf ->
                val headerSize = 256.coerceAtMost(raf.length().toInt())
                val buffer = ByteArray(headerSize)
                raf.readFully(buffer)
                for (i in 0 until headerSize) {
                    buffer[i] = (buffer[i].toInt() xor xorKey.toInt()).toByte()
                }
                raf.seek(0)
                raf.write(buffer)
            }
            return tempPlaybackFile
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempPlaybackFile.exists()) tempPlaybackFile.delete()
            return null
        }
    }

    fun executeSelfDestruct() {
        val files = vaultDir.listFiles { _, name -> name.endsWith(".dat") } ?: return
        val zeroBuffer = ByteArray(4096) { 0 }

        for (file in files) {
            try {
                val size = file.length()
                var written = 0L
                FileOutputStream(file).use { fos ->
                    while (written < size) {
                        val toWrite = if (size - written > zeroBuffer.size) zeroBuffer.size else (size - written).toInt()
                        fos.write(zeroBuffer, 0, toWrite)
                        written += toWrite
                    }
                }
                file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
