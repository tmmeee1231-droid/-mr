package com.manw.tiktoksync

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import android.widget.MediaController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedLink = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        } else ""
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFFF2638),
                onPrimary = Color.White,
                secondary = Color(0xFF9B1020),
                background = Color(0xFF050505),
                surface = Color(0xFF101010),
                onBackground = Color(0xFFF2F2F2),
                onSurface = Color(0xFFF2F2F2)
            )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF050505)) {
                    var unlocked by remember { mutableStateOf(false) }
                    if (!unlocked) {
                        AppLockScreen(onUnlock = { unlocked = it })
                    } else {
                        ManwScreen(
                            sharedLink = sharedLink,
                            onResolve = { link, onResult ->
                                lifecycleScope.launch {
                                    val result = withContext(Dispatchers.IO) { resolveVideoUrl(link) }
                                    onResult(result)
                                }
                            },
                            onDownload = { media, onStatus ->
                                val queued = enqueueVideoDownload(this@MainActivity, media.url, media.fileName)
                                onStatus(if (queued) "تم إرسال الفيديو إلى مدير التنزيلات. تابع الإشعار للتأكد من اكتمال الحفظ." else "تعذر بدء التحميل على هذا الجهاز.")
                            }
                        )
                    }
                }
            }
        }
    }
}

private const val APP_ACCESS_PASSWORD = "manw1231\$\$"

