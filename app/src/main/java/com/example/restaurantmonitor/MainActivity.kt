package com.example.restaurantmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

// ==========================================
// ✅ 雲端資料庫設定
// ==========================================
const val GOOGLE_SHEET_AUTH_CSV_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vRd5flUUgB6kq2GD_HlOuGXHTJ6yMrGfvg05ZYjBC_mf9cmxeluMoYw4VQB_06AehbOKXB0DkQWgrLl/pub?gid=0&single=true&output=csv"
const val GOOGLE_SHEET_APK_CSV_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vRd5flUUgB6kq2GD_HlOuGXHTJ6yMrGfvg05ZYjBC_mf9cmxeluMoYw4VQB_06AehbOKXB0DkQWgrLl/pub?gid=1381228885&single=true&output=csv"
const val GOOGLE_SHEET_MSG_CSV_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vRd5flUUgB6kq2GD_HlOuGXHTJ6yMrGfvg05ZYjBC_mf9cmxeluMoYw4VQB_06AehbOKXB0DkQWgrLl/pub?gid=997114230&single=true&output=csv"

// 資料模型
data class Device(
    val id: String, 
    val name: String, 
    var ip: String, 
    val port: Int? = null,
    var isOnline: Boolean = true, 
    var hasNotifiedOffline: Boolean = false, 
    val troubleshootingMsg: String,
    val hasCashDrawer: Boolean = false // 新增：是否連接錢箱
)

data class ApkInfo(
    val id: String, val appName: String, val packageName: String,
    val latestVersion: String, val downloadUrl: String
)

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        askPermissions()
        requestBatteryExemption()
        checkOverlayPermission()
        setContent { MaterialTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MonitorScreen() } } }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("NETWORK_ALERT_CHANNEL", "網路異常警報", NotificationManager.IMPORTANCE_HIGH)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun askPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { permissions.add(Manifest.permission.ACCESS_FINE_LOCATION); permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        if (permissions.isNotEmpty()) requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent().apply { action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; data = Uri.parse("package:$packageName") })
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }
}

/**
 * 解析字串中的 URL 並轉換為可點擊的 AnnotatedString
 */
@Composable
fun parseMessageWithLinks(text: String): AnnotatedString {
    val urlRegex = "(https?://[\\w\\d:#@%/\\\$()~_?\\+-=\\\\\\.&]*)".toRegex()
    return buildAnnotatedString {
        var lastIndex = 0
        urlRegex.findAll(text).forEach { matchResult ->
            append(text.substring(lastIndex, matchResult.range.first))
            val url = matchResult.value
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = Color.Blue,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Bold
                        )
                    )
                )
            ) {
                append(url)
            }
            lastIndex = matchResult.range.last + 1
        }
        append(text.substring(lastIndex))
    }
}

@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("TakoAppPrefs", Context.MODE_PRIVATE)

    val currentToolVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知" } catch (e: Exception) { "未知" }
    }

    var showAuthDialog by remember { mutableStateOf(false) }
    var showRouterDialog by remember { mutableStateOf(false) }
    var showModemDialog by remember { mutableStateOf(false) }
    var showPrinterDialog by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showMsgHistoryDialog by remember { mutableStateOf(false) }
    var isMsgLoading by remember { mutableStateOf(false) }
    var showImageDialog by remember { mutableStateOf(false) }
    var imageResToShow by remember { mutableStateOf(0) }

    var storeNameInput by remember { mutableStateOf(prefs.getString("storeName", "") ?: "") }
    var storeIdInput by remember { mutableStateOf(prefs.getString("storeId", "") ?: "") }
    var isAuthorized by remember { mutableStateOf(prefs.getBoolean("isAuthorized", false)) }
    var authMessage by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }

    var authorizedApks by remember { mutableStateOf(loadApksFromPrefs(context)) }
    val devices = remember { mutableStateListOf<Device>().apply { addAll(loadDevicesFromPrefs(context)) } }

    var newPrinterIp by remember { mutableStateOf("") }
    var newPrinterName by remember { mutableStateOf("") }
    var newPrinterHasCashDrawer by remember { mutableStateOf(false) } // 新增：新增出單機時是否連錢箱
    var routerIpInput by remember { mutableStateOf("") }
    var modemIpInput by remember { mutableStateOf("") }
    var deviceToDelete by remember { mutableStateOf<Device?>(null) }

    var currentAnnouncement by remember { mutableStateOf<Pair<String, String>?>(null) }
    var msgHistory by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // 啟動/關閉 懸浮錢箱按鈕服務
    LaunchedEffect(devices.map { it.hasCashDrawer }) {
        val anyCashDrawerEnabled = devices.any { it.hasCashDrawer }
        val serviceIntent = Intent(context, FloatingCashDrawerService::class.java)
        if (anyCashDrawerEnabled && Settings.canDrawOverlays(context)) {
            context.startService(serviceIntent)
        } else {
            context.stopService(serviceIntent)
        }
    }

    // 📡 背景監測：網路設備狀態
    LaunchedEffect(isAuthorized) {
        while (true) {
            if (!isAuthorized) { delay(5000); continue }
            val currentSsid = getWifiSSID(context)
            val cleanSsid = currentSsid.uppercase()
            val printers = devices.filter { it.name.contains("出單機") || it.name.contains("🖨️") }
            val allPrintersOffline = printers.isNotEmpty() && printers.all { !it.isOnline }

            for (i in devices.indices) {
                val device = devices[i]
                var isReachable = true
                if (device.id == "0") {
                    if (currentSsid.isEmpty() || cleanSsid.contains("UNKNOWN")) {
                        devices[i].ip = "切換中..."; isReachable = true
                    } else {
                        devices[i].ip = currentSsid
                        isReachable = if (allPrintersOffline) cleanSsid.startsWith("TAKOPOS") else true
                    }
                } else {
                    isReachable = if (device.port != null) checkSocketPort(device.ip, device.port) else pingIp(device.ip)
                }

                if (!isReachable && !device.hasNotifiedOffline) {
                    playAlertSound(); sendDisconnectNotification(context, device.name, device.troubleshootingMsg)
                    devices[i] = device.copy(isOnline = false, hasNotifiedOffline = true)
                } else if (isReachable && !device.isOnline) {
                    devices[i] = device.copy(isOnline = true, hasNotifiedOffline = false)
                }
            }
            delay(5000)
        }
    }

    // 📢 背景監測：總部通告
    LaunchedEffect(isAuthorized) {
        while (true) {
            if (!isAuthorized) { delay(30000); continue }
            try {
                val msgCsv = withContext(Dispatchers.IO) { URL("$GOOGLE_SHEET_MSG_CSV_URL&t=${System.currentTimeMillis()}").readText() }
                for (line in msgCsv.lines()) {
                    if (line.isBlank()) continue
                    val cols = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()).map { it.replace("\"", "").trim() }
                    if (cols.size >= 3) {
                        val targetStoreId = cols[0]
                        val msgId = cols[1]
                        val msgContent = cols[2]
                        if (targetStoreId == storeIdInput || targetStoreId.uppercase() == "ALL") {
                            val isRead = prefs.getBoolean("MSG_READ_$msgId", false)
                            if (!isRead) {
                                currentAnnouncement = msgId to msgContent
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            delay(60000)
        }
    }

    if (currentAnnouncement != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("📢 總部重要通知") },
            text = { Text(currentAnnouncement!!.second) },
            confirmButton = {
                Button(onClick = {
                    prefs.edit().putBoolean("MSG_READ_${currentAnnouncement!!.first}", true).apply()
                    currentAnnouncement = null
                }) { Text("我已瞭解") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 頂部列：商店資訊與設定按鈕
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(text = if (isAuthorized) "🏪 $storeNameInput ($storeIdInput)" else "❌ 未驗證商店", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(text = "版本: $currentToolVersion", fontSize = 12.sp, color = Color.Gray)
            }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("🔑 重新驗證商店") }, onClick = { showMenu = false; showAuthDialog = true })
                    DropdownMenuItem(text = { Text("⚙️ 設定 Router IP") }, onClick = { showMenu = false; showRouterDialog = true })
                    DropdownMenuItem(text = { Text("⚙️ 設定 Modem IP") }, onClick = { showMenu = false; showModemDialog = true })
                    DropdownMenuItem(text = { Text("🖨️ 新增自定義出單機") }, onClick = { showMenu = false; showPrinterDialog = true })
                    DropdownMenuItem(text = { Text("📢 查看歷史通知") }, onClick = {
                        showMenu = false; showMsgHistoryDialog = true; isMsgLoading = true
                        coroutineScope.launch {
                            try {
                                val msgCsv = withContext(Dispatchers.IO) { URL("$GOOGLE_SHEET_MSG_CSV_URL&t=${System.currentTimeMillis()}").readText() }
                                val list = mutableListOf<Pair<String, String>>()
                                for (line in msgCsv.lines()) {
                                    if (line.isBlank()) continue
                                    val cols = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()).map { it.replace("\"", "").trim() }
                                    if (cols.size >= 3) {
                                        val targetStoreId = cols[0]
                                        if (targetStoreId == storeIdInput || targetStoreId.uppercase() == "ALL") list.add(cols[1] to cols[2])
                                    }
                                }
                                msgHistory = list.reversed()
                            } catch (e: Exception) { e.printStackTrace() }
                            isMsgLoading = false
                        }
                    })
                    DropdownMenuItem(text = { Text("💡 技術支援 (Line)") }, onClick = { showMenu = false; showSupportDialog = true })
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 監視設備列表
        Text("📡 設備連線狀態", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            items(devices) { device -> DeviceItem(device, onDelete = { deviceToDelete = it }, onToggleCashDrawer = { d, enabled ->
                val idx = devices.indexOfFirst { it.id == d.id }
                if (idx != -1) {
                    devices[idx] = devices[idx].copy(hasCashDrawer = enabled)
                    saveDevicesToPrefs(context, devices)
                }
            }) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 雲端 App 下載區
        Text("☁️ 雲端 App 下載", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (!isAuthorized) {
            Text("⚠️ 請先驗證商店以查看可用 App", color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        } else {
            LazyRow(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(authorizedApks) { apk -> ApkItem(apk, context) }
            }
        }
    }

    // --- 對話框 ---
    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = { if (!isVerifying) showAuthDialog = false },
            title = { Text("🔑 驗證商店權限") },
            text = {
                Column {
                    OutlinedTextField(value = storeNameInput, onValueChange = { storeNameInput = it }, label = { Text("商店名稱 (例如: Tako 總部)") }, modifier = Modifier.fillMaxWidth(), enabled = !isVerifying)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = storeIdInput, onValueChange = { storeIdInput = it }, label = { Text("商店代號 (例如: S001)") }, modifier = Modifier.fillMaxWidth(), enabled = !isVerifying)
                    if (authMessage.isNotEmpty()) { Text(authMessage, color = if (authMessage.contains("✅")) Color.Green else Color.Red, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isVerifying = true; authMessage = "驗證中..."
                            val apks = fetchCloudApkData(storeNameInput, storeIdInput)
                            if (apks != null) {
                                isAuthorized = true; authorizedApks = apks
                                prefs.edit().putString("storeName", storeNameInput).putString("storeId", storeIdInput).putBoolean("isAuthorized", true).apply()
                                saveApksToPrefs(context, apks)
                                authMessage = "✅ 授權成功！"
                                delay(1000); showAuthDialog = false
                            } else {
                                isAuthorized = false; authMessage = "❌ 驗證失敗，請檢查資料。"
                            }
                            isVerifying = false
                        }
                    },
                    enabled = !isVerifying && storeNameInput.isNotBlank() && storeIdInput.isNotBlank()
                ) { if (isVerifying) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White) else Text("驗證") }
            },
            dismissButton = { TextButton(onClick = { showAuthDialog = false }, enabled = !isVerifying) { Text("取消") } }
        )
    }

    if (showRouterDialog) {
        AlertDialog(
            onDismissRequest = { showRouterDialog = false },
            title = { Text("⚙️ 設定 Router IP") },
            text = { OutlinedTextField(value = routerIpInput, onValueChange = { routerIpInput = it }, label = { Text("例如: 192.168.123.1") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { val idx = devices.indexOfFirst { it.id == "1" }; if (idx != -1) { devices[idx] = devices[idx].copy(ip = routerIpInput); saveDevicesToPrefs(context, devices) }; showRouterDialog = false }) { Text("儲存") } },
            dismissButton = { TextButton(onClick = { showRouterDialog = false }) { Text("取消") } }
        )
    }

    if (showModemDialog) {
        AlertDialog(
            onDismissRequest = { showModemDialog = false },
            title = { Text("⚙️ 設定 Modem IP") },
            text = { OutlinedTextField(value = modemIpInput, onValueChange = { modemIpInput = it }, label = { Text("例如: 192.168.1.1") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { val idx = devices.indexOfFirst { it.id == "2" }; if (idx != -1) { devices[idx] = devices[idx].copy(ip = modemIpInput); saveDevicesToPrefs(context, devices) }; showModemDialog = false }) { Text("儲存") } },
            dismissButton = { TextButton(onClick = { showModemDialog = false }) { Text("取消") } }
        )
    }

    if (showPrinterDialog) {
        AlertDialog(
            onDismissRequest = { showPrinterDialog = false },
            title = { Text("🖨️ 新增自定義出單機") },
            text = {
                Column {
                    OutlinedTextField(value = newPrinterName, onValueChange = { newPrinterName = it }, label = { Text("出單機名稱 (例如: 廚房出單機)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newPrinterIp, onValueChange = { newPrinterIp = it }, label = { Text("IP 位址 (例如: 192.168.123.100)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = newPrinterHasCashDrawer, onCheckedChange = { newPrinterHasCashDrawer = it })
                        Text("此出單機有連接錢箱")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPrinterName.isNotBlank() && newPrinterIp.isNotBlank()) {
                        val newDevice = Device(
                            id = System.currentTimeMillis().toString(),
                            name = "🖨️ $newPrinterName",
                            ip = newPrinterIp,
                            port = 9100,
                            troubleshootingMsg = "1. 檢查出單機電源與網路線\n2. 檢查紙捲是否用完\n3. 重新開機出單機",
                            hasCashDrawer = newPrinterHasCashDrawer
                        )
                        devices.add(newDevice)
                        saveDevicesToPrefs(context, devices)
                        newPrinterName = ""; newPrinterIp = ""; newPrinterHasCashDrawer = false; showPrinterDialog = false
                    }
                }) { Text("新增") }
            },
            dismissButton = { TextButton(onClick = { showPrinterDialog = false }) { Text("取消") } }
        )
    }

    if (deviceToDelete != null) {
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            title = { Text("🗑️ 刪除設備") },
            text = { Text("確定要刪除「${deviceToDelete?.name}」嗎？") },
            confirmButton = { Button(onClick = { devices.remove(deviceToDelete); saveDevicesToPrefs(context, devices); deviceToDelete = null }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deviceToDelete = null }) { Text("取消") } }
        )
    }

    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("💡 技術支援") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("掃描下方 QR Code 加入 Tako 客服 Line")
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(painter = painterResource(id = R.drawable.qr), contentDescription = "Line QR", modifier = Modifier.size(150.dp))
                }
            },
            confirmButton = { Button(onClick = { showSupportDialog = false }) { Text("關閉") } }
        )
    }

    if (showMsgHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showMsgHistoryDialog = false },
            title = { Text("📢 歷史通知") },
            text = {
                Box(modifier = Modifier.height(300.dp).fillMaxWidth()) {
                    if (isMsgLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (msgHistory.isEmpty()) {
                        Text("尚無通知紀錄", modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn {
                            items(msgHistory) { msg ->
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text("ID: ${msg.first}", fontSize = 12.sp, color = Color.Gray)
                                    Text(parseMessageWithLinks(msg.second))
                                    Divider(modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showMsgHistoryDialog = false }) { Text("關閉") } }
        )
    }

    if (showImageDialog) {
        AlertDialog(
            onDismissRequest = { showImageDialog = false },
            text = {
                Box(contentAlignment = Alignment.Center) {
                    Image(painter = painterResource(id = imageResToShow), contentDescription = "Tutorial Image", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                }
            },
            confirmButton = { Button(onClick = { showImageDialog = false }) { Text("關閉") } }
        )
    }
}

@Composable
fun DeviceItem(device: Device, onDelete: (Device) -> Unit, onToggleCashDrawer: (Device, Boolean) -> Unit) {
    val isCustomPrinter = device.id != "0" && device.id != "1" && device.id != "2"
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = if (device.isOnline) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(if (device.isOnline) Color.Green else Color.Red))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(device.ip, fontSize = 14.sp, color = Color.Gray)
                }
                if (isCustomPrinter) {
                    IconButton(onClick = { onDelete(device) }) { Icon(painterResource(id = android.R.drawable.ic_menu_delete), contentDescription = "Delete", tint = Color.Red) }
                }
            }

            if (!device.isOnline) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("❌ 故障排除建議：", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(device.troubleshootingMsg, fontSize = 13.sp, color = Color.DarkGray)
            }

            // 新增：如果名稱包含「出單機」或有印表機圖示，顯示錢箱開關
            if (device.name.contains("出單機") || device.name.contains("🖨️")) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = device.hasCashDrawer, onCheckedChange = { onToggleCashDrawer(device, it) })
                    Text("啟動錢箱按鈕", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun ApkItem(apk: ApkInfo, context: Context) {
    Card(
        modifier = Modifier.width(120.dp).padding(4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray), contentAlignment = Alignment.Center) {
                Text(apk.appName.take(1), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(apk.appName, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("v${apk.latestVersion}", fontSize = 10.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apk.downloadUrl))
                    context.startActivity(intent)
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("下載", fontSize = 10.sp)
            }
        }
    }
}

// ==========================================
// 🛠️ 工具函式
// ==========================================

fun getWifiSSID(context: Context): String {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val info = wifiManager.connectionInfo
    return info.ssid.replace("\"", "")
}

fun pingIp(ip: String): Boolean {
    return try {
        val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -w 2 $ip")
        process.waitFor() == 0
    } catch (e: Exception) { false }
}

fun checkSocketPort(ip: String, port: Int): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), 2000)
            true
        }
    } catch (e: Exception) { false }
}

fun playAlertSound() {
    try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500) } catch (e: Exception) {}
}

fun sendDisconnectNotification(context: Context, deviceName: String, msg: String) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val builder = NotificationCompat.Builder(context, "NETWORK_ALERT_CHANNEL")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("⚠️ 設備斷線通知: $deviceName")
        .setContentText("請檢查設備連線狀態。")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setStyle(NotificationCompat.BigTextStyle().bigText("設備 $deviceName 已斷線。\n\n建議排除步驟：\n$msg"))
    notificationManager.notify(deviceName.hashCode(), builder.build())
}

// ==========================================
// 📂 資料持久化 (SharedPreferences)
// ==========================================

fun saveDevicesToPrefs(context: Context, devices: List<Device>) {
    val prefs = context.getSharedPreferences("TakoAppPrefs", Context.MODE_PRIVATE)
    val array = JSONArray()
    devices.forEach {
        val obj = JSONObject()
        obj.put("id", it.id)
        obj.put("name", it.name)
        obj.put("ip", it.ip)
        if (it.port != null) obj.put("port", it.port)
        obj.put("troubleshootingMsg", it.troubleshootingMsg)
        obj.put("hasCashDrawer", it.hasCashDrawer) // 保存錢箱設定
        array.put(obj)
    }
    prefs.edit().putString("devices_json", array.toString()).apply()
}

