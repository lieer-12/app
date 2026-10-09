package com.example.lifemanager.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.lifemanager.BuildConfig
import com.example.lifemanager.data.about.OfflineLicenses

@Composable
fun SettingsPlatformSection() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun read() = Triple(
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        NotificationManagerCompat.from(context).areNotificationsEnabled(),
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    )
    var permissions by remember(context) { mutableStateOf(read()) }
    var message by remember { mutableStateOf<String?>(null) }
    var licenseAsset by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissions = read() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissions = read() // Never treat the callback Boolean as proof of system state.
        message = if (!permissions.first || !permissions.second)
            "通知尚未开启，可在系统通知设置中调整。离线数据操作不受影响。" else null
    }
    fun launch(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { message = "此设备未提供该设置入口，请在系统设置中找到本应用并调整权限。离线数据操作不受影响。" }
        catch (_: SecurityException) { message = "系统限制了此操作，请在系统设置中检查本应用权限。离线数据操作不受影响。" }
    }
    Text("系统提醒权限", style = MaterialTheme.typography.titleLarge)
    Text(if (permissions.first) "通知权限：已允许" else "通知权限：未允许")
    Text(if (permissions.second) "应用通知：已开启" else "应用通知：已关闭")
    if (Build.VERSION.SDK_INT >= 33 && !permissions.first) {
        Button(onClick = {
            try { request.launch(Manifest.permission.POST_NOTIFICATIONS) }
            catch (_: Exception) { message = "无法申请通知权限，请使用系统通知设置。离线数据操作不受影响。" }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("申请通知权限", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
    OutlinedButton(onClick = { launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("系统通知设置", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    Text(when {
        Build.VERSION.SDK_INT < 31 -> "精确提醒：此系统无需单独授权"
        permissions.third -> "精确提醒：已允许"
        else -> "精确提醒：未允许，提醒可能延迟"
    })
    if (Build.VERSION.SDK_INT >= 31) {
        OutlinedButton(onClick = { launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("精确提醒设置", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
    Text("权限关闭不影响离线保存；省电限制、通知渠道设置也可能影响投递。", style = MaterialTheme.typography.bodySmall)
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("关于", style = MaterialTheme.typography.titleLarge)
    Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", modifier = Modifier.fillMaxWidth())
    Text("项目尚未声明开源许可证；第三方组件遵循各自许可证。")
    Text("数据保存在本设备；备份是明文文件，请妥善保管。")
    OutlinedButton(onClick = { licenseAsset = "third-party-licenses.txt" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看离线第三方许可证", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    licenseAsset?.let { asset ->
        val text by produceState("正在读取本地许可证…", asset) {
            value = try { OfflineLicenses.read(context, asset) } catch (_: Exception) { "无法读取本地许可证，请重新打开页面。" }
        }
        AlertDialog(
            onDismissRequest = { licenseAsset = null },
            title = { Text(if (asset == "apache-2.0.txt") "Apache License 2.0" else "第三方许可证") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Separate paragraphs let accessibility and tests reach the end of long text.
                    text.split("\n\n").forEach { Text(it) }
                    if (asset != "apache-2.0.txt") TextButton(onClick = { licenseAsset = "apache-2.0.txt" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Apache License 2.0 原文") }
                }
            },
            confirmButton = { TextButton(onClick = { licenseAsset = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭许可证") } },
            dismissButton = {
                if (asset == "apache-2.0.txt") TextButton(onClick = { licenseAsset = "third-party-licenses.txt" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回依赖清单") }
            },
        )
    }
}
