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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ManwScreen(sharedLink) { link, onStatus ->
                        lifecycleScope.launch {
                            onStatus("جاري فحص الرابط واستخراج الفيديو...")
                            val result = withContext(Dispatchers.IO) { resolveVideoUrl(link) }
                            if (result.isFailure) {
                                onStatus(result.exceptionOrNull()?.message ?: "تعذر استخراج الفيديو من هذا الرابط.")
                            } else {
                                val media = result.getOrThrow()
                                val queued = enqueueVideoDownload(this@MainActivity, media.url, media.fileName)
                                onStatus(if (queued) {
                                    "بدأ التحميل. ستجد الفيديو في مجلد Downloads."
                                } else {
                                    "تعذر بدء التحميل على هذا الجهاز."
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ResolvedVideo(val url: String, val fileName: String)

private fun resolveVideoUrl(pageUrl: String): Result<ResolvedVideo> = runCatching {
    val parsed = Uri.parse(pageUrl)
    require(parsed.scheme == "https" || parsed.scheme == "http") {
        "أدخل رابطًا يبدأ بـ https:// أو http://"
    }
    require(!parsed.host.isNullOrBlank()) { "الرابط غير صحيح." }

    val directExtension = parsed.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it in setOf("mp4", "m4v", "mov", "webm", "3gp", "mkv") }

    val videoUrl: String
    var title = "MANW_video"

    if (directExtension != null) {
        videoUrl = pageUrl
    } else {
        val connection = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        }
        try {
            connection.connect()
            require(connection.responseCode in 200..299) {
                "الموقع رفض فتح الرابط (\${connection.responseCode}). قد يحتاج تسجيل دخول أو لا يسمح بالتنزيل."
            }
            val contentType = connection.contentType.orEmpty()
            if (contentType.startsWith("video/")) {
                videoUrl = connection.url.toString()
            } else {
                val document = connection.inputStream.bufferedReader().use { it.readText() }
                val page = Jsoup.parse(document, connection.url.toString())
                title = page.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: page.title().takeIf { it.isNotBlank() }
                    ?: title

                val candidate = page.selectFirst("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player:stream]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: page.selectFirst("video source[src], video[src]")?.let {
                        it.absUrl("src").ifBlank { it.attr("src") }
                    }
                require(!candidate.isNullOrBlank()) {
                    "لم أجد رابط فيديو مباشرًا في الصفحة. جرّب رابط MP4 مباشرًا أو موقعًا يوفّر تنزيلًا رسميًا."
                }
                videoUrl = URL(connection.url, candidate).toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    val mediaUri = Uri.parse(videoUrl)
    require(mediaUri.scheme == "https" || mediaUri.scheme == "http") { "رابط الفيديو المستخرج غير صالح." }
    val extension = mediaUri.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it in setOf("mp4", "m4v", "mov", "webm", "3gp", "mkv") }
        ?: "mp4"
    val safeTitle = title.replace(Regex("[^A-Za-z0-9 _-]"), "")
        .trim().replace(Regex("\\s+"), "_").take(50)
        .ifBlank { "MANW_video" }
    ResolvedVideo(videoUrl, "$safeTitle.$extension")
}

private fun enqueueVideoDownload(context: Context, url: String, fileName: String): Boolean = runCatching {
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(fileName)
        .setDescription("𝑀𝒜𝒩𝒲 - تنزيل الفيديو")
        .setMimeType("video/*")
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
    onDownload: (String, (String) -> Unit) -> Unit
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
    var message by remember { mutableStateOf("أضف رابط فيديو من أي موقع يدعم رابط فيديو قابلًا للتنزيل.") }
    var downloading by remember { mutableStateOf(false) }
    var titleText by remember { mutableStateOf(prefs.getString("app_title", "𝑀𝒜𝒩𝒲 تيك توك") ?: "𝑀𝒜𝒩𝒲 تيك توك") }
    var subtitleText by remember { mutableStateOf(prefs.getString("app_subtitle", "إدارة الحسابات وتنزيل الفيديوهات") ?: "إدارة الحسابات وتنزيل الفيديوهات") }
    var backgroundUri by remember { mutableStateOf(prefs.getString("background_uri", null)) }
    var adminOpen by remember { mutableStateOf(false) }
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
                    downloading = true
                    onDownload(videoUrl.trim()) { status ->
                        message = status
                        downloading = false
                    }
                }
            },
            enabled = !downloading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (downloading) "جاري التجهيز..." else "تحميل الفيديو") }

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
                    if (savedHash != null) {
                        OutlinedTextField(value = adminPassword, onValueChange = { adminPassword = it }, label = { Text("كلمة المرور الحالية") }, singleLine = true)
                    }
                    OutlinedTextField(value = newPassword, onValueChange = { newPassword = it }, label = { Text(if (savedHash == null) "أنشئ كلمة مرور" else "كلمة المرور الجديدة") }, singleLine = true)
                    OutlinedTextField(value = confirmPassword, onValueChange = { confirmPassword = it }, label = { Text("تأكيد كلمة المرور") }, singleLine = true)
                    if (savedHash != null) {
                        OutlinedTextField(value = titleText, onValueChange = { titleText = it }, label = { Text("اسم التطبيق داخل الواجهة") })
                        OutlinedTextField(value = subtitleText, onValueChange = { subtitleText = it }, label = { Text("النص الظاهر أسفل الاسم") })
                        Button(onClick = { backgroundPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text("اختيار صورة خلفية") }
                    }
                    if (adminError.isNotBlank()) Text(adminError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPassword.length < 6) {
                        adminError = "كلمة المرور لازم تكون 6 أحرف على الأقل."
                    } else if (newPassword != confirmPassword) {
                        adminError = "تأكيد كلمة المرور غير مطابق."
                    } else if (savedHash == null) {
                        val salt = java.util.UUID.randomUUID().toString()
                        prefs.edit().putString("admin_salt", salt).putString("admin_hash", passwordHash(salt, newPassword)).apply()
                        adminOpen = false; adminError = ""; message = "تم إنشاء كلمة مرور المشرف."
                    } else {
                        val salt = prefs.getString("admin_salt", "").orEmpty()
                        if (passwordHash(salt, adminPassword) != savedHash) {
                            adminError = "كلمة المرور الحالية غير صحيحة."
                        } else {
                            prefs.edit().putString("admin_hash", passwordHash(salt, newPassword))
                                .putString("app_title", titleText).putString("app_subtitle", subtitleText).apply()
                            adminOpen = false; adminError = ""; adminPassword = ""; newPassword = ""; confirmPassword = ""
                            message = "تم حفظ إعدادات التطبيق على هذا الجهاز."
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { adminOpen = false }) { Text("إلغاء") } }
        )
    }
}
