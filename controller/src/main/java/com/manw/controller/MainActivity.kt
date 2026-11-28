package com.manw.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Socket

private val Red = Color(0xFFFF2638)
private val Bg = Color(0xFF070709)
private val Panel = Color(0xFF171014)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var ip by remember { mutableStateOf("") }
            var pin by remember { mutableStateOf("") }
            var status by remember { mutableStateOf("DISCONNECTED") }
            var busy by remember { mutableStateOf(false) }
            var deviceInfo by remember { mutableStateOf("لا يوجد جهاز مرتبط") }
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Panel)) {
                Column(Modifier.fillMaxSize().background(Bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("MANW", color = Red, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("CONTROLLER // LOCAL NETWORK", color = Color.LightGray, fontSize = 11.sp)
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("ربط جهاز جديد", color = Red, fontWeight = FontWeight.Bold)
                            OutlinedTextField(ip, { ip = it }, label = { Text("عنوان IP للجهاز الآخر") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("رمز الربط المكوّن من 6 أرقام") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                            Button(onClick = {
                                if (ip.isBlank() || pin.length != 6) { status = "أدخل العنوان والرمز بشكل صحيح"; return@Button }
                                busy = true
                                status = "CONNECTING..."
                                Thread {
                                    val result = runCatching {
                                        Socket().use { socket ->
                                            socket.connect(java.net.InetSocketAddress(ip.trim(), 45821), 5000)
                                            socket.soTimeout = 5000
                                            val out = socket.getOutputStream().bufferedWriter()
                                            val input = socket.getInputStream().bufferedReader()
                                            out.write("PAIR|$pin\n"); out.flush()
                                            val reply = input.readLine() ?: error("لا يوجد رد من الجهاز")
                                            check(reply == "PAIRED") { "رمز الربط غير صحيح" }
                                            out.write("STATUS\n"); out.flush()
                                            input.readLine() ?: "تم الربط، لكن لم تصل الحالة"
                                        }
                                    }
                                    runOnUiThread {
                                        busy = false
                                        result.onSuccess { deviceInfo = it; status = "CONNECTED" }
                                            .onFailure { status = it.message ?: "فشل الاتصال" }
                                    }
                                }.start()
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                                Text(if (busy) "جاري الاتصال..." else "اتصال آمن")
                            }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("LINK STATUS", color = Red, fontWeight = FontWeight.Bold)
                            Text(status, color = if (status == "CONNECTED") Color(0xFF57E389) else Color.White, fontSize = 18.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(deviceInfo, color = Color.LightGray)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("الاتصال محلي عبر Wi-Fi / Hotspot • لا يحتاج إنترنت", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}