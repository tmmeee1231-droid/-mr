package com.manw.agent

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ManwAgentService : Service() {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var streamOut: DataOutputStream? = null
    private var imageThread: HandlerThread? = null
    private val streaming = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "MANW اتصال ومشاركة شاشة", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val projectionData = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") (intent?.getParcelableExtra(EXTRA_PROJECTION_DATA))
        val wantsProjection = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) == Activity.RESULT_OK && projectionData != null
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("MANW Agent نشط")
            .setContentText(if (wantsProjection) "مشاركة الشاشة نشطة • أوقفها من التطبيق" else "استقبال محلي ظاهر للمستخدم")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (wantsProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, types)
        } else startForeground(NOTIFICATION_ID, notification)
        if (worker?.isAlive != true) startServer()
        if (wantsProjection && projection == null) beginProjection(intent!!.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED), projectionData!!)
        return START_STICKY
    }

    private fun beginProjection(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mp = manager.getMediaProjection(resultCode, data)
            projection = mp
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.coerceAtMost(1280)
            val height = (width.toFloat() * metrics.heightPixels / metrics.widthPixels).toInt().coerceAtLeast(1)
            val density = metrics.densityDpi
            val imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            reader = imageReader
            imageThread = HandlerThread("MANW-Screen-Capture").also { it.start() }
            val handler = Handler(imageThread!!.looper)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopProjection() }
            }, handler)
            imageReader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer: ByteBuffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(buffer)
                    val frame = Bitmap.createBitmap(padded, 0, 0, width, height)
                    padded.recycle()
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        frame.compress(Bitmap.CompressFormat.JPEG, 55, out); out.toByteArray()
                    }
                    frame.recycle()
                    val output = streamOut
                    if (streaming.get() && output != null && bytes.size < 2_000_000) synchronized(output) {
                        output.writeInt(bytes.size); output.write(bytes); output.flush()
                    }
                } catch (_: Exception) { streaming.set(false); streamOut = null }
                finally { image.close() }
            }, handler)
            virtualDisplay = mp.createVirtualDisplay("MANW-Screen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler)
        } catch (_: Exception) { stopProjection() }
    }

    private fun startServer() {
        worker = Thread {
            try {
                ServerSocket(PORT).use { listener ->
                    server = listener
                    while (!listener.isClosed) {
                        val client = listener.accept()
                        Thread { handleClient(client) }.start()
                    }
                }
            } catch (_: Exception) { } finally { server = null }
        }.apply { name = "MANW-Local-Agent"; start() }
    }

    private fun readLine(input: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 256) {
            val b = input.read()
            if (b < 0 || b == 10) break
            if (b != 13) bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 15000
                val input = socket.getInputStream()
                val output = DataOutputStream(socket.getOutputStream())
                val request = readLine(input)
                val expected = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PIN, "") ?: ""
                if (request != "PAIR|$expected") { output.write("DENIED\n".toByteArray()); output.flush(); return }
                output.write("PAIRED\n".toByteArray()); output.flush()
                when (readLine(input)) {
                    "STATUS" -> { output.write("MANW AGENT • الخدمة تعمل في الخلفية\n".toByteArray()); output.flush() }
                    "SCREEN" -> {
                        if (projection == null) { output.write("NO_PROJECTION\n".toByteArray()); output.flush(); return }
                        output.write("SCREEN_READY\n".toByteArray()); output.flush()
                        socket.soTimeout = 0
                        streaming.set(true)
                        streamOut = output
                        Thread {
                            try {
                                while (!socket.isClosed) {
                                    val command = readLine(input)
                                    if (command.isBlank()) break
                                    val parts = command.split("|", limit = 2)
                                    when (parts.firstOrNull()) {
                                        "TAP" -> if (parts.size == 3) ManwRemoteAccessibilityService.tap(parts[1].toFloatOrNull() ?: -1f, parts[2].toFloatOrNull() ?: -1f)
                                        "SWIPE" -> if (parts.size == 6) ManwRemoteAccessibilityService.swipe(
                                            parts[1].toFloatOrNull() ?: -1f, parts[2].toFloatOrNull() ?: -1f,
                                            parts[3].toFloatOrNull() ?: -1f, parts[4].toFloatOrNull() ?: -1f,
                                            parts[5].toLongOrNull() ?: 350L
                                        )
                                        "BACK" -> ManwRemoteAccessibilityService.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                                        "HOME" -> ManwRemoteAccessibilityService.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                                        "RECENTS" -> ManwRemoteAccessibilityService.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                                        "TYPE" -> if (parts.size == 2) ManwRemoteAccessibilityService.typeText(parts[1])
                                    }
                                }
                            } catch (_: Exception) { }
                        }.apply { name = "MANW-Input-Commands"; isDaemon = true; start() }
                        try { while (!socket.isClosed && streaming.get()) Thread.sleep(250) }
                        finally { streaming.set(false); streamOut = null }
                    }
                }
            } catch (_: Exception) { streaming.set(false); streamOut = null }
        }
    }

    private fun stopProjection() {
        streaming.set(false); streamOut = null
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        imageThread?.quitSafely(); imageThread = null
    }

    private fun stopAll() {
        stopProjection()
        runCatching { server?.close() }; server = null; worker = null
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 45821
        const val ACTION_STOP = "com.manw.agent.STOP"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val PREFS = "manw_agent"
        const val KEY_PIN = "pair_pin"
        private const val CHANNEL = "manw_agent_connection"
        private const val NOTIFICATION_ID = 45821
    }
}