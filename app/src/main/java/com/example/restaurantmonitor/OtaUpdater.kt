package com.example.restaurantmonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class OtaUpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String = ""
)

object OtaUpdater {
    /** GitHub Releases（工程下載／有 repo 權限時可用） */
    const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/takopos/networkmonitor/releases/latest"

    /** 對外下載頁（Release 頁面） */
    const val PUBLIC_DOWNLOAD_PAGE =
        "https://github.com/takopos/networkmonitor/releases/latest"

    fun currentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (_: Exception) {
            "0"
        }
    }

    /**
     * 檢查更新：優先 Google Sheets APK 表（packageName 相符），
     * 若無則嘗試 GitHub Releases latest。
     */
    suspend fun checkForUpdate(context: Context, storeId: String? = null): OtaUpdateInfo? =
        withContext(Dispatchers.IO) {
            val current = currentVersionName(context)
            val packageName = context.packageName

            checkFromGoogleSheet(packageName, storeId, current)
                ?: checkFromGitHub(current)
        }

    private fun checkFromGoogleSheet(
        packageName: String,
        storeId: String?,
        currentVersion: String
    ): OtaUpdateInfo? {
        return try {
            val apkCsv = URL("$GOOGLE_SHEET_APK_CSV_URL&t=${System.currentTimeMillis()}").readText()
            var best: OtaUpdateInfo? = null
            for (line in apkCsv.lines().drop(1)) {
                if (line.isBlank()) continue
                val cols = line.split(",").map { it.trim().replace("\"", "") }
                if (cols.size < 6) continue
                val targetStoreId = cols[0]
                val rowPackage = cols[3]
                val latestVersion = cols[4]
                val downloadUrl = cols[5]
                val storeOk = storeId.isNullOrBlank() ||
                    targetStoreId.equals("ALL", ignoreCase = true) ||
                    targetStoreId == storeId
                if (!storeOk || rowPackage != packageName) continue
                if (isNewerVersion(latestVersion, currentVersion)) {
                    if (best == null || isNewerVersion(latestVersion, best.latestVersion)) {
                        best = OtaUpdateInfo(
                            latestVersion = latestVersion,
                            downloadUrl = downloadUrl,
                            releaseNotes = "雲端發佈的新版本"
                        )
                    }
                }
            }
            best
        } catch (_: Exception) {
            null
        }
    }

    private fun checkFromGitHub(currentVersion: String): OtaUpdateInfo? {
        return try {
            val conn = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TAKO-Store-Monitor")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v")
            if (!isNewerVersion(tag, currentVersion)) return null
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) return null
            OtaUpdateInfo(
                latestVersion = tag,
                downloadUrl = apkUrl,
                releaseNotes = json.optString("body", "").ifBlank { "GitHub Release 新版本" }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 比較語意化版本，remote > local 時回傳 true */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val r = parseVersion(remote)
        val l = parseVersion(local)
        val max = maxOf(r.size, l.size)
        for (i in 0 until max) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun parseVersion(version: String): List<Int> {
        return version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir("Download") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val outFile = File(dir, "tako_ota_update.apk")
        if (outFile.exists()) outFile.delete()

        val resolvedUrl = resolveDriveDirectUrl(downloadUrl)
        val conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TAKO-Store-Monitor")
        }
        conn.inputStream.use { input ->
            val total = conn.contentLengthLong
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                var downloaded = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) {
                        onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        onProgress(100)
        outFile
    }

    /** Google Drive 分享連結轉直接下載 */
    fun resolveDriveDirectUrl(url: String): String {
        val fileId = Regex("""/d/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
        return if (fileId != null) {
            "https://drive.google.com/uc?export=download&id=$fileId"
        } else {
            url
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
