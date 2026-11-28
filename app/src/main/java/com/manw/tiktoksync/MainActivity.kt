package com.manw.tiktoksync

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private val NeonRed = Color(0xFF35FF8A)
private val Dark = Color(0xFF08090D)
private val Panel = Color(0xFF151821)
private const val ACCESS_PASSWORD = "manw1231\$\$"
private const val SHIZUKU_REQUEST_CODE = 7412

private data class LaunchableApp(val packageName: String, val label: String)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("manw_app_hider", MODE_PRIVATE) }
    private var privilegedService: IAppHiderService? = null
    private var shizukuStatus by mutableStateOf("Shizuku غير متصل")

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, AppHiderService::class.java.name))
            .daemon(false)
            .processNameSuffix("app_hider")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            privilegedService = IAppHiderService.Stub.asInterface(service)
            shizukuStatus = if (privilegedService != null) "Shizuku متصل وجاهز" else "تعذر الاتصال بخدمة Shizuku"
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            privilegedService = null
            shizukuStatus = "اتصال Shizuku انقطع"
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) bindPrivilegedService()
            else shizukuStatus = "لم يتم منح إذن Shizuku"
        }
    }

    private fun installedLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        val matches = if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, flags)
        }
        return matches.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == packageName) return@mapNotNull null
            LaunchableApp(pkg, info.loadLabel(packageManager).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private fun connectShizuku() {
        try {
            if (Shizuku.isPreV11()) {
                shizukuStatus = "إصدار Shizuku قديم. حدّث Shizuku أولًا."
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindPrivilegedService()
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                shizukuStatus = "وافق على طلب الإذن من Shizuku"
            }
        } catch (e: Throwable) {
            shizukuStatus = "افتح Shizuku وشغّله أولًا، ثم حاول الاتصال."
        }
    }

    private fun bindPrivilegedService() {
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            shizukuStatus = "جارٍ الاتصال بخدمة Shizuku..."
        } catch (e: Throwable) {
            shizukuStatus = "فشل الاتصال: ${e.message ?: "خطأ غير معروف"}"
        }
    }

    private fun changePackageState(app: LaunchableApp, enable: Boolean, onDone: (Boolean, String) -> Unit) {
        val service = privilegedService
        if (service == null) {
            onDone(false, "اتصل بـ Shizuku وامنحه الإذن أولًا.")
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { service.setPackageEnabled(app.packageName, enable) }
                    .getOrElse { "تعذر تنفيذ العملية: ${it.message}" }
            }
            val success = result.startsWith("OK:")
            onDone(success, if (success) (if (enable) "تم إظهار ${app.label}" else "تم إخفاء ${app.label}") else result)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = NeonRed, background = Dark, surface = Panel,
                onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White
            )) {
                var unlocked by remember { mutableStateOf(false) }
                var password by remember { mutableStateOf("") }
                var passwordError by remember { mutableStateOf("") }
                var apps by remember { mutableStateOf(installedLaunchableApps()) }
                val hidden = remember {
                    mutableStateListOf<String>().apply {
                        addAll(prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet())
                    }
                }
                var secretPanel by remember { mutableStateOf(false) }
                var titleTaps by remember { mutableIntStateOf(0) }
                var infoMessage by remember { mutableStateOf("") }

                fun saveHidden() {
                    prefs.edit().putStringSet("hidden_packages", hidden.toSet()).apply()
                }

                Surface(Modifier.fillMaxSize(), color = Dark) {
                    if (!unlocked) {
                        Column(
                            Modifier.fillMaxSize().padding(28.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("☠  MANW", color = NeonRed, fontSize = 34.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(10.dp))
                            Text("أدخل كلمة المرور للمتابعة", color = Color.White)
                            Spacer(Modifier.height(18.dp))
                            OutlinedTextField(
                                value = password, onValueChange = { password = it; passwordError = "" },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("كلمة المرور") },
                                visualTransformation = PasswordVisualTransformation()
                            )
                            if (passwordError.isNotBlank()) Text(passwordError, color = NeonRed)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = {
                                if (password == ACCESS_PASSWORD) unlocked = true
                                else passwordError = "كلمة المرور غير صحيحة"
                            }, modifier = Modifier.fillMaxWidth()) { Text("دخول") }
                        }
                    } else {
                        Box(Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color(0xFF020806), Color(0xFF07130F), Dark))
                        )) {
                            Canvas(Modifier.fillMaxSize()) {
                                var x = 8f
                                while (x < size.width) {
                                    drawLine(Color(0x1835FF8A), Offset(x, 0f), Offset(x, size.height), 1f)
                                    x += 34f
                                }
                                var y = 18f
                                while (y < size.height) {
                                    drawLine(Color(0x1235FF8A), Offset(0f, y), Offset(size.width, y), 1f)
                                    y += 34f
                                }
                            }
                            Text(
                                "𝑴𝑨𝑵𝑾<𝑨𝑯𝑴𝑬𝑫>𓄿",
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                                color = Color(0x2235FF8A), fontSize = 24.sp, fontWeight = FontWeight.Black
                            )
                            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "ꪑꪖ᭢᭙👊<ꪖꫝꪑꫀᦔ>",
                                    modifier = Modifier.clickable {
                                        titleTaps++
                                        if (titleTaps >= 5) {
                                            secretPanel = true
                                            titleTaps = 0
                                        }
                                    },
                                    color = Color(0xFF35FF8A), fontSize = 25.sp, fontWeight = FontWeight.Black
                                )
                                if (secretPanel) {
                                    Text("PRIVATE CONTROL", color = Color.LightGray, fontSize = 11.sp)
                                    Button(onClick = { secretPanel = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text("رجوع")
                                    }
                                    val hiddenApps = hidden.map { pkg ->
                                        LaunchableApp(pkg, prefs.getString("label_$pkg", pkg) ?: pkg)
                                    }
                                    if (hiddenApps.isEmpty()) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text("لا توجد عناصر", color = Color.Gray)
                                        }
                                    } else {
                                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(hiddenApps, key = { it.packageName }) { app ->
                                                Row(Modifier.fillMaxWidth().background(Panel.copy(alpha = 0.96f), RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                        Text(app.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                                                        Text(app.packageName, color = Color.Gray, fontSize = 10.sp)
                                                    }
                                                    OutlinedButton(onClick = {
                                                        hidden.remove(app.packageName)
                                                        saveHidden()
                                                        apps = installedLaunchableApps()
                                                        infoMessage = "تمت إعادة التطبيق إلى القائمة"
                                                    }) { Text("إرجاع") }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text("التطبيقات", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    val visibleApps = apps.filter { it.packageName !in hidden }
                                    if (visibleApps.isEmpty()) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text("لا توجد تطبيقات", color = Color.Gray)
                                        }
                                    } else {
                                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(visibleApps, key = { it.packageName }) { app ->
                                                Row(
                                                    Modifier.fillMaxWidth()
                                                        .background(Panel.copy(alpha = 0.96f), RoundedCornerShape(14.dp))
                                                        .combinedClickable(
                                                            onClick = { },
                                                            onLongClick = {
                                                                if (!hidden.contains(app.packageName)) {
                                                                    hidden.add(app.packageName)
                                                                    prefs.edit().putString("label_${app.packageName}", app.label).apply()
                                                                    saveHidden()
                                                                    infoMessage = "تم وضع ${app.label} في الأمان"
                                                                }
                                                            }
                                                        )
                                                        .padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(app.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                                                        Text("اضغط مطولاً لوضعه في الأمان", color = Color.Gray, fontSize = 10.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (infoMessage.isNotBlank()) Text(infoMessage, color = Color(0xFF9BE7A5), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
        } catch (_: Throwable) { }
        super.onDestroy()
    }
}
