package com.ai.assistance.operit.rpg.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ai.assistance.operit.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Every update remains tied to this repository, package and installed signing certificate. */
class RpgUpdateManager(private val context: Context) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val repository = RpgUpdatePolicy.REPOSITORY

    private fun get(url: String): okhttp3.Response {
        var target = url
        repeat(6) {
            RpgUpdatePolicy.validateUrl(target)
            val response = client.newCall(Request.Builder().url(target).header("User-Agent", "OperitRPG/${BuildConfig.VERSION_NAME}").build()).execute()
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                target = try { RpgUpdatePolicy.redirect(target, response.header("Location")) }
                finally { response.close() }
            } else {
                if (!response.isSuccessful) { val status = response.code; response.close(); error("更新服务器错误：$status") }
                return response
            }
        }
        error("更新服务器跳转过多")
    }

    private fun json(url: String): JSONObject = get(url).use { response ->
        val body = requireNotNull(response.body)
        require(body.contentLength() <= 1024 * 1024) { "更新信息过大" }
        val data = body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= 1024 * 1024) { "更新信息过大" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        JSONObject(data.toString(Charsets.UTF_8))
    }

    suspend fun check(): RpgRelease? = withContext(Dispatchers.IO) {
        val latest = json("https://api.github.com/repos/$repository/releases/latest")
        require(!latest.getBoolean("draft") && !latest.getBoolean("prerelease")) { "版本尚未正式发布" }
        val tag = latest.getString("tag_name")
        require(Regex("rpg-v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag)) { "版本标识无效" }
        val manifest = json("https://github.com/$repository/releases/download/$tag/update-manifest.json")
        RpgUpdatePolicy.release(manifest, tag, latest.getString("body"), BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong())
    }

    suspend fun download(release: RpgRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        // Rotation can cancel a previous UI request while its blocking read is still ending.
        // Each request owns its file, including cleanup on cancellation or verification failure.
        val output = File(context.cacheDir, "rpg-update/update-${UUID.randomUUID()}.apk")
        output.parentFile!!.mkdirs()
        try {
            get(release.url).use { response ->
                val body = requireNotNull(response.body)
                require(body.contentLength() == release.bytes) { "下载大小与发布信息不一致" }
                body.byteStream().use { input -> output.outputStream().use { destination ->
                    RpgDownloadVerifier.copyVerified(input, destination, release.bytes, release.sha256, progress)
                } }
            }
            verifyApk(output, release)
            output
        } catch (error: Exception) { output.delete(); throw error }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, release: RpgRelease) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(manager.getPackageArchiveInfo(file.path, flags)) { "无法读取安装包" }
        val installed = manager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == BuildConfig.APPLICATION_ID) { "安装包身份不匹配" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(code == release.code && code > BuildConfig.VERSION_CODE && archive.versionName == release.version) { "安装包版本不匹配" }
        val incoming = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val current = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        require(!incoming.isNullOrEmpty() && !current.isNullOrEmpty() && incoming.toSet() == current.toSet()) { "安装包签名与当前应用不一致" }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    fun install(file: File, release: RpgRelease) {
        require(canInstall()) { "请先允许此应用安装更新" }
        verifyApk(file, release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
