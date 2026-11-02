package com.ai.assistance.operit.rpg.update

import java.net.URI
import org.json.JSONObject

internal object RpgUpdatePolicy {
    const val REPOSITORY = "CGM2026/OperitRPG"
    const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024

    fun validateUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "更新地址必须使用 HTTPS" }
        require(uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "更新地址来源不受信任" }
    }

    fun redirect(from: String, location: String?): String {
        require(!location.isNullOrBlank()) { "更新服务器返回无效跳转" }
        val target = URI(from).resolve(location).toString()
        validateUrl(target)
        return target
    }

    fun release(manifest: JSONObject, tag: String, notes: String, applicationId: String, installedCode: Long): RpgRelease? {
        require(Regex("rpg-v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag)) { "版本标识无效" }
        require(manifest.getString("applicationId") == applicationId) { "更新包名不匹配" }
        val version = manifest.getString("versionName")
        require(tag == "rpg-v$version") { "版本信息不一致" }
        val code = manifest.getLong("versionCode")
        require(code > 0)
        val url = manifest.getString("apkUrl")
        require(url == "https://github.com/$REPOSITORY/releases/download/$tag/operit-rpg-$version.apk") { "更新文件不属于当前版本" }
        val bytes = manifest.getLong("sizeBytes")
        require(bytes in 1..MAX_APK_BYTES) { "更新文件大小无效" }
        val sha = manifest.getString("sha256").lowercase()
        require(Regex("[a-f0-9]{64}").matches(sha)) { "更新校验信息无效" }
        return if (code <= installedCode) null else RpgRelease(version, code, notes, url, bytes, sha)
    }
}
