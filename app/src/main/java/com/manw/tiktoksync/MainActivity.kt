package com.manw.tiktoksync

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NeonRed = Color(0xFFFF2638)
private val Dark = Color(0xFF08090D)
private val Panel = Color(0xFF151821)

private data class LaunchableApp(val packageName: String, val label: String)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("manw_app_hider", MODE_PRIVATE) }

    private fun installedLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return matches.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == this.packageName) return@mapNotNull null
            LaunchableApp(packageName, info.loadLabel(packageManager).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = NeonRed, background = Dark, surface = Panel,
                onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White
            )) {
                var apps by remember { mutableStateOf(installedLaunchableApps()) }
                val hidden = remember {
                    mutableStateListOf<String>().apply {
                        addAll(prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet())
                    }
                }
                var showHidden by remember { mutableStateOf(false) }
                var infoMessage by remember { mutableStateOf("") }

                fun saveHidden() {
                    prefs.edit().putStringSet("hidden_packages", hidden.toSet()).apply()
                }

                val homeRoleLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    infoMessage = if (isDefaultHome()) "تم تعيين MANW كمشغّل افتراضي."
                    else "لم يتم اختيار MANW كمشغّل افتراضي."
                }

                Surface(Modifier.fillMaxSize(), color = Dark) {
                    Column(
                        Modifier.fillMaxSize().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("☠  MANW", color = NeonRed, fontSize = 30.sp, fontWeight = FontWeight.Black)
                        Text("APP HIDER  //  LAUNCHER", color = Color.LightGray, fontSize = 12.sp)
                        Text("اختر التطبيقات التي تريد إخفاءها من شاشة MANW الرئيسية ودرج تطبيقاتها.", color = Color.White)

                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 29) {
                                val roleManager = getSystemService(RoleManager::class.java)
                                if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                                    !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                                    homeRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                                } else infoMessage = "MANW هو المشغّل الافتراضي بالفعل."
                            } else homeRoleLauncher.launch(Intent("android.settings.HOME_SETTINGS"))
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("تعيين MANW كمشغّل افتراضي")
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showHidden, onClick = { showHidden = false }, label = { Text("التطبيقات") })
                            FilterChip(selected = showHidden, onClick = { showHidden = true }, label = { Text("المخفية (${hidden.size})") })
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { apps = installedLaunchableApps() }) { Text("تحديث") }
                        }

                        if (!isDefaultHome()) {
                            Text(
                                "لكي تختفي التطبيقات من درج التطبيقات والشاشة الرئيسية، اختر MANW كمشغّل افتراضي.",
                                color = Color(0xFFFFC857), fontSize = 12.sp
                            )
                        }
                        if (infoMessage.isNotBlank()) Text(infoMessage, color = Color(0xFF9BE7A5), fontSize = 12.sp)

                        val visibleApps = if (showHidden) apps.filter { it.packageName in hidden }
                                          else apps.filter { it.packageName !in hidden }

                        if (visibleApps.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text(if (showHidden) "لا توجد تطبيقات مخفية حاليًا" else "لم يتم العثور على تطبيقات", color = Color.Gray)
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(visibleApps, key = { it.packageName }) { app ->
                                    Row(
                                        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier.size(42.dp).background(Color(0xFF292D39), RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(app.label.take(1).uppercase(), color = NeonRed, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                        }
                                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                            Text(app.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                                            Text(app.packageName, color = Color.Gray, fontSize = 10.sp)
                                        }
                                        if (showHidden) {
                                            OutlinedButton(onClick = { hidden.remove(app.packageName); saveHidden() }) { Text("إظهار") }
                                        } else {
                                            Button(onClick = { hidden.add(app.packageName); saveHidden() }) { Text("إخفاء") }
                                            TextButton(onClick = {
                                                runCatching { packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) } }
                                            }) { Text("فتح") }
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "الإخفاء يطبّق داخل مشغّل MANW فقط. قد تظل التطبيقات ظاهرة في الإعدادات أو المشغّلات الأخرى.",
                            color = Color.Gray, fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }
}
