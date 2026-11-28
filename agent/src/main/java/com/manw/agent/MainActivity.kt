package com.manw.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.ServerSocket
import java.net.Socket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

private val Red = Color(0xFFFF2638)
private val Bg = Color(0xFF070709)
private val Panel = Color(0xFF171014)

class MainActivity : ComponentActivity() {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var serverThread: Thread? = null

    private fun localAddresses(): String = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
            .filter { !it.isLoopbackAddress && it is Inet4Address }.joinToString("  •  ") { it.hostAddress ?: "" }
    }.getOrDefault("افتح نقطة الاتصال ثم أعد تشغيل الاستقبال")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pin = (100000 + SecureRandom().nextInt(900000)).toString()
        setContent {
            var listening by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf("الاستقبال متوقف") }
            var addresses by remember { mutableStateOf(localAddresses()) }
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Panel)) {
                Column(Modifier.fillMaxSize().background(Bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("MANW", color = Red, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("AGENT // DEVICE NODE", color = Color.LightGray, fontSize = 11.sp)
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("رمز الربط الخاص بهذا الجهاز", color = Color.LightGray)
                            Text(pin, color = Red, fontSize = 38.sp, fontWeight = FontWeight.Black)
                            Text("لا تشارك الرمز إلا مع جهازك الموثوق", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("عنوان الشبكة المحلية", color = Red, fontWeight = FontWeight.Bold)
                            Text(addresses, color = Color.White)
                            OutlinedButton(onClick = { addresses = localAddresses() }) { Text("تحديث العنوان", color = Red) }
                        }
                    }
                    Button(onClick = {
                        if (!listening) {
                            listening = true
                            message = "بانتظار اتصال MANW Controller..."
                            serverThread = Thread {
                                runCatching {
                                    ServerSocket(45821).use { ss ->
                                        server = ss
                                        while (!ss.isClosed) {
                                            val client = ss.accept()
                                            Thread {
                                                client.use { s ->
                                                    s.soTimeout = 10000
                                                    val input = s.getInputStream().bufferedReader()
                                                    val output = s.getOutputStream().bufferedWriter()
                                                    val request = input.readLine() ?: return@Thread
                                                    if (request == "PAIR|$pin") {
                                                        output.write("PAIRED\n"); output.flush()
                                                        if (input.readLine() == "STATUS") {
                                                            output.write("MANW AGENT • متصل وجاهز على هذا الجهاز\n"); output.flush()
                                                        }
                                                        runOnUiThread { message = "تم اتصال جهاز موثوق بنجاح" }
                                                    } else {
                                                        output.write("DENIED\n"); output.flush()
                                                    }
                                                }
                                            }.start()
                                        }
                                    }
                                }.onFailure { runOnUiThread { message = "تعذر تشغيل الاستقبال: ${it.message}" } }
                                runOnUiThread { listening = false; server = null }
                            }.apply { start() }
                        } else {
                            runCatching { server?.close() }
                            listening = false
                            message = "تم إيقاف الاستقبال"
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                        Text(if (listening) "إيقاف الاستقبال" else "تشغيل الاستقبال")
                    }
                    Text(message, color = if (listening) Color(0xFF57E389) else Color.LightGray)
                    Spacer(Modifier.weight(1f))
                    Text("يعمل أثناء فتح هذه الشاشة فقط في هذه النسخة الأولية", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { server?.close() }
        super.onDestroy()
    }
}