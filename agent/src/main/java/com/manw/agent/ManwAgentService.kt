package com.manw.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.net.ServerSocket
import java.security.SecureRandom

class ManwAgentService : Service() {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "MANW اتصال محلي", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("MANW Agent نشط")
            .setContentText("استقبال اتصال محلي ظاهر للمستخدم • اضغط إيقاف من التطبيق")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (worker == null || worker?.isAlive != true) startServer()
        return START_STICKY
    }

    private fun startServer() {
        worker = Thread {
            try {
                ServerSocket(PORT).use { listener ->
                    server = listener
                    while (!listener.isClosed) {
                        val socket = listener.accept()
                        Thread {
                            socket.use { client ->
                                runCatching {
                                    client.soTimeout = 15000
                                    val input = client.getInputStream().bufferedReader()
                                    val output = client.getOutputStream().bufferedWriter()
                                    val request = input.readLine() ?: return@runCatching
                                    val expected = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PIN, "") ?: ""
                                    if (request == "PAIR|$expected") {
                                        output.write("PAIRED\n"); output.flush()
                                        if (input.readLine() == "STATUS") {
                                            output.write("MANW AGENT • الخدمة تعمل في الخلفية\n"); output.flush()
                                        }
                                    } else {
                                        output.write("DENIED\n"); output.flush()
                                    }
                                }
                            }
                        }.start()
                    }
                }
            } catch (_: Exception) {
                // Socket closes when the user stops the foreground service.
            } finally {
                server = null
            }
        }.apply { name = "MANW-Local-Agent"; start() }
    }

    private fun stopServer() {
        runCatching { server?.close() }
        server = null
        worker = null
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 45821
        const val ACTION_STOP = "com.manw.agent.STOP"
        const val PREFS = "manw_agent"
        const val KEY_PIN = "pair_pin"
        private const val CHANNEL = "manw_agent_connection"
        private const val NOTIFICATION_ID = 45821
    }
}