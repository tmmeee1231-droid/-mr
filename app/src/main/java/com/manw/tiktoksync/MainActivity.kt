package com.manw.tiktoksync

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import java.security.MessageDigest
import android.app.role.RoleManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
                var themeIndex by remember { mutableIntStateOf(prefs.getInt("theme_index", 0)) }
                var wallpaperIndex by remember { mutableIntStateOf(prefs.getInt("wallpaper_index", 0)) }
                var iconStyle by remember { mutableIntStateOf(prefs.getInt("icon_style", 0)) }
                val Neon = listOf(Color(0xFFFF202A), Color(0xFF00D9FF), Color(0xFFB56BFF), Color(0xFF00E676), Color(0xFFFFB300))[themeIndex.coerceIn(0,4)]
                val NeonDark = listOf(Color(0xFF8D080D), Color(0xFF07536A), Color(0xFF542B78), Color(0xFF075A37), Color(0xFF704600))[themeIndex.coerceIn(0,4)]
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
                var pendingApp by remember { mutableStateOf<LaunchableApp?>(null) }
                var passwordDialog by remember { mutableStateOf(false) }
                var passwordSetup by remember { mutableStateOf(false) }
                var passwordInput by remember { mutableStateOf("") }
                var passwordError by remember { mutableStateOf("") }
                val homeRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    message = if (Build.VERSION.SDK_INT >= 29 && getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)) "تم تعيين MANW كشاشة رئيسية" else "لم يتم تعيين MANW كشاشة رئيسية"
                }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val rm = getSystemService(RoleManager::class.java)
                        if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME) && !prefs.getBoolean("home_role_prompted", false)) {
                            prefs.edit().putBoolean("home_role_prompted", true).apply()
                            homeRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                        }
                    }
                }

                fun saveHidden() { prefs.edit().putStringSet("hidden_packages", hidden.toSet()).apply() }
                fun enterSecretArea() { passwordInput=""; passwordError=""; passwordSetup=prefs.getString("secret_password_hash",null).isNullOrBlank(); passwordDialog=true }
                fun passwordHash(value:String):String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

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
                            Text("MANW", modifier = Modifier.combinedClickable(onClick = { titleTaps++; if (titleTaps >= 5) { enterSecretArea(); titleTaps = 0 } }, onLongClick = { }), color = Neon, fontSize = if (compact) 27.sp else 34.sp, fontWeight = FontWeight.Black)
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

                Box(Modifier.fillMaxSize().background(when (wallpaperIndex) {
                    1 -> Brush.verticalGradient(listOf(Color(0xFF151B2D), Black, Color(0xFF080D18)))
                    2 -> Brush.verticalGradient(listOf(Color(0xFF21112B), Black, Color(0xFF0C0711)))
                    3 -> Brush.verticalGradient(listOf(Color(0xFF09251D), Black, Color(0xFF050D0A)))
                    else -> Brush.verticalGradient(listOf(Color(0xFF160506), Black, Color(0xFF090506)))
                })) {
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
                                            NeonCard(Modifier.weight(1f).height(118.dp).combinedClickable(onClick = { page = item.third })) {
                                                Text(item.first, color = Neon, fontSize = 30.sp)
                                                Spacer(Modifier.height(8.dp))
                                                Text(item.second, color = Color.White, fontWeight = FontWeight.Bold)
                                                Text("فتح القسم  ›", color = Muted, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        listOf("ⓘ" to "حول التطبيق", "☆" to "تقييم التطبيق").forEach { item ->
                                            NeonCard(Modifier.weight(1f).height(90.dp).combinedClickable(onClick = { message = if (item.second == "حول التطبيق") "MANW AHMED • تطبيق الشاشة الرئيسية وإدارة التطبيقات داخل MANW" else "شكرًا لدعمك MANW" })) {
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
                                                .combinedClickable(onClick = {
                                                    packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) } ?: run { message = "تعذر فتح التطبيق" }
                                                }, onLongClick = { if (app.packageName !in hidden) pendingApp = app }).padding(vertical = 10.dp, horizontal = 3.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            val icon = remember(app.packageName) {
                                                runCatching { packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                                            }
                                            Box(Modifier.size(54.dp).clip(when (iconStyle) { 1 -> CircleShape; 2 -> RoundedCornerShape(8.dp); else -> RoundedCornerShape(15.dp) }).background(if (iconStyle == 0) Color.Transparent else Neon.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                                if (icon != null) androidx.compose.foundation.Image(icon, contentDescription = app.label, modifier = Modifier.size(48.dp).clip(if (iconStyle == 1) CircleShape else RoundedCornerShape(11.dp)))
                                                else Text("▣", color = Neon, fontSize = 40.sp)
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
                                    NeonCard(Modifier.fillMaxWidth()) {
                                        Text("ألوان الثيم", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            listOf(Color(0xFFFF202A), Color(0xFF00D9FF), Color(0xFFB56BFF), Color(0xFF00E676), Color(0xFFFFB300)).forEachIndexed { i, color ->
                                                Box(Modifier.size(40.dp).clip(CircleShape).background(color).combinedClickable(onClick = { themeIndex=i; prefs.edit().putInt("theme_index",i).apply() }), contentAlignment = Alignment.Center) { if(themeIndex==i) Text("✓",color=Color.Black) }
                                            }
                                        }
                                    }
                                    NeonCard(Modifier.fillMaxWidth()) {
                                        Text("الخلفيات", color = Color.White, fontWeight = FontWeight.Bold)
                                        listOf("أحمر داكن","أزرق ليلي","بنفسجي","أخضر").forEachIndexed { i, label ->
                                            Row(Modifier.fillMaxWidth().combinedClickable(onClick={ wallpaperIndex=i; prefs.edit().putInt("wallpaper_index",i).apply() }), verticalAlignment=Alignment.CenterVertically) {
                                                Text(label,color=Color.White,modifier=Modifier.weight(1f))
                                                RadioButton(selected=wallpaperIndex==i,onClick={wallpaperIndex=i;prefs.edit().putInt("wallpaper_index",i).apply()})
                                            }
                                        }
                                    }
                                    NeonCard(Modifier.fillMaxWidth()) {
                                        Text("شكل الأيقونات",color=Color.White,fontWeight=FontWeight.Bold)
                                        listOf("مربعة ناعمة","دائرية","إطار مستدير").forEachIndexed { i,label ->
                                            Row(Modifier.fillMaxWidth().combinedClickable(onClick={iconStyle=i;prefs.edit().putInt("icon_style",i).apply()}),verticalAlignment=Alignment.CenterVertically) {
                                                Text(label,color=Color.White,modifier=Modifier.weight(1f))
                                                RadioButton(selected=iconStyle==i,onClick={iconStyle=i;prefs.edit().putInt("icon_style",i).apply()})
                                            }
                                        }
                                    }
                                    NeonCard(Modifier.fillMaxWidth()) {
                                        Text("الشاشة الرئيسية",color=Color.White,fontWeight=FontWeight.Bold)
                                        Button(onClick={
                                            if(Build.VERSION.SDK_INT>=29){ val rm=getSystemService(RoleManager::class.java); if(rm.isRoleAvailable(RoleManager.ROLE_HOME)) homeRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
                                            else startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                                        },modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=NeonDark)){Text("اختيار MANW كشاشة رئيسية")}
                                    }
                                    NeonCard(Modifier.fillMaxWidth()) {
                                        Text("الأمان والخصوصية",color=Color.White,fontWeight=FontWeight.Bold)
                                        Text("التطبيقات في أمان MANW تختفي من قائمة MANW فقط.",color=Muted,fontSize=11.sp)
                                        OutlinedButton(onClick={enterSecretArea()},modifier=Modifier.fillMaxWidth()){Text("فتح منطقة الأمان",color=Neon)}
                                    }
                                    OutlinedButton(onClick={themeIndex=0;wallpaperIndex=0;iconStyle=0;prefs.edit().putInt("theme_index",0).putInt("wallpaper_index",0).putInt("icon_style",0).apply()},modifier=Modifier.fillMaxWidth()){Text("إعادة ضبط المظهر")}
                                }
                            }
                        }
                        if (message.isNotBlank()) {
                            Text(message, color = Color(0xFFFF8589), fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), textAlign = TextAlign.Center)
                        }
                        if (!secretPanel) NavBar()
                    }
                    if (passwordDialog) AlertDialog(onDismissRequest={passwordDialog=false},containerColor=CardBlack,title={Text(if(passwordSetup) "إنشاء كلمة مرور" else "كلمة مرور منطقة الأمان",color=Neon)},text={Column { OutlinedTextField(value=passwordInput,onValueChange={passwordInput=it;passwordError=""},singleLine=true,label={Text("كلمة المرور")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation()); if(passwordError.isNotBlank()) Text(passwordError,color=Neon) }},confirmButton={TextButton(onClick={if(passwordInput.length<4) passwordError="اكتب 4 أحرف أو أرقام على الأقل" else if(passwordSetup){prefs.edit().putString("secret_password_hash",passwordHash(passwordInput)).apply();passwordDialog=false;secretPanel=true} else if(passwordHash(passwordInput)==prefs.getString("secret_password_hash","")){passwordDialog=false;secretPanel=true}else passwordError="كلمة المرور غير صحيحة"}){Text(if(passwordSetup) "حفظ وفتح" else "دخول",color=Neon)}},dismissButton={TextButton(onClick={passwordDialog=false}){Text("إلغاء",color=Muted)}})
                    pendingApp?.let { selected ->
                        AlertDialog(onDismissRequest={pendingApp=null},containerColor=CardBlack,
                            title={Text("تأكيد وضع التطبيق في الأمان",color=Neon,fontWeight=FontWeight.Bold)},
                            text={Text("هل تريد وضع " + selected.label + " في أمان MANW؟ سيختفي من قائمة MANW فقط.",color=Color.White)},
                            confirmButton={TextButton(onClick={
                                hidden.add(selected.packageName)
                                prefs.edit().putString("label_" + selected.packageName,selected.label).apply()
                                saveHidden()
                                message="تم وضع " + selected.label + " في أمان MANW"
                                pendingApp=null
                            }){Text("وضع في الأمان",color=Neon)}},
                            dismissButton={TextButton(onClick={pendingApp=null}){Text("إلغاء",color=Muted)}}
                        )
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
