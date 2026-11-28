package com.manw.controller

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private val Red = Color(0xFFFF2638)
private val Bg = Color(0xFF070709)
private val Panel = Color(0xFF171014)

class MainActivity : ComponentActivity() {
    @Volatile private var streamSocket: Socket? = null
    @Volatile private var commandOutput: OutputStream? = null
    private val streaming = AtomicBoolean(false)

    private fun applyImmersiveMode(enabled: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        applyImmersiveMode(false)
        stopScreen()
        super.onDestroy()
    }

    private fun stopScreen() {
        streaming.set(false)
        runCatching { streamSocket?.close() }
        streamSocket = null
        commandOutput = null
    }

    private fun sendInput(command: String) {
        runCatching {
            val output = commandOutput ?: return
            synchronized(output) {
                output.write((command + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
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
            var fullscreen by remember { mutableStateOf(false) }
            var connected by remember { mutableStateOf(false) }
            var screen by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            var textToSend by remember { mutableStateOf("") }

            fun connectScreen() {
                if (ip.isBlank() || pin.length != 6 || busy) {
                    status = "أدخل IP والرمز المكوّن من 6 أرقام"
                    return
                }
                busy = true
                status = "CONNECTING..."
                streaming.set(true)
                Thread {
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(ip.trim(), 45821), 5000)
                            socket.soTimeout = 0
                            streamSocket = socket
                            val input = socket.getInputStream()
                            val output = socket.getOutputStream()
                            commandOutput = output
                            output.write("PAIR|$pin\nSCREEN\n".toByteArray(Charsets.UTF_8))
                            output.flush()
                            val paired = readLine(input)
                            check(paired == "PAIRED") { if (paired == "DENIED") "رمز الربط غير صحيح" else "فشل الربط: $paired" }
                            val ready = readLine(input)
                            check(ready == "SCREEN_READY") { if (ready == "NO_PROJECTION") "وافق على مشاركة الشاشة في الهاتف الآخر أولاً" else "الشاشة غير جاهزة: $ready" }
                            runOnUiThread { busy = false; connected = true; status = "SCREEN LIVE" }
                            while (!socket.isClosed) {
                                val header = ByteArray(4)
                                var got = 0
                                while (got < 4) {
                                    val n = input.read(header, got, 4 - got)
                                    if (n < 0) return@Thread
                                    got += n
                                }
                                val size = ((header[0].toInt() and 255) shl 24) or
                                    ((header[1].toInt() and 255) shl 16) or
                                    ((header[2].toInt() and 255) shl 8) or
                                    (header[3].toInt() and 255)
                                if (size !in 1..2_000_000) error("إطار شاشة غير صالح")
                                val bytes = ByteArray(size)
                                var offset = 0
                                while (offset < size) {
                                    val n = input.read(bytes, offset, size - offset)
                                    if (n < 0) return@Thread
                                    offset += n
                                }
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                                runOnUiThread { screen = bitmap.asImageBitmap() }
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { status = e.message ?: "انقطع الاتصال"; connected = false; busy = false }
                    } finally {
                        streaming.set(false)
                        commandOutput = null
                        streamSocket = null
                    }
                }.start()
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Panel)) {
                if (fullscreen && connected) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        screen?.let { bitmap ->
                            Image(bitmap, contentDescription = "شاشة الهاتف البعيد",
                                modifier = Modifier.fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTapGestures { p ->
                                            sendInput("TAP|"+(p.x/size.width)+"|"+(p.y/size.height))
                                        }
                                    }
                                    .pointerInput(Unit) {
                                        var start = Offset.Zero
                                        var end = Offset.Zero
                                        detectDragGestures(
                                            onDragStart = { start = it; end = it },
                                            onDrag = { change, _ -> end = change.position },
                                            onDragEnd = {
                                                sendInput("SWIPE|"+(start.x/size.width)+"|"+(start.y/size.height)+"|"+(end.x/size.width)+"|"+(end.y/size.height)+"|400")
                                            }
                                        )
                                    },
                                contentScale = ContentScale.FillBounds
                            )
                        }
                        Row(Modifier.align(androidx.compose.ui.Alignment.TopCenter).fillMaxWidth()
                            .background(Color(0xCC09090B)).padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { sendInput("BACK") }) { Text("رجوع", color = Color.White) }
                            TextButton(onClick = { sendInput("HOME") }) { Text("الرئيسية", color = Color.White) }
                            TextButton(onClick = { sendInput("RECENTS") }) { Text("التطبيقات", color = Color.White) }
                            TextButton(onClick = { fullscreen = false; applyImmersiveMode(false) }) { Text("خروج", color = Red) }
                        }
                        Text("MANW • LIVE", Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                            .background(Color(0xAA09090B)).padding(8.dp), color = Color.White, fontSize = 10.sp)
                    }
                } else {
                    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("MANW", color = Red, fontSize = 32.sp, fontWeight = FontWeight.Black)
                        Text("CONTROLLER // FULL REMOTE VIEW", color = Color.LightGray, fontSize = 11.sp)
                        OutlinedTextField(ip, { ip = it }, label = { Text("عنوان IP للهاتف الآخر") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("رمز الربط (6 أرقام)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { connectScreen() }, enabled = !busy && !connected,
                                colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text(if (busy) "جاري الاتصال" else "فتح الشاشة") }
                            OutlinedButton(onClick = { stopScreen(); connected = false; busy = false; screen = null; status = "STOPPED" }) { Text("إيقاف", color = Red) }
                            if (connected) OutlinedButton(onClick = { fullscreen = true; applyImmersiveMode(true) }) { Text("ملء الشاشة", color = Red) }
                        }
                        Text(status, color = if (connected) Color(0xFF57E389) else Color.White)
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Box(Modifier.fillMaxSize().padding(4.dp)) {
                                if (screen != null) {
                                    Image(screen!!, contentDescription = "شاشة الهاتف",
                                        modifier = Modifier.fillMaxSize()
                                            .pointerInput(Unit) {
                                                detectTapGestures { p -> sendInput("TAP|"+(p.x/size.width)+"|"+(p.y/size.height)) }
                                            }
                                            .pointerInput(Unit) {
                                                var start = Offset.Zero
                                                var end = Offset.Zero
                                                detectDragGestures(onDragStart = { start = it; end = it },
                                                    onDrag = { change, _ -> end = change.position },
                                                    onDragEnd = { sendInput("SWIPE|"+(start.x/size.width)+"|"+(start.y/size.height)+"|"+(end.x/size.width)+"|"+(end.y/size.height)+"|400") })
                                            }, contentScale = ContentScale.FillBounds)
                                } else Text("ستظهر الشاشة هنا بعد موافقة صاحب الهاتف", color = Color.Gray)
                            }
                        }
                        OutlinedTextField(textToSend, { textToSend = it }, label = { Text("اكتب نصًا لإرساله للهاتف") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { if (textToSend.isNotEmpty()) { sendInput("TYPE|"+textToSend); textToSend = "" } },
                            enabled = connected && textToSend.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("إرسال النص") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { sendInput("BACK") }, enabled = connected) { Text("رجوع") }
                            OutlinedButton(onClick = { sendInput("HOME") }, enabled = connected) { Text("الرئيسية") }
                            OutlinedButton(onClick = { sendInput("RECENTS") }, enabled = connected) { Text("التطبيقات") }
                        }
                        Text("اللمس والسحب يعملان بعد تفعيل خدمة إمكانية الوصول على الهاتف الهدف.", color = Color.Gray, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}