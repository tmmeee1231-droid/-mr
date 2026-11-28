package com.manw.tiktoksync

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

private val Neon = Color(0xFFFF202A)
private val NeonDark = Color(0xFF8D080D)
private val Black = Color(0xFF050506)
private val CardBlack = Color(0xFF100D0E)
private val Muted = Color(0xFFB8AEB0)

private data class LaunchableApp(val packageName: String, val label: String)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("manw_app_hider", MODE_PRIVATE) }

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

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Neon, secondary = Neon, background = Black, surface = CardBlack,
                onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White
            )) {
                var page by remember { mutableStateOf("home") }
                var apps by remember { mutableStateOf(installedLaunchableApps()) }
                val hidden = remember {
                    mutableStateListOf<String>().apply {
                        addAll(prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet())
                    }
                }
                var secretPanel by remember { mutableStateOf(false) }
                var titleTaps by remember { mutableIntStateOf(0) }
                var search by remember { mutableStateOf("") }
                var message by remember { mutableStateOf("") }

                fun saveHidden() {
                    prefs.edit().putStringSet("hidden_packages", hidden.toSet()).apply()
                }

                @Composable
                fun NeonCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
                    Column(
                        modifier.shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Neon.copy(alpha = .22f), spotColor = Neon.copy(alpha = .35f))
                            .clip(RoundedCornerShape(20.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF1A0A0B), CardBlack)))
                            .padding(14.dp),
                        content = content
                    )
                }

                @Composable
                fun Header(compact: Boolean = false) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("MANW", color = Neon, fontSize = if (compact) 27.sp else 34.sp, fontWeight = FontWeight.Black)
                            Text("AHMED", color = Color.White, fontSize = if (compact) 17.sp else 21.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Text("☠", color = Neon, fontSize = if (compact) 42.sp else 54.sp)
                    }
                }

                @Composable
                fun NavBar() {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF090708), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(Triple("settings", "⚙", "الإعدادات"), Triple("home", "⌂", "الرئيسية"), Triple("apps", "▦", "التطبيقات")).forEach { item ->
                            val selected = page == item.first
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                    .background(if (selected) NeonDark.copy(alpha = .55f) else Color.Transparent)
                                    .combinedClickable(onClick = { page = item.first; secretPanel = false }),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(item.second, color = if (selected) Neon else Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                Text(item.third, color = if (selected) Neon else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF160506), Black, Color(0xFF090506))))) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        if (!secretPanel) Header(compact = page != "home")
                        when {
                            secretPanel -> {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("منطقة الأمان السرية", color = Neon, fontSize = 22.sp, fontWeight = FontWeight.Black)
                                    TextButton(onClick = { secretPanel = false }) { Text("رجوع", color = Neon) }
                                }
                                val safeApps = hidden.map { pkg -> LaunchableApp(pkg, prefs.getString("label_$pkg", pkg) ?: pkg) }
                                if (safeApps.isEmpty()) {
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("لا توجد تطبيقات في الأمان", color = Muted) }
                                } else {
                                    androidx.compose.foundation.lazy.LazyColumn(
                                        Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(safeApps, key = { it.packageName }) { app ->
                                            Row(Modifier.fillMaxWidth().background(CardBlack, RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(app.label, color = Color.White, fontWeight = FontWeight.Bold)
                                                    Text(app.packageName, color = Muted, fontSize = 10.sp)
                                                }
                                                OutlinedButton(onClick = {
                                                    hidden.remove(app.packageName); saveHidden(); apps = installedLaunchableApps()
                                                    message = "تم إرجاع التطبيق للقائمة"
                                                }) { Text("إرجاع", color = Neon) }
                                            }
                                        }
                                    }
                                }
                            }
                            page == "home" -> {
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    NeonCard(Modifier.fillMaxWidth()) {
                                        Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                                            Text("♛", color = Neon.copy(alpha = .65f), fontSize = 90.sp, modifier = Modifier.align(Alignment.TopCenter))
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("◉", color = Neon, fontSize = 74.sp, fontWeight = FontWeight.Black)
                                                Text("MANW", color = Neon, fontSize = 46.sp, fontWeight = FontWeight.Black)
                                                Text("AHMED", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                                                Text("SECURE • PRIVATE • CONTROL", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    NeonCard(Modifier.fillMaxWidth()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("⬡", color = Neon, fontSize = 44.sp)
                                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                                Text("أمان التطبيقات", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
                                                Text("نظّم التطبيقات داخل مساحة MANW", color = Muted, fontSize = 12.sp)
                                            }
                                            Text("❯", color = Neon, fontSize = 26.sp)
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Button(onClick = { page = "apps" }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = NeonDark), shape = RoundedCornerShape(14.dp)) {
                                            Text("استعراض التطبيقات", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        listOf(Triple("⚙", "الإعدادات", "settings"), Triple("▦", "جميع التطبيقات", "apps")).forEach { item ->
                                            NeonCard(Modifier.weight(1f).height(118.dp)) {
                                                Text(item.first, color = Neon, fontSize = 30.sp)
                                                Spacer(Modifier.height(8.dp))
                                                Text(item.second, color = Color.White, fontWeight = FontWeight.Bold)
                                                Text("فتح القسم  ›", color = Muted, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        listOf("ⓘ" to "حول التطبيق", "☆" to "تقييم التطبيق").forEach { item ->
                                            NeonCard(Modifier.weight(1f).height(90.dp)) {
                                                Text(item.first, color = Neon, fontSize = 25.sp)
                                                Text(item.second, color = Color.White, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                            page == "apps" -> {
                                Text("جميع التطبيقات الموجودة على جهازك", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = search, onValueChange = { search = it },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    placeholder = { Text("البحث عن تطبيق...", color = Muted) },
                                    leadingIcon = { Text("⌕", color = Neon, fontSize = 26.sp) },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Neon, unfocusedBorderColor = NeonDark,
                                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                                    )
                                )
                                Spacer(Modifier.height(10.dp))
                                Text("اضغط مطولاً على أي تطبيق لوضعه في الأمان", color = Muted, fontSize = 11.sp)
                                val visibleApps = apps.filter { it.packageName !in hidden && it.label.contains(search, true) }
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(4),
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    items(visibleApps, key = { it.packageName }) { app ->
                                        Column(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                                .background(Brush.verticalGradient(listOf(Color(0xFF21090B), Color(0xFF0C090A))))
                                                .combinedClickable(onClick = { }, onLongClick = {
                                                    if (app.packageName !in hidden) {
                                                        hidden.add(app.packageName)
                                                        prefs.edit().putString("label_${app.packageName}", app.label).apply()
                                                        saveHidden()
                                                        message = "تم وضع ${app.label} في الأمان"
                                                    }
                                                }).padding(vertical = 10.dp, horizontal = 3.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            val icon = remember(app.packageName) {
                                                runCatching { packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                                            }
                                            if (icon != null) {
                                                androidx.compose.foundation.Image(icon, contentDescription = app.label, modifier = Modifier.size(48.dp))
                                            } else {
                                                Text("▣", color = Neon, fontSize = 40.sp)
                                            }
                                            Spacer(Modifier.height(5.dp))
                                            Text(app.label, color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2, minLines = 2, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                            else -> {
                                Text("إعدادات التطبيق", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                                Text("تحكم كامل في خيارات MANW", color = Muted, fontSize = 13.sp)
                                Spacer(Modifier.height(14.dp))
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    listOf(
                                        Triple("♢", "أمان التطبيق", "إدارة التطبيقات الموجودة في منطقة الأمان"),
                                        Triple("♧", "إعدادات الإشعارات", "إدارة إشعارات التطبيق"),
                                        Triple("⚒", "المظهر والواجهة", "تصميم أسود وأحمر بطابع MANW"),
                                        Triple("▣", "الخصوصية", "خيارات الخصوصية داخل التطبيق"),
                                        Triple("ⓘ", "معلومات التطبيق", "MANW AHMED • الإصدار الحالي")
                                    ).forEach { item ->
                                        NeonCard(Modifier.fillMaxWidth()) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(item.first, color = Neon, fontSize = 34.sp)
                                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                                    Text(item.second, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                                    Text(item.third, color = Muted, fontSize = 11.sp)
                                                }
                                                Text("❯", color = Neon, fontSize = 23.sp)
                                            }
                                        }
                                    }
                                    OutlinedButton(onClick = { search = ""; message = "تمت إعادة تعيين العرض" }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Neon)) {
                                        Text("إعادة تعيين الإعدادات")
                                    }
                                }
                            }
                        }
                        if (message.isNotBlank()) {
                            Text(message, color = Color(0xFFFF8589), fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), textAlign = TextAlign.Center)
                        }
                        if (!secretPanel) NavBar()
                    }
                    if (!secretPanel) {
                        Text(
                            "ꪑꪖ᭢᭙👊<ꪖꫝꪑꫀᦔ>",
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 18.dp)
                                .combinedClickable(onClick = {
                                    titleTaps++
                                    if (titleTaps >= 5) { secretPanel = true; titleTaps = 0 }
                                }, onLongClick = { }),
                            color = Color.Transparent, fontSize = 1.sp
                        )
                    }
                }
            }
        }
    }
}
