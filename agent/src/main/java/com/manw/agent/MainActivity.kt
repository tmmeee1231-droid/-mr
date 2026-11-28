package com.manw.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

private val Red = Color(0xFFFF2638)
private val Bg = Color(0xFF070709)
private val Panel = Color(0xFF171014)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences(ManwAgentService.PREFS, MODE_PRIVATE) }
    private var pinValue: String = ""

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The user can still run the visible foreground service if notifications are denied;
        // Android keeps its foreground-service disclosure in system UI.
        startAgentService()
    }

    private fun startAgentService() {
        val intent = Intent(this, ManwAgentService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun localAddresses(): String = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
            .filter { !it.isLoopbackAddress && it is Inet4Address }.joinToString("  •  ") { it.hostAddress ?: "" }
    }.getOrDefault("افتح نقطة الاتصال ثم حدّث العنوان")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinValue = prefs.getString(ManwAgentService.KEY_PIN, null)
            ?: (100000 + SecureRandom().nextInt(900000)).toString().also {
                prefs.edit().putString(ManwAgentService.KEY_PIN, it).apply()
            }
        setContent {
            var listening by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf("الاستقبال متوقف") }
            var addresses by remember { mutableStateOf(localAddresses()) }
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Panel)) {
                Column(Modifier.fillMaxSize().background(Bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("MANW", color = Red, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("AGENT // BACKGROUND NODE", color = Color.LightGray, fontSize = 11.sp)
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("رمز الربط الخاص بهذا الجهاز", color = Color.LightGray)
                            Text(pinValue, color = Red, fontSize = 38.sp, fontWeight = FontWeight.Black)
                            Text("احتفظ بالرمز ولا تشاركه إلا مع جهازك الموثوق", color = Color.Gray, fontSize = 11.sp)
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
                            message = "الخدمة تعمل في الخلفية بإشعار ظاهر"
                            listening = true
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else startAgentService()
                        } else {
                            startService(Intent(this@MainActivity, ManwAgentService::class.java).setAction(ManwAgentService.ACTION_STOP))
                            listening = false
                            message = "تم إيقاف خدمة MANW"
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                        Text(if (listening) "إيقاف الخدمة" else "تشغيل الخدمة بالخلفية")
                    }
                    Text(message, color = if (listening) Color(0xFF57E389) else Color.LightGray)
                    Spacer(Modifier.weight(1f))
                    Text("تبقى الخدمة نشطة بإشعار واضح حتى توقفها. قد يقيّد النظام عملها عند تفعيل توفير الطاقة.", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}