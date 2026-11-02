package com.ai.assistance.operit.rpg.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.io.File

/** Drop-in control for the RPG settings screen. No background downloads or implicit installs. */
@Composable
fun RpgUpdateButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember(context) { RpgUpdateManager(context) }
    val scope = rememberCoroutineScope()
    var visible by rememberSaveable { mutableStateOf(false) }
    var version by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf(0L) }
    var notes by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var bytes by rememberSaveable { mutableStateOf(0L) }
    var sha by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    fun release() = RpgRelease(version, code, notes, url, bytes, sha)
    fun install() {
        try { manager.install(File(path), release()); message = "已打开系统安装页面；取消后可再次安装。" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message = error.message ?: "安装启动失败，请重试" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (manager.canInstall() && path.isNotEmpty()) install()
        else message = "尚未允许安装更新，点击安装可重新开启设置。"
    }
    TextButton(modifier = modifier, enabled = !busy, onClick = {
        visible = true
        busy = true
        version = ""
        path = ""
        message = "正在检查新版…"
        scope.launch {
            try {
                val found = manager.check()
                if (found == null) message = "当前已是最新版本。"
                else {
                    version = found.version; code = found.code; notes = found.notes
                    url = found.url; bytes = found.bytes; sha = found.sha256
                    message = "发现新版 $version，下载大小 ${bytes / 1024 / 1024} MB。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "检查失败，请重试" }
            finally { busy = false }
        }
    }) { Text("检查新版") }
    if (visible) AlertDialog(
        onDismissRequest = { if (!busy) visible = false },
        title = { Text("雨夜跑团更新") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(message)
            if (version.isNotEmpty() && path.isEmpty()) Text("下载时请保持当前页面；旋转屏幕后可安全重试。")
            if (version.isNotEmpty()) Text(notes.take(4000))
            if (busy && version.isNotEmpty()) LinearProgressIndicator(progress = { progress })
        } },
        confirmButton = {
            if (version.isNotEmpty()) TextButton(enabled = !busy, onClick = {
                if (path.isNotEmpty() && File(path).isFile) {
                    if (manager.canInstall()) install() else permission.launch(manager.permissionIntent())
                } else {
                    busy = true; progress = 0f; message = "正在下载并校验…"
                    scope.launch {
                        try {
                            path = manager.download(release()) { progress = it }.path
                            message = "校验通过，准备安装。"
                            if (manager.canInstall()) install() else permission.launch(manager.permissionIntent())
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { message = error.message ?: "下载失败，请重试"; path = "" }
                        finally { busy = false }
                    }
                }
            }) { Text(if (path.isEmpty()) "下载并安装" else "安装更新") }
            else TextButton(enabled = !busy, onClick = { visible = false }) { Text("知道了") }
        },
        dismissButton = { if (version.isNotEmpty()) TextButton(enabled = !busy, onClick = { visible = false }) { Text("稍后") } }
    )
}
