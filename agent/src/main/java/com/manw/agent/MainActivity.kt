package com.manw.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(this, ManwAgentService::class.java)
                .putExtra(ManwAgentService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ManwAgentService.EXTRA_PROJECTION_DATA, result.data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startAgentService() }

    private fun startAgentService() {
        val intent = Intent(this, ManwAgentService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
    private fun requestScreenShare() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    private fun localAddresses(): String = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
            .filter { !it.isLoopbackAddress && it is Inet4Address }.joinToString("  •  ") { it.hostAddress ?: "" }
    }.getOrDefault("افتح نقطة الاتصال ثم حدّث العنوان")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinValue = prefs.getString(ManwAgentService.KEY_PIN, null)
            ?: (100000 + SecureRandom().nextInt(900000)).toString().also { prefs.edit().putString(ManwAgentService.KEY_PIN, it).apply() }
        setContent {
            var listening by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf("الاستقبال متوقف") }
            var addresses by remember { mutableStateOf(localAddresses()) }
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Panel)) {
                Column(Modifier.fillMaxSize().background(Bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("MANW", color = Red, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("AGENT // CONSENT-BASED SCREEN SHARE", color = Color.LightGray, fontSize = 11.sp)
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("رمز الربط الخاص بهذا الجهاز", color = Color.LightGray)
                            Text(pinValue, color = Red, fontSize = 38.sp, fontWeight = FontWeight.Black)
                            Text("لا تشارك الرمز إلا مع جهاز موثوق", color = Color.Gray, fontSize = 11.sp)
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
                            message = "الاستقبال المحلي يعمل بإشعار ظاهر"
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else startAgentService()
                        } else {
                            startService(Intent(this@MainActivity, ManwAgentService::class.java).setAction(ManwAgentService.ACTION_STOP))
                            listening = false; message = "تم إيقاف الخدمة ومشاركة الشاشة"
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                        Text(if (listening) "إيقاف الخدمة" else "تشغيل الاستقبال المحلي")
                    }
                    OutlinedButton(onClick = { requestScreenShare() }, modifier = Modifier.fillMaxWidth()) {
                        Text("السماح بمشاركة الشاشة", color = Red)
                    }
                    Text("مشاركة الشاشة لا تبدأ إلا بعد موافقتك في نافذة أندرويد. يظهر إشعار دائم ويمكنك الإيقاف في أي وقت.", color = Color.Gray, fontSize = 11.sp)
                    Text(message, color = Color(0xFF57E389))
                }
            }
        }
    }
}