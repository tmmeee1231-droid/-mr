package com.manw.tiktoksync

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

    fun persist(values: List<String>) {
        handles = values
        selected = selected.intersect(values.toSet())
        prefs.edit().putString("handles", JSONArray(values).toString()).apply()
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("𝑀𝒜𝒩𝒲 تيك توك", style = MaterialTheme.typography.headlineMedium)
        Text("إدارة الحسابات وتنزيل الفيديوهات")

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
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(handles, key = { it }) { handle ->
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
