package com.manw.tiktoksync

class AppHiderService : IAppHiderService.Stub() {
    override fun setPackageEnabled(packageName: String, enabled: Boolean): String {
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            return "ERROR: اسم الحزمة غير صالح"
        }
        return try {
            val command = if (enabled) {
                arrayOf("pm", "enable", "--user", "0", packageName)
            } else {
                arrayOf("pm", "disable-user", "--user", "0", packageName)
            }
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode == 0) "OK:$output" else "ERROR:${output.ifBlank { "رفض النظام العملية" }}"
        } catch (e: Exception) {
            "ERROR:${e.message ?: "فشل تنفيذ الأمر"}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}