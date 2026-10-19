package com.stealth.evidence.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.stealth.evidence.R
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StealthCameraService : Service() {

    companion object {
        const val ACTION_START_STANDBY = "ACTION_START_STANDBY"
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "stealth_camera_channel"
        private const val TAG = "StealthService"
    }

    private var webServer: StealthWebServer? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentJpegData: ByteArray? = null
    private var isRecording = false // 現在の録画状態
    private var currentVideoFile: File? = null
    private var videoOutputStream: FileOutputStream? = null
    
    private var isFrontCamera = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_STANDBY) {
            val notification = createNotification("システム待機中...")
            startForeground(NOTIFICATION_ID, notification)
            openCamera(isFrontCamera)
            startWebServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWebServer()
        closeCamera()
        stopBackgroundThread()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "システムサービス",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "バックグラウンドで動作するために必要な通知です。"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("システムサービス")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    // カメラの切り替え
    private fun switchCamera() {
        isFrontCamera = !isFrontCamera
        closeCamera()
        openCamera(isFrontCamera)
    }

    // 録画の開始（ファイル名を「時分秒」に変更）
    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        
        try {
            val dir = File(filesDir, "vault")
            if (!dir.exists()) dir.mkdirs()
            
            // ★修正：ファイル名を HHmmss (時分秒) の形式にする
            val sdf = SimpleDateFormat("HHmmss", Locale.getDefault())
            val timeString = sdf.format(Date())
            val fileName = "$timeString.dat"
            
            currentVideoFile = File(dir, fileName)
            videoOutputStream = FileOutputStream(currentVideoFile)
            
            Log.d(TAG, "Recording started: $fileName")
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
        }
    }

    // 録画の停止
    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        
        try {
            videoOutputStream?.flush()
            videoOutputStream?.close()
            
            // 録画終了時に先頭をXOR暗号化（フォレンジック対策）
            currentVideoFile?.let { encryptFileHeader(it) }
            
            Log.d(TAG, "Recording stopped: ${currentVideoFile?.name}")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            videoOutputStream = null
            currentVideoFile = null
        }
    }
    
    // ダミー通知を鳴らす（画面を光らせる用）
    private fun playCamouflageNotification() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, uri)
            r.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun encryptFileHeader(file: File) {
        if (!file.exists()) return
        try {
            val data = file.readBytes()
            val headerSize = 256.coerceAtMost(data.size)
            val xorKey = 0xAA.toByte()
            for (i in 0 until headerSize) {
                data[i] = (data[i].toInt() xor xorKey.toInt()).toByte()
            }
            file.writeBytes(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(useFront: Boolean = false) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var targetCameraId: String? = null
            for (cameraId in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val targetFacing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                if (facing == targetFacing) {
                    targetCameraId = cameraId
                    break
                }
            }
            
            if (targetCameraId == null) targetCameraId = manager.cameraIdList[0]

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startCaptureSession() {
        cameraDevice?.let { camera ->
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        processImage(image)
                        image.close()
                    }
                }, backgroundHandler)
            }

            try {
                val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                requestBuilder.addTarget(imageReader!!.surface)

                camera.createCaptureSession(
                    listOf(imageReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                            } catch (e: CameraAccessException) { e.printStackTrace() }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, null
                )
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
    }

    private fun processImage(image: Image) {
        val buffer0 = image.planes[0].buffer
        val buffer1 = image.planes[1].buffer
        val buffer2 = image.planes[2].buffer

        val buffer0Size = buffer0.remaining()
        val buffer1Size = buffer1.remaining()
        val buffer2Size = buffer2.remaining()

        val nv21 = ByteArray(buffer0Size + buffer1Size + buffer2Size)
        buffer0.get(nv21, 0, buffer0Size)
        buffer2.get(nv21, buffer0Size, buffer2Size)
        buffer1.get(nv21, buffer0Size + buffer2Size, buffer1Size)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
        var jpegBytes = out.toByteArray()

        // 映像の回転処理（フロントカメラの場合は鏡映しにする）
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val matrix = Matrix()
        matrix.postRotate(90f)
        if (isFrontCamera) {
            matrix.postScale(-1f, 1f) // 左右反転
        }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val rotatedOut = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 50, rotatedOut)
        jpegBytes = rotatedOut.toByteArray()
        
        currentJpegData = jpegBytes

        // 録画中ならMJPEG形式としてファイルに書き出す
        if (isRecording && videoOutputStream != null) {
            try {
                videoOutputStream?.write(jpegBytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private fun startWebServer() {
        if (webServer == null) {
            webServer = StealthWebServer(8080)
            try {
                webServer?.start()
                Log.d(TAG, "Web Server started on port 8080")
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun stopWebServer() {
        webServer?.stop()
        webServer = null
    }

    // HTTPリクエストを受け付けるローカルサーバー
    private inner class StealthWebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            
            // ★追加：クライアントに現在の録画状態と動画リストを返すAPI
            if (uri == "/status") {
                val jsonResponse = JSONObject()
                jsonResponse.put("isRecording", isRecording)
                
                // 現在の動画リストを取得して含める
                val dir = File(filesDir, "vault")
                val files = dir.listFiles { _, name -> name.endsWith(".dat") } ?: arrayOf()
                val jsonArray = JSONArray()
                files.sortedByDescending { it.lastModified() }.forEach { file ->
                    val obj = JSONObject()
                    obj.put("name", file.name)
                    obj.put("size", file.length())
                    jsonArray.put(obj)
                }
                jsonResponse.put("videos", jsonArray)
                
                return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse.toString())
            }
            
            if (uri == "/stream") {
                return streamResponse()
            }
            if (uri == "/start") {
                startRecording()
                return newFixedLengthResponse("Recording started")
            }
            if (uri == "/stop") {
                stopRecording()
                return newFixedLengthResponse("Recording stopped")
            }
            if (uri == "/switch") {
                switchCamera()
                return newFixedLengthResponse("Camera switched")
            }
            if (uri == "/notify") {
                playCamouflageNotification()
                return newFixedLengthResponse("Notification played")
            }
            // (後方互換性) 単純な動画リスト取得
            if (uri == "/videos") {
                val dir = File(filesDir, "vault")
                val files = dir.listFiles { _, name -> name.endsWith(".dat") } ?: arrayOf()
                val jsonArray = JSONArray()
                
                files.sortedByDescending { it.lastModified() }.forEach { file ->
                    val obj = JSONObject()
                    obj.put("name", file.name)
                    obj.put("size", file.length())
                    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
                    obj.put("date", sdf.format(Date(file.lastModified())))
                    jsonArray.put(obj)
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
            }
            // 動画ファイルのダウンロード
            if (uri == "/download") {
                val params = session.parameters
                val fileName = params["name"]?.get(0)
                if (fileName != null) {
                    val file = File(File(filesDir, "vault"), fileName)
                    if (file.exists()) {
                        try {
                            // ダウンロード用に復号して送る
                            val decryptedData = file.readBytes()
                            val headerSize = 256.coerceAtMost(decryptedData.size)
                            val xorKey = 0xAA.toByte()
                            for (i in 0 until headerSize) {
                                decryptedData[i] = (decryptedData[i].toInt() xor xorKey.toInt()).toByte()
                            }
                            // 拡張子を .mp4 に偽装してダウンロードさせる（MJPEGですが一部プレイヤーで再生可能）
                            val downloadName = fileName.replace(".dat", ".mp4")
                            
                            val response = newFixedLengthResponse(Response.Status.OK, "video/mp4", decryptedData.inputStream(), decryptedData.size.toLong())
                            response.addHeader("Content-Disposition", "attachment; filename=\"$downloadName\"")
                            return response
                        } catch (e: Exception) {
                            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Decryption error")
                        }
                    }
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "File not found")
            }
            
            return newFixedLengthResponse("Stealth Server Running")
        }

        private fun streamResponse(): Response {
            val response = object : Response(Status.OK, "multipart/x-mixed-replace; boundary=--BoundaryString", null, 0) {
                override fun send(out: java.io.OutputStream) {
                    try {
                        while (true) {
                            currentJpegData?.let { data ->
                                out.write(("--BoundaryString\r\n").toByteArray())
                                out.write(("Content-Type: image/jpeg\r\n").toByteArray())
                                out.write(("Content-Length: ${data.size}\r\n\r\n").toByteArray())
                                out.write(data)
                                out.write(("\r\n").toByteArray())
                                out.flush()
                            }
                            Thread.sleep(100)
                        }
                    } catch (e: Exception) {
                        // ストリーム終了
                    }
                }
            }
            return response
        }
    }
}