fun loadDevicesFromPrefs(context: Context): List<Device> {
    val prefs = context.getSharedPreferences("TakoAppPrefs", Context.MODE_PRIVATE)
    val json = prefs.getString("devices_json", null)
    if (json == null) {
        return listOf(
            Device("0", "📡 店內 Wi-Fi (SSID)", "偵測中...", troubleshootingMsg = "1. 確認平板 Wi-Fi 已開啟\n2. 確認是否連接至 TAKOPOS 網路"),
            Device("1", "🌐 Router (分享器)", "192.168.123.1", troubleshootingMsg = "1. 檢查分享器電源\n2. 檢查網路線是否鬆脫\n3. 嘗試重新插拔分享器電源"),
            Device("2", "🌍 Modem (小烏龜)", "192.168.1.1", troubleshootingMsg = "1. 檢查小烏龜燈號(Alarm是否紅燈)\n2. 撥打中華電信客服 0800-080128")
        )
    }
    val list = mutableListOf<Device>()
    val array = JSONArray(json)
    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(Device(
            obj.getString("id"),
            obj.getString("name"),
            obj.getString("ip"),
            if (obj.has("port")) obj.getInt("port") else null,
            troubleshootingMsg = obj.getString("troubleshootingMsg"),
            hasCashDrawer = if (obj.has("hasCashDrawer")) obj.getBoolean("hasCashDrawer") else false
        ))
    }
    return list
}

