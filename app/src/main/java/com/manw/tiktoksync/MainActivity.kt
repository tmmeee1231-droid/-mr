package com.manw.tiktoksync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ManwScreen() } } }
    }
}

@Composable
private fun ManwScreen() {
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
    var videoUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("أضف أسماء حساباتك للبدء.") }

    fun persist(values: List<String>) {
        handles = values
        selected = selected.intersect(values.toSet())
        prefs.edit().putString("handles", JSONArray(values).toString()).apply()
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("𝑀𝒜𝒩𝒲 تيك توك", style = MaterialTheme.typography.headlineMedium)
        Text("لوحة إدارة حسابات TikTok")
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

        Text("الحسابات المحفوظة (${handles.size})", style = MaterialTheme.typography.titleMedium)
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
        OutlinedTextField(
            value = videoUrl, onValueChange = { videoUrl = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("رابط فيديو TikTok") }, singleLine = true
        )
        Button(onClick = {
            val uri = runCatching { Uri.parse(videoUrl.trim()) }.getOrNull()
            if (uri != null && uri.scheme in listOf("https", "http") &&
                (uri.host?.endsWith("tiktok.com") == true || uri.host == "vm.tiktok.com")) {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                message = "افتح الفيديو ثم نفّذ التفاعل يدويًا بالحساب المطلوب."
            } else message = "أدخل رابط TikTok صحيحًا."
        }, modifier = Modifier.fillMaxWidth()) { Text("فتح الفيديو في TikTok") }

        Text("الحسابات المحددة: ${selected.size}")
        Text(message, style = MaterialTheme.typography.bodySmall)
        Text("ملاحظة: التطبيق يحفظ أسماء الحسابات محليًا ولا يطلب كلمات المرور. التفاعلات مثل الإعجاب تُنفّذ يدويًا داخل TikTok.",
            style = MaterialTheme.typography.bodySmall)
    }
}
