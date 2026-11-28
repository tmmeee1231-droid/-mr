package com.manw.controller

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private val Red = Color(0xFFFF2638)
private val Bg = Color(0xFF070709)
private val Panel = Color(0xFF171014)

class MainActivity : ComponentActivity() {
    private var streamSocket: Socket? = null
    private val streaming = AtomicBoolean(false)
    override fun onDestroy() { stopScreen(); super.onDestroy() }

    private fun stopScreen() {
        streaming.set(false)
        runCatching { streamSocket?.close() }
        streamSocket = null
    }
    private fun readLine(input: java.io.InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 256) {
            val b = input.read()
            if (b < 0 || b == 10) break
            if (b != 13) bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var ip by remember { mutableStateOf("") }
            var pin by remember { mutableStateOf("") }
            var status by remember { mutableStateOf("DISCONNECTED") }
            var busy by remember { mutableStateOf(false) }
            var screen by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Panel)) {
                Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("MANW", color = Red, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Text("CONTROLLER // LOCAL SCREEN SESSION", color = Color.LightGray, fontSize = 11.sp)
                    OutlinedTextField(ip, { ip = it }, label = { Text("عنوان IP للهاتف الآخر") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("رمز الربط (6 أرقام)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (ip.isBlank() || pin.length != 6 || busy) { status = "أدخل IP والرمز بشكل صحيح"; return@Button }
                            busy = true; status = "CONNECTING..."
                            Thread {
                                try {
                                    Socket().use { s ->
                                        s.connect(InetSocketAddress(ip.trim(), 45821), 5000); s.soTimeout = 0
                                        val input = s.getInputStream(); val out = s.getOutputStream()
                                        out.write("PAIR|$pin\nSCREEN\n".toByteArray()); out.flush()
                                        val reply = readLine(input)
                                        check(reply == "PAIRED") { if (reply == "DENIED") "رمز الربط غير صحيح" else "فشل التحقق: $reply" }
                                        val ready = readLine(input)
                                        check(ready == "SCREEN_READY") { if (ready == "NO_PROJECTION") "فعّل مشاركة الشاشة على الهاتف الآخر أولاً" else "الشاشة غير جاهزة: $ready" }
                                        runOnUiThread { busy = false; status = "SCREEN LIVE" }
                                        while (streaming.get() || !isFinishing) {
                                            val sizeBytes = ByteArray(4); var got = 0
                                            while (got < 4) { val n = input.read(sizeBytes, got, 4-got); if (n < 0) return@Thread; got += n }
                                            val size = ((sizeBytes[0].toInt() and 255) shl 24) or ((sizeBytes[1].toInt() and 255) shl 16) or ((sizeBytes[2].toInt() and 255) shl 8) or (sizeBytes[3].toInt() and 255)
                                            if (size !in 1..2_000_000) error("حجم إطار غير صالح")
                                            val bytes = ByteArray(size); var offset = 0
                                            while (offset < size) { val n = input.read(bytes, offset, size-offset); if (n < 0) return@Thread; offset += n }
                                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                                            runOnUiThread { screen = bitmap.asImageBitmap() }
                                        }
                                    }
                                } catch (e: Exception) { runOnUiThread { status = e.message ?: "انقطع الاتصال"; busy = false } }
                            }.start()
                        }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("عرض الشاشة") }
                        OutlinedButton(onClick = { stopScreen(); status = "STOPPED"; screen = null }) { Text("إيقاف", color = Red) }
                    }
                    Text(status, color = if (status == "SCREEN LIVE") Color(0xFF57E389) else Color.White)
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Box(Modifier.fillMaxSize().padding(6.dp)) {
                            if (screen != null) Image(screen!!, contentDescription = "مشاركة شاشة الهاتف", modifier = Modifier.fillMaxSize())
                            else Text("ستظهر شاشة الهاتف هنا بعد موافقة صاحبه", color = Color.Gray)
                        }
                    }
                    Text("اتصال محلي عبر Wi-Fi / Hotspot فقط. العرض لا يمنح التحكم باللمس بعد.", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}