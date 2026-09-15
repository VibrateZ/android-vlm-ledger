package com.vibratez.ledger

import android.Manifest
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import com.vibratez.ledger.ledger.LedgerDecision
import com.vibratez.ledger.ledger.LedgerStore
import com.vibratez.ledger.photo.AutoBookSaveState
import com.vibratez.ledger.photo.MediaStorePhotoRepository
import com.vibratez.ledger.photo.PhotoProcessingResult
import com.vibratez.ledger.photo.PhotoProcessor
import com.vibratez.ledger.security.SecureSettings
import com.vibratez.ledger.ui.LedgerConfirmationEditor
import com.vibratez.ledger.vlm.ConnectionResult
import com.vibratez.ledger.vlm.OpenAiVlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Currency
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LedgerTheme {
                LedgerApp()
            }
        }
    }
}

@Composable
private fun LedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val secureSettings = remember { SecureSettings(context.applicationContext) }
    val photoRepository = remember { MediaStorePhotoRepository(context.applicationContext) }
    val ledgerStore = remember { LedgerStore(context.applicationContext) }
    val vlmClient = remember {
        OpenAiVlmClient(loadSystemPrompt(context))
    }
    val processor = remember {
        PhotoProcessor(
            repository = photoRepository,
            settings = secureSettings,
            client = vlmClient,
            ledgerStore = ledgerStore,
        )
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var settings by remember { mutableStateOf(secureSettings.load()) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var timeout by remember { mutableStateOf(settings.timeoutSeconds.toString()) }
    var apiKey by remember { mutableStateOf("") }
    var showConsent by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var connectionText by remember { mutableStateOf<String?>(null) }
    var processingResults by remember { mutableStateOf<List<PhotoProcessingResult>>(emptyList()) }
    var deleteMessage by remember { mutableStateOf<String?>(null) }
    var ledgerCount by remember { mutableIntStateOf(0) }
    var hasPhotoPermission by remember { mutableStateOf(hasPhotoReadPermission(context)) }
    var pendingLegacyDelete by remember { mutableStateOf<Uri?>(null) }
    var mediaStoreChanged by remember { mutableStateOf(false) }
    var bookedPhotoPrompts by remember {
        mutableStateOf<List<BookedPhotoPrompt>>(emptyList())
    }

    DisposableEffect(photoRepository) {
        val registration = photoRepository.observeChanges { mediaStoreChanged = true }
        onDispose { registration.close() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPhotoPermission = hasFullPhotoReadPermission(context)
        if (!hasPhotoPermission) {
            scope.launch { snackbar.showSnackbar("未获得照片权限，自动识别已暂停") }
        }
    }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        deleteMessage = if (it.resultCode == android.app.Activity.RESULT_OK) {
            "已按系统确认删除截图"
        } else {
            "已保留截图"
        }
    }
    val legacyDeletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val uri = pendingLegacyDelete
        pendingLegacyDelete = null
        if (!granted || uri == null) {
            deleteMessage = "未获得删除权限，已保留截图"
        } else {
            scope.launch {
                deleteMessage = if (deleteDirectly(context, uri)) {
                    "已删除截图"
                } else {
                    "删除失败，已保留截图"
                }
            }
        }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (!settings.cloudEnabled) {
            scope.launch { snackbar.showSnackbar("请先启用云端识别") }
        } else {
            processing = true
            scope.launch {
                try {
                    val candidates = withContext(Dispatchers.IO) {
                        uris.mapNotNull(photoRepository::candidateFromUri)
                    }
                    if (candidates.isEmpty()) {
                        snackbar.showSnackbar("未能读取所选图片")
                        processingResults = emptyList()
                    } else {
                        val results = processor.processCandidates(candidates)
                        processingResults = results
                        bookedPhotoPrompts = bookedPhotoPrompts + autoBookedPrompts(results)
                        ledgerCount = ledgerStore.all().size
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    snackbar.showSnackbar("图片处理失败，请稍后重试")
                } finally {
                    processing = false
                }
            }
        }
    }
    val requestPhotoPermission = {
        permissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
        )
    }
    val requestPhotoDelete: (Uri) -> Unit = delete@ { uri ->
        val deleteUri = resolveMediaStoreDeleteUri(context, uri)
        if (deleteUri == null) {
            deleteMessage = "所选文件不属于 MediaStore，请在系统文件应用中删除"
            return@delete
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                runCatching {
                    MediaStore.createDeleteRequest(
                        context.contentResolver,
                        listOf(deleteUri),
                    )
                }.onSuccess { request: PendingIntent ->
                    deleteLauncher.launch(
                        IntentSenderRequest.Builder(request.intentSender).build(),
                    )
                }.onFailure {
                    deleteMessage = "无法发起系统删除确认，已保留截图"
                }
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                scope.launch {
                    when (val result = deleteOnAndroid10(context, deleteUri)) {
                        DeleteAttempt.Deleted -> deleteMessage = "已删除截图"
                        DeleteAttempt.Failed -> deleteMessage = "删除失败，已保留截图"
                        is DeleteAttempt.RequiresConfirmation -> {
                            deleteLauncher.launch(
                                IntentSenderRequest.Builder(
                                    result.pendingIntent.intentSender,
                                ).build(),
                            )
                        }
                    }
                }
            }
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED -> {
                scope.launch {
                    deleteMessage = if (deleteDirectly(context, deleteUri)) {
                        "已删除截图"
                    } else {
                        "删除失败，已保留截图"
                    }
                }
            }
            else -> {
                pendingLegacyDelete = deleteUri
                legacyDeletePermissionLauncher.launch(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        ledgerCount = ledgerStore.all().size
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("云端智能记账") },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("云端识别设置", style = MaterialTheme.typography.titleLarge)
                Text(
                    "照片只在你开启此开关并同意后发送到配置的 VLM 服务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Cloud, contentDescription = null)
                                Column {
                                    Text("启用云端识别")
                                    Text(
                                        if (settings.cloudEnabled) "已启用" else "默认关闭",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = settings.cloudEnabled,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        showConsent = true
                                    } else {
                                        val updated = settings.copy(cloudEnabled = false)
                                        secureSettings.save(updated, null)
                                        settings = secureSettings.load()
                                    }
                                },
                            )
                        }
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Base URL") },
                            placeholder = { Text("https://api.example.com") },
                            singleLine = true,
                            supportingText = { Text("仅支持 HTTPS 服务根地址或 /v1 API 根地址") },
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    if (settings.apiKeyConfigured) {
                                        "API Key（留空则保留当前 Key）"
                                    } else {
                                        "API Key"
                                    },
                                )
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        if (settings.apiKeyConfigured) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = {
                                        secureSettings.clearApiKey()
                                        secureSettings.save(settings.copy(cloudEnabled = false), null)
                                        settings = secureSettings.load()
                                        apiKey = ""
                                        connectionText = null
                                        scope.launch { snackbar.showSnackbar("API Key 已清除，云端识别已关闭") }
                                    },
                                ) {
                                    Text("清除 API Key")
                                }
                            }
                        }
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Model") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = timeout,
                            onValueChange = { value ->
                                if (value.all(Char::isDigit) && value.length <= 3) timeout = value
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("请求超时（秒，10-120）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (testing) return@OutlinedButton
                                    val timeoutSeconds = timeout.toIntOrNull()
                                    val key = apiKey.ifBlank { secureSettings.readApiKey().orEmpty() }
                                    val validation = validateSettings(baseUrl, model, timeoutSeconds, key)
                                    if (validation != null) {
                                        connectionText = validation
                                        return@OutlinedButton
                                    }
                                    testing = true
                                    connectionText = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            vlmClient.testConnection(
                                                baseUrl = baseUrl,
                                                apiKey = key,
                                                model = model.trim(),
                                                timeoutSeconds = timeoutSeconds!!,
                                            )
                                        }
                                        testing = false
                                        connectionText = when (result) {
                                            is ConnectionResult.Success ->
                                                if (result.modelAvailable) "连接成功，模型可用"
                                                else "服务可连接，但未从模型列表确认该模型"
                                            is ConnectionResult.Failure ->
                                                "连接失败：" + result.category.name.lowercase(Locale.ROOT)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Wifi, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(if (testing) "测试中" else "测试连接")
                            }
                            Button(
                                onClick = {
                                    val timeoutSeconds = timeout.toIntOrNull()
                                    val key = apiKey.ifBlank { null }
                                    val validation = if (settings.cloudEnabled) {
                                        validateSettings(baseUrl, model, timeoutSeconds, key ?: secureSettings.readApiKey())
                                    } else {
                                        null
                                    }
                                    if (validation != null) {
                                        scope.launch { snackbar.showSnackbar(validation) }
                                        return@Button
                                    }
                                    saving = true
                                    secureSettings.save(
                                        settings.copy(
                                            baseUrl = baseUrl.trim().trimEnd('/'),
                                            model = model.trim(),
                                            timeoutSeconds = timeoutSeconds ?: 60,
                                        ),
                                        key,
                                    )
                                    settings = secureSettings.load()
                                    apiKey = ""
                                    saving = false
                                    scope.launch { snackbar.showSnackbar("设置已保存") }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !saving,
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(if (saving) "保存中" else "保存")
                            }
                        }
                        Text(
                            "连接测试不上传照片，但供应商可能对探测请求计费。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        connectionText?.let {
                            Text(
                                it,
                                color = if (it.startsWith("连接成功")) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("最近截图", style = MaterialTheme.typography.titleLarge)
                Text(
                    "仅读取 MediaStore 中近期、已写入完成且符合截图命名/目录特征的图片；原图不复制到应用目录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = requestPhotoPermission,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(if (hasPhotoPermission) "刷新权限" else "授予权限")
                        }
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("image/*")) },
                            modifier = Modifier.weight(1f),
                            enabled = !processing,
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("选择照片")
                        }
                    }
                    Button(
                        onClick = {
                            if (!hasPhotoPermission) {
                                requestPhotoPermission()
                                return@Button
                            }
                            if (!settings.cloudEnabled) {
                                scope.launch { snackbar.showSnackbar("请先启用云端识别") }
                                return@Button
                            }
                            processing = true
                            processingResults = emptyList()
                            mediaStoreChanged = false
                            scope.launch {
                                try {
                                    val results = processor.processRecent()
                                    processingResults = results
                                    bookedPhotoPrompts = bookedPhotoPrompts + autoBookedPrompts(results)
                                    if (results.isEmpty()) {
                                        snackbar.showSnackbar("最近 30 分钟内未发现截图")
                                    }
                                    ledgerCount = ledgerStore.all().size
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    snackbar.showSnackbar("无法读取最近截图，请检查照片权限")
                                } finally {
                                    processing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !processing,
                    ) {
                        if (processing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.size(6.dp))
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(if (processing) "处理中" else "识别最近截图")
                    }
                }
                if (mediaStoreChanged && hasPhotoPermission) {
                    Text(
                        "检测到媒体库变化，可重新识别最近截图。",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                deleteMessage?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (processingResults.isNotEmpty()) {
                item {
                    Text("处理结果", style = MaterialTheme.typography.titleLarge)
                }
                items(processingResults) { result ->
                    ProcessingResultCard(
                        result = result,
                        onDelete = requestPhotoDelete,
                        onAutoBookRetryStored = { uri, amountText ->
                            ledgerCount += 1
                            bookedPhotoPrompts = bookedPhotoPrompts + BookedPhotoPrompt(
                                uri = uri,
                                amountText = amountText,
                            )
                        },
                        ledgerStore = ledgerStore,
                        onBooked = { ledgerCount += 1 },
                    )
                }
            }
            item {
                Text(
                    "本地账目：" + ledgerCount + " 条",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("确认启用云端识别") },
            text = {
                Text(
                    "当前照片将通过 HTTPS 发送到你配置的第三方 VLM 服务。请先确认服务商的留存、训练和跨境传输政策。应用不会保存原图副本。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = settings.copy(cloudEnabled = true)
                        secureSettings.save(updated, null)
                        settings = secureSettings.load()
                        showConsent = false
                    },
                ) { Text("确认启用") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text("取消") }
            },
        )
    }

    val bookedPhotoPrompt = bookedPhotoPrompts.firstOrNull()
    if (!showConsent && bookedPhotoPrompt != null) {
        AlertDialog(
            onDismissRequest = { bookedPhotoPrompts = bookedPhotoPrompts.drop(1) },
            title = { Text("账目已录入") },
            text = {
                Text(bookedPhotoPrompt.amountText + " 已保存。是否删除原截图？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        bookedPhotoPrompts = bookedPhotoPrompts.drop(1)
                        requestPhotoDelete(bookedPhotoPrompt.uri)
                    },
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("删除截图")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookedPhotoPrompts = bookedPhotoPrompts.drop(1) }) {
                    Text("保留截图")
                }
            },
        )
    }
}

@Composable
private fun ProcessingResultCard(
    result: PhotoProcessingResult,
    onDelete: (Uri) -> Unit,
    onAutoBookRetryStored: (Uri, String) -> Unit,
    ledgerStore: LedgerStore,
    onBooked: () -> Unit,
) {
    val cardScope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(result.candidate.displayName, style = MaterialTheme.typography.titleMedium)
            when (result) {
                is PhotoProcessingResult.Failed -> {
                    Text("未记账：" + result.reason, color = MaterialTheme.colorScheme.error)
                    Text(
                        if (result.retryable) "可稍后重试" else "请检查设置或照片权限",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is PhotoProcessingResult.Completed -> {
                    when (val decision = result.decision) {
                        is LedgerDecision.AutoBook -> {
                            var bookingState by remember(result.sha256, result.autoBookSaveState) {
                                mutableStateOf(result.autoBookSaveState.toBookingState())
                            }
                            Text(
                                when (bookingState) {
                                    BookingState.Loading -> "正在保存账目…"
                                    BookingState.Stored ->
                                        "已录入 " +
                                            formatAmount(decision.ledger.amountMinor, decision.ledger.currency) +
                                            " · " + (decision.ledger.suggestedTag ?: "未分类")
                                    BookingState.AlreadyStored -> "已存在相同账目"
                                    BookingState.Failed -> "账目保存失败，截图已保留"
                                },
                                color = if (bookingState == BookingState.Failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            if (bookingState == BookingState.Stored ||
                                bookingState == BookingState.AlreadyStored
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onDelete(result.candidate.uri) }) {
                                        Icon(Icons.Default.Delete, contentDescription = null)
                                        Spacer(Modifier.size(6.dp))
                                        Text("删除截图")
                                    }
                                    Text(
                                        "可保留原图",
                                        modifier = Modifier.align(Alignment.CenterVertically),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            } else if (bookingState == BookingState.Failed) {
                                OutlinedButton(
                                    onClick = {
                                        bookingState = BookingState.Loading
                                        cardScope.launch {
                                            try {
                                                val record = ledgerStore.addIfAbsent(
                                                    decision.ledger,
                                                    result.sha256,
                                                    sourceUri = result.candidate.uri.toString(),
                                                    vlmModel = result.vlmModel,
                                                    vlmRequestId = result.vlmRequestId,
                                                    screenshotCapturedAtMillis =
                                                        result.candidate.capturedAtMillis,
                                                    localDecision = "AUTO_BOOK",
                                                )
                                                bookingState = if (record == null) {
                                                    BookingState.AlreadyStored
                                                } else {
                                                    onAutoBookRetryStored(
                                                        result.candidate.uri,
                                                        formatAmount(
                                                            decision.ledger.amountMinor,
                                                            decision.ledger.currency,
                                                        ),
                                                    )
                                                    BookingState.Stored
                                                }
                                            } catch (error: CancellationException) {
                                                throw error
                                            } catch (_: Exception) {
                                                bookingState = BookingState.Failed
                                            }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null)
                                    Spacer(Modifier.size(6.dp))
                                    Text("重试保存")
                                }
                            }
                        }
                        is LedgerDecision.NeedsConfirmation -> {
                            Text(
                                if (decision.reason == "INVALID_RESPONSE") {
                                    "模型响应无效，未采用任何模型字段"
                                } else {
                                    "检测到疑似支付截图：" + decision.reason
                                },
                            )
                            Text(
                                if (decision.reason == "INVALID_RESPONSE") {
                                    "如确认这是交易截图，请在空白表单中填写并核对后录入。"
                                } else {
                                    "请核对并修正以下字段后再录入。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            var confirming by remember(result.sha256) { mutableStateOf(false) }
                            var confirmed by remember(result.sha256) { mutableStateOf(false) }
                            var alreadyStored by remember(result.sha256) { mutableStateOf(false) }
                            var saveFailed by remember(result.sha256) { mutableStateOf(false) }
                            LedgerConfirmationEditor(
                                ledger = decision.ledger,
                                saving = confirming,
                                saved = confirmed,
                                alreadyStored = alreadyStored,
                                onConfirm = { correctedLedger ->
                                    if (confirming || confirmed || alreadyStored) {
                                        return@LedgerConfirmationEditor
                                    }
                                    confirming = true
                                    saveFailed = false
                                    cardScope.launch {
                                        try {
                                            val saved = ledgerStore.addIfAbsent(
                                                correctedLedger,
                                                result.sha256,
                                                sourceUri = result.candidate.uri.toString(),
                                                vlmModel = result.vlmModel,
                                                vlmRequestId = result.vlmRequestId,
                                                screenshotCapturedAtMillis = result.candidate.capturedAtMillis,
                                                localDecision = "USER_CONFIRMED",
                                            )
                                            confirmed = saved != null
                                            alreadyStored = saved == null
                                            if (saved != null) onBooked()
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (_: Exception) {
                                            saveFailed = true
                                        } finally {
                                            confirming = false
                                        }
                                    }
                                },
                            )
                            if (saveFailed) {
                                Text(
                                    "账目保存失败，请稍后重试",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        is LedgerDecision.Rejected -> Text("已忽略：" + decision.reason)
                        LedgerDecision.Duplicate -> Text("重复图片，未再次处理")
                    }
                }
            }
        }
    }
}

private enum class BookingState {
    Loading,
    Stored,
    AlreadyStored,
    Failed,
}

private data class BookedPhotoPrompt(
    val uri: Uri,
    val amountText: String,
)

private fun autoBookedPrompts(
    results: List<PhotoProcessingResult>,
): List<BookedPhotoPrompt> = results.mapNotNull { result ->
    val completed = result as? PhotoProcessingResult.Completed ?: return@mapNotNull null
    val decision = completed.decision as? LedgerDecision.AutoBook ?: return@mapNotNull null
    if (completed.autoBookSaveState != AutoBookSaveState.STORED) return@mapNotNull null
    BookedPhotoPrompt(
        uri = completed.candidate.uri,
        amountText = formatAmount(decision.ledger.amountMinor, decision.ledger.currency),
    )
}

private fun AutoBookSaveState.toBookingState(): BookingState = when (this) {
    AutoBookSaveState.STORED -> BookingState.Stored
    AutoBookSaveState.ALREADY_STORED -> BookingState.AlreadyStored
    AutoBookSaveState.FAILED,
    AutoBookSaveState.NOT_APPLICABLE,
    -> BookingState.Failed
}

private sealed interface DeleteAttempt {
    data object Deleted : DeleteAttempt
    data object Failed : DeleteAttempt
    data class RequiresConfirmation(val pendingIntent: PendingIntent) : DeleteAttempt
}

private suspend fun deleteDirectly(context: android.content.Context, uri: Uri): Boolean =
    withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun deleteOnAndroid10(
    context: android.content.Context,
    uri: Uri,
): DeleteAttempt = withContext(Dispatchers.IO) {
    try {
        if (context.contentResolver.delete(uri, null, null) > 0) {
            DeleteAttempt.Deleted
        } else {
            DeleteAttempt.Failed
        }
    } catch (error: RecoverableSecurityException) {
        DeleteAttempt.RequiresConfirmation(error.userAction.actionIntent)
    } catch (_: Exception) {
        DeleteAttempt.Failed
    }
}

private fun resolveMediaStoreDeleteUri(
    context: android.content.Context,
    uri: Uri,
): Uri? {
    if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) return uri
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
}

private fun hasPhotoReadPermission(context: android.content.Context): Boolean {
    return hasFullPhotoReadPermission(context)
}

private fun hasFullPhotoReadPermission(context: android.content.Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val fullGrant = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val selectedGrant = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
        // A selected-photo grant is useful for manual selection, but not enough for
        // automatic recent-screenshot discovery.
        if (!fullGrant && selectedGrant) return false
    }
    return fullGrant
}

private fun validateSettings(
    baseUrl: String,
    model: String,
    timeoutSeconds: Int?,
    apiKey: String?,
): String? {
    val trimmedUrl = baseUrl.trim()
    if (trimmedUrl.length > 2_048) return "Base URL 过长"
    val parsedUrl = runCatching { java.net.URI(trimmedUrl) }.getOrNull()
        ?: return "Base URL 格式无效"
    if (!parsedUrl.scheme.equals("https", ignoreCase = true) || parsedUrl.host.isNullOrBlank()) {
        return "Base URL 必须是有效的 HTTPS 地址"
    }
    if (parsedUrl.userInfo != null || parsedUrl.query != null || parsedUrl.fragment != null) {
        return "Base URL 不得包含查询串、片段或用户信息"
    }
    val path = parsedUrl.path.orEmpty().trimEnd('/')
    if (path.isNotEmpty() && path != "/v1" && !path.endsWith("/v1")) {
        return "Base URL 只能填写服务根地址或 /v1 API 根地址"
    }
    if (!Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$").matches(model.trim())) {
        return "Model 格式无效，仅允许字母、数字及 . _ : / -"
    }
    if (timeoutSeconds !in 10..120) return "超时必须为 10-120 秒"
    if (apiKey.isNullOrBlank()) return "请填写 API Key，或保留已保存的 Key"
    if (apiKey.length > 4_096 || apiKey.any(Char::isISOControl)) return "API Key 格式无效"
    return null
}

internal fun formatAmount(amountMinor: Long?, currency: String?): String {
    if (amountMinor == null || currency == null) return "金额待确认"
    val fractionDigits = runCatching {
        Currency.getInstance(currency).defaultFractionDigits.takeIf { it in 0..6 }
    }.getOrNull() ?: return "$currency $amountMinor"
    val majorAmount = BigDecimal.valueOf(amountMinor)
        .movePointLeft(fractionDigits)
        .setScale(fractionDigits)
        .toPlainString()
    return if (currency == "CNY") {
        "¥$majorAmount"
    } else {
        "$currency $majorAmount"
    }
}

private fun loadSystemPrompt(context: android.content.Context): String {
    return runCatching {
        context.assets.open("ledger_system_prompt.txt").bufferedReader().use { it.readText() }
    }.getOrDefault(
        "你是 Ledger VLM。严格按照 ledger.v1 只输出一个 JSON 对象，并返回程序要求的全部字段。",
    )
}