fun saveApksToPrefs(context: Context, apks: List<ApkInfo>) {
    val prefs = context.getSharedPreferences("TakoAppPrefs", Context.MODE_PRIVATE)
    val array = JSONArray()
    apks.forEach {
        val obj = JSONObject()
        obj.put("id", it.id); obj.put("appName", it.appName); obj.put("packageName", it.packageName)
        obj.put("latestVersion", it.latestVersion); obj.put("downloadUrl", it.downloadUrl)
        array.put(obj)
    }
    prefs.edit().putString("apks_json", array.toString()).apply()
}

fun loadApksFromPrefs(context: Context): List<ApkInfo> {
    val prefs = context.getSharedPreferences("TakoAppPrefs", Context.MODE_PRIVATE)
    val json = prefs.getString("apks_json", null) ?: return emptyList()
    val list = mutableListOf<ApkInfo>()
    val array = JSONArray(json)
    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(ApkInfo(obj.getString("id"), obj.getString("appName"), obj.getString("packageName"), obj.getString("latestVersion"), obj.getString("downloadUrl")))
    }
    return list
}

// ==========================================
// ☁️ 雲端 API 串接
// ==========================================

suspend fun fetchCloudApkData(storeName: String, storeId: String): List<ApkInfo>? = withContext(Dispatchers.IO) {
    try {
        val authCsv = URL("$GOOGLE_SHEET_AUTH_CSV_URL&t=${System.currentTimeMillis()}").readText()
        var isAuthorized = false
        for (line in authCsv.lines()) {
            val cols = line.split(",").map { it.trim() }
            if (cols.size >= 2 && cols[0] == storeName && cols[1] == storeId) { isAuthorized = true; break }
        }
        if (!isAuthorized) return@withContext null

        val apkCsv = URL("$GOOGLE_SHEET_APK_CSV_URL&t=${System.currentTimeMillis()}").readText()
        val list = mutableListOf<ApkInfo>()
        for (line in apkCsv.lines().drop(1)) {
            val cols = line.split(",").map { it.trim() }
            if (cols.size >= 5) {
                val targetStoreId = cols[0]
                if (targetStoreId == storeId || targetStoreId.uppercase() == "ALL") {
                    list.add(ApkInfo(cols[1], cols[2], cols[3], cols[4], cols[5]))
                }
            }
        }
        list
    } catch (e: Exception) { e.printStackTrace(); null }
}