@Composable
private fun AppLockScreen(onUnlock: (Boolean) -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("☠", color = Color(0xFFFF2638), style = MaterialTheme.typography.displayLarge)
            Text("𝑀𝒜𝒩𝒲", color = Color(0xFFFF2638), style = MaterialTheme.typography.headlineLarge)
            Text("RESTRICTED SYSTEM", color = Color(0xFFB71C2C), style = MaterialTheme.typography.titleMedium)
            Text("أدخل كلمة المرور للمتابعة", color = Color(0xFFE0E0E0))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("كلمة مرور الدخول") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = {
                    if (password == APP_ACCESS_PASSWORD) onUnlock(true)
                    else error = "كلمة المرور غير صحيحة"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("فتح التطبيق") }
            if (error.isNotBlank()) {
                Text(error, color = Color(0xFFFF5262))
            }
            Text("⚠ ACCESS PROTECTED ⚠", color = Color(0xFF777777), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private data class ResolvedVideo(val url: String, val fileName: String)

private const val MANW_RESOLVER_URL = "https://manw-tiktok-resolver-production.up.railway.app/resolve"

private fun resolveVideoUrl(pageUrl: String): Result<ResolvedVideo> = runCatching {
    val parsed = Uri.parse(pageUrl)
    require(parsed.scheme == "https" || parsed.scheme == "http") {
        "أدخل رابطًا يبدأ بـ https:// أو http://"
    }
    require(!parsed.host.isNullOrBlank()) { "الرابط غير صحيح." }
    val host = parsed.host!!.lowercase(Locale.ROOT)
    require(host == "tiktok.com" || host.endsWith(".tiktok.com")) {
        "حاليًا خدمة الاستخراج تدعم روابط TikTok العامة."
    }

    val endpoint = URL("$MANW_RESOLVER_URL?url=" + java.net.URLEncoder.encode(pageUrl, "UTF-8"))
    val connection = (endpoint.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20000
        readTimeout = 300000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/json")
    }
    try {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = JSONObject(response.ifBlank { "{}" })
        if (status !in 200..299) {
            throw IllegalStateException(json.optString("error").ifBlank { "تعذر استخراج الفيديو (HTTP $status)." })
        }
        val direct = json.optString("download_url")
        require(direct.startsWith("https://") || direct.startsWith("http://")) {
            json.optString("error").ifBlank { "لم يتم العثور على فيديو عام في الرابط." }
        }
        val title = json.optString("title").replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim().replace(Regex("\\s+"), "_").take(50).ifBlank { "MANW_video" }
        val mediaUri = Uri.parse(direct)
        val extension = mediaUri.lastPathSegment?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in setOf("mp4", "m4v", "mov", "webm", "3gp", "mkv") } ?: "mp4"
        ResolvedVideo(direct, "$title.$extension")
    } finally {
        connection.disconnect()
    }
}

private fun enqueueVideoDownload(context: Context, url: String, fileName: String): Boolean = runCatching {
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(fileName)
        .setDescription("𝑀𝒜𝒩𝒲 - تنزيل الفيديو")
        .setMimeType("video/*")
        .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        .addRequestHeader("Referer", "https://www.tiktok.com/")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    manager.enqueue(request)
    true
}.getOrDefault(false)

@Composable
private fun ManwScreen(
    sharedLink: String,
    onResolve: (String, (Result<ResolvedVideo>) -> Unit) -> Unit,
    onDownload: (ResolvedVideo, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("manw_accounts", 0) }
    var handles by remember {
        mutableStateOf(runCatching {
            val array = JSONArray(prefs.getString("handles", "[]") ?: "[]")
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList()))
    }
    var selected by remember { mutableStateOf(handles.toSet()) }
    var newHandle by remember { mutableStateOf("") }
    var videoUrl by remember { mutableStateOf(sharedLink.trim()) }
    var preview by remember { mutableStateOf<ResolvedVideo?>(null) }
    var message by remember { mutableStateOf("ضع رابط الفيديو ثم اضغط «عرض الفيديو» لمعاينته قبل التنزيل.") }
    var resolving by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var titleText by remember { mutableStateOf(prefs.getString("app_title", "𝑀𝒜𝒩𝒲 تيك توك") ?: "𝑀𝒜𝒩𝒲 تيك توك") }
    var subtitleText by remember { mutableStateOf(prefs.getString("app_subtitle", "إدارة الحسابات وتنزيل الفيديوهات") ?: "إدارة الحسابات وتنزيل الفيديوهات") }
    var backgroundUri by remember { mutableStateOf(prefs.getString("background_uri", null)) }
    var adminOpen by remember { mutableStateOf(false) }
    var adminUnlocked by remember { mutableStateOf(false) }
    var adminPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var adminError by remember { mutableStateOf("") }
    var titleTaps by remember { mutableIntStateOf(0) }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            backgroundUri = uri.toString()
            prefs.edit().putString("background_uri", backgroundUri).apply()
            message = "تم تغيير الخلفية على هذا الجهاز."
        }
    }

    fun passwordHash(salt: String, value: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + value).toByteArray()).joinToString("") { "%02x".format(it) }

    fun persist(values: List<String>) {
        handles = values
        selected = selected.intersect(values.toSet())
        prefs.edit().putString("handles", JSONArray(values).toString()).apply()
    }

    val backgroundBitmap = remember(backgroundUri) {
        backgroundUri?.let { raw -> runCatching {
            context.contentResolver.openInputStream(Uri.parse(raw))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull() }
    }

    Box(Modifier.fillMaxSize()) {
        if (backgroundBitmap != null) {
            Image(bitmap = backgroundBitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(titleText, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.clickable {
            titleTaps++
            if (titleTaps >= 5) { adminOpen = true; titleTaps = 0; adminError = "" }
        })
        Text(subtitleText)

        OutlinedTextField(
            value = newHandle, onValueChange = { newHandle = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("اسم الحساب مثل @username") }, singleLine = true
        )
        Button(onClick = {
            val value = newHandle.trim().removePrefix("@")
            if (value.isNotBlank() && value !in handles) {
                persist(handles + value); newHandle = ""; message = "تمت إضافة الحساب."
            } else message = "اكتب اسم حساب جديد."
        }, modifier = Modifier.fillMaxWidth()) { Text("إضافة حساب") }

        Text("الحسابات المحفوظة (\${handles.size})", style = MaterialTheme.typography.titleMedium)
        Column {
            handles.forEach { handle ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = handle in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + handle else selected - handle
                        })
                        Text("@$handle", modifier = Modifier.weight(1f))
                        Button(onClick = { persist(handles - handle) }) { Text("حذف") }
                    }
                }
            }
        }

        Text("تنزيل فيديو من أي موقع", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = videoUrl, onValueChange = { videoUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ضع رابط الفيديو أو صفحة الفيديو") },
            placeholder = { Text("https://...") },
            singleLine = true
        )
        Button(
            onClick = {
                if (videoUrl.isBlank()) {
                    message = "ضع رابط الفيديو أولًا."
                } else {
                    resolving = true
                    preview = null
                    message = "جاري استخراج الفيديو..."
                    onResolve(videoUrl.trim()) { result ->
                        resolving = false
                        if (result.isSuccess) {
                            preview = result.getOrThrow()
                            message = "تم تجهيز المعاينة. شاهد الفيديو ثم اضغط تنزيل إذا أردت."
                        } else {
                            message = result.exceptionOrNull()?.message ?: "تعذر تجهيز معاينة الفيديو."
                        }
                    }
                }
            },
            enabled = !resolving && !downloading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (resolving) "جاري تجهيز المعاينة..." else "عرض الفيديو") }


        preview?.let { media ->
            Text(media.fileName, style = MaterialTheme.typography.titleSmall)
            AndroidView(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(Uri.parse(media.url))
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            start()
                        }
                    }
                },
                update = { view ->
                    if (view.tag != media.url) {
                        view.tag = media.url
                        view.setVideoURI(Uri.parse(media.url))
                        view.setOnPreparedListener { it.isLooping = false; view.start() }
                    }
                }
            )
            Button(
                onClick = {
                    downloading = true
                    onDownload(media) { status ->
                        message = status
                        downloading = false
                    }
                },
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (downloading) "جاري إرسال التنزيل..." else "تنزيل الفيديو") }
        }

        Text(message, style = MaterialTheme.typography.bodySmall)
        Text(
            "تُحفظ التنزيلات في مجلد Downloads. تعمل الروابط المباشرة وصفحات الفيديو التي تعرض رابطًا عامًا؛ المواقع التي تتطلب تسجيل دخول أو تحمي الفيديو قد لا تدعم الاستخراج. لا تتم إزالة العلامة المائية من نسخة موجودة؛ استخدم النسخة الأصلية أو خيار التنزيل الرسمي بدون علامة إن وفره صاحب المحتوى.",
            style = MaterialTheme.typography.bodySmall
        )
        }
    }

    if (adminOpen) {
        val savedHash = prefs.getString("admin_hash", null)
        AlertDialog(
            onDismissRequest = { adminOpen = false; adminPassword = ""; newPassword = ""; confirmPassword = ""; adminError = "" },
            title = { Text(if (savedHash == null) "إنشاء كلمة مرور المشرف" else "لوحة المشرف") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (savedHash == null) {
                        OutlinedTextField(value = newPassword, onValueChange = { newPassword = it }, label = { Text("أنشئ كلمة مرور") }, singleLine = true)
                        OutlinedTextField(value = confirmPassword, onValueChange = { confirmPassword = it }, label = { Text("تأكيد كلمة المرور") }, singleLine = true)
                    } else if (!adminUnlocked) {
                        OutlinedTextField(value = adminPassword, onValueChange = { adminPassword = it }, label = { Text("كلمة مرور المشرف") }, singleLine = true)
                    } else {
                        OutlinedTextField(value = titleText, onValueChange = { titleText = it }, label = { Text("اسم التطبيق داخل الواجهة") })
                        OutlinedTextField(value = subtitleText, onValueChange = { subtitleText = it }, label = { Text("النص الظاهر أسفل الاسم") })
                        Button(onClick = { backgroundPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text("اختيار صورة خلفية") }
                        OutlinedTextField(value = newPassword, onValueChange = { newPassword = it }, label = { Text("كلمة مرور جديدة (اختياري)") }, singleLine = true)
                        if (newPassword.isNotBlank()) OutlinedTextField(value = confirmPassword, onValueChange = { confirmPassword = it }, label = { Text("تأكيد كلمة المرور الجديدة") }, singleLine = true)
                    }
                    if (adminError.isNotBlank()) Text(adminError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (savedHash == null) {
                        if (newPassword.length < 6) adminError = "كلمة المرور لازم تكون 6 أحرف على الأقل."
                        else if (newPassword != confirmPassword) adminError = "تأكيد كلمة المرور غير مطابق."
                        else {
                            val salt = java.util.UUID.randomUUID().toString()
                            prefs.edit().putString("admin_salt", salt).putString("admin_hash", passwordHash(salt, newPassword)).apply()
                            adminUnlocked = true; newPassword = ""; confirmPassword = ""; adminError = ""
                        }
                    } else if (!adminUnlocked) {
                        val salt = prefs.getString("admin_salt", "").orEmpty()
                        if (passwordHash(salt, adminPassword) != savedHash) adminError = "كلمة المرور غير صحيحة."
                        else { adminUnlocked = true; adminPassword = ""; adminError = "" }
                    } else if (newPassword.isNotBlank() && (newPassword.length < 6 || newPassword != confirmPassword)) {
                        adminError = if (newPassword.length < 6) "كلمة المرور لازم تكون 6 أحرف على الأقل." else "تأكيد كلمة المرور غير مطابق."
                    } else {
                        val edit = prefs.edit().putString("app_title", titleText).putString("app_subtitle", subtitleText)
                        if (newPassword.isNotBlank()) {
                            val salt = java.util.UUID.randomUUID().toString()
                            edit.putString("admin_salt", salt).putString("admin_hash", passwordHash(salt, newPassword))
                        }
                        edit.apply()
                        adminOpen = false; adminUnlocked = false; adminError = ""; adminPassword = ""; newPassword = ""; confirmPassword = ""
                        message = "تم حفظ إعدادات التطبيق على هذا الجهاز."
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { adminOpen = false }) { Text("إلغاء") } }
        )
    }
}
