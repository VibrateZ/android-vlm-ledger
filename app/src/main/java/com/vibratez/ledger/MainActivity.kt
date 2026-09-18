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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import com.vibratez.ledger.ledger.LedgerDecision
import com.vibratez.ledger.budget.BudgetStore
import com.vibratez.ledger.ledger.LedgerStore
import com.vibratez.ledger.ledger.PendingReview
import com.vibratez.ledger.ledger.PendingReviewStore
import com.vibratez.ledger.background.LedgerWorkScheduler
import com.vibratez.ledger.background.ScreenshotObserverService
import com.vibratez.ledger.ledger.LedgerExportFormat
import com.vibratez.ledger.ledger.LedgerExporter
import com.vibratez.ledger.ledger.ScreenshotQueueStore
import com.vibratez.ledger.ledger.ScreenshotQueueSummary
import com.vibratez.ledger.photo.AutoBookSaveState
import com.vibratez.ledger.photo.MediaStorePhotoRepository
import com.vibratez.ledger.photo.InstalledApp
import com.vibratez.ledger.photo.InstalledAppRepository
import com.vibratez.ledger.photo.PhotoProcessingResult
import com.vibratez.ledger.photo.PhotoProcessor
import com.vibratez.ledger.security.SecureSettings
import com.vibratez.ledger.security.PackageFilterMode
import com.vibratez.ledger.statement.OfficialStatementImporter
import com.vibratez.ledger.statement.StatementParseResult
import com.vibratez.ledger.statement.StatementPreview
import com.vibratez.ledger.ui.LedgerConfirmationEditor
import com.vibratez.ledger.vlm.ConnectionResult
import com.vibratez.ledger.vlm.OpenAiVlmClient
import com.vibratez.ledger.vlm.VlmApiProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Currency
import java.util.Locale
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startupSettings = SecureSettings(this).load()
        LedgerWorkScheduler.reconcile(
            this,
            startupSettings.cloudEnabled &&
                startupSettings.backgroundAutoProcessingEnabled &&
                hasFullPhotoReadPermission(this),
        )
        if (startupSettings.cloudEnabled && startupSettings.backgroundAutoProcessingEnabled &&
            hasFullPhotoReadPermission(this)
        ) {
            runCatching { ScreenshotObserverService.start(this) }
        }
        setContent {
            LedgerTheme {
                LedgerApp()
            }
        }
    }
}

@Composable
private fun LedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) LedgerDarkColors else LedgerLightColors,
        typography = LedgerTypography,
        shapes = LedgerShapes,
        content = content,
    )
}

private val LedgerDeepGreen = Color(0xFF263D37)
private val LedgerMint = Color(0xFF7D958D)
private val LedgerGold = Color(0xFFB99249)

private val LedgerLightColors = lightColorScheme(
    primary = LedgerDeepGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E8E5),
    onPrimaryContainer = Color(0xFF1B302A),
    secondary = Color(0xFF5F6D68),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EAE8),
    onSecondaryContainer = Color(0xFF303936),
    tertiary = Color(0xFF735B27),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2E5C5),
    onTertiaryContainer = Color(0xFF392E16),
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF202321),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF202321),
    surfaceVariant = Color(0xFFEBEDEB),
    onSurfaceVariant = Color(0xFF5C625F),
    outline = Color(0xFF858B88),
    error = Color(0xFFBA1A1A),
)

private val LedgerDarkColors = darkColorScheme(
    primary = LedgerMint,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF344C45),
    onPrimaryContainer = Color(0xFFD5E4DF),
    secondary = Color(0xFFBCC5C1),
    onSecondary = Color(0xFF29332F),
    secondaryContainer = Color(0xFF3D4542),
    onSecondaryContainer = Color(0xFFDCE3E0),
    tertiary = LedgerGold,
    onTertiary = Color(0xFF442F00),
    tertiaryContainer = Color(0xFF604600),
    onTertiaryContainer = Color(0xFFFFDEA0),
    background = Color(0xFF171918),
    onBackground = Color(0xFFE5E7E5),
    surface = Color(0xFF202321),
    onSurface = Color(0xFFE5E7E5),
    surfaceVariant = Color(0xFF303431),
    onSurfaceVariant = Color(0xFFC5CAC7),
    outline = Color(0xFF929895),
    error = Color(0xFFFFB4AB),
)

private val LedgerTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
)

private val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

private enum class AppPage(val title: String) {
    LEDGER("账本"),
    RECOGNITION("识别"),
    SETTINGS("设置"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val secureSettings = remember { SecureSettings(context.applicationContext) }
    val photoRepository = remember { MediaStorePhotoRepository(context.applicationContext) }
    val ledgerStore = remember { LedgerStore(context.applicationContext) }
    val budgetStore = remember { BudgetStore(context.applicationContext) }
    val pendingReviewStore = remember { PendingReviewStore(context.applicationContext) }
    val screenshotQueueStore = remember { ScreenshotQueueStore(context.applicationContext) }
    val ledgerExporter = remember { LedgerExporter(context.applicationContext, ledgerStore) }
    val statementImporter = remember {
        OfficialStatementImporter(context.applicationContext, ledgerStore)
    }
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
    var currentPage by remember { mutableStateOf(AppPage.LEDGER) }
    var protocol by remember { mutableStateOf(settings.protocol) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var timeout by remember { mutableStateOf(settings.timeoutSeconds.toString()) }
    var keepOriginalCopies by remember { mutableStateOf(settings.keepOriginalCopies) }
    var autoDeleteAfterBook by remember { mutableStateOf(settings.autoDeleteAfterBook) }
    var packageFilterMode by remember { mutableStateOf(settings.packageFilterMode) }
    var packageNames by remember { mutableStateOf(settings.packageNames) }
    var showAppSelector by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loadingInstalledApps by remember { mutableStateOf(false) }
    var retryBaseMinutes by remember { mutableStateOf(settings.retryBaseMinutes.toString()) }
    var retryMaxMinutes by remember { mutableStateOf(settings.retryMaxMinutes.toString()) }
    var maxRetryAttempts by remember { mutableStateOf(settings.maxRetryAttempts.toString()) }
    var enabledLedgerFields by remember { mutableStateOf(settings.enabledLedgerFields.joinToString(",")) }
    var apiKey by remember { mutableStateOf("") }
    var showConsent by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var connectionText by remember { mutableStateOf<String?>(null) }
    var processingResults by remember { mutableStateOf<List<PhotoProcessingResult>>(emptyList()) }
    var deleteMessage by remember { mutableStateOf<String?>(null) }
    var ledgerCount by remember { mutableIntStateOf(0) }
    var pendingReviews by remember { mutableStateOf<List<PendingReview>>(emptyList()) }
    var hasPhotoPermission by remember { mutableStateOf(hasPhotoReadPermission(context)) }
    var pendingLegacyDelete by remember { mutableStateOf<Uri?>(null) }
    var pendingLegacyDeleteSourceUri by remember { mutableStateOf<String?>(null) }
    var mediaStoreChanged by remember { mutableStateOf(false) }
    var bookedPhotoPrompts by remember {
        mutableStateOf<List<BookedPhotoPrompt>>(emptyList())
    }
    var statementPreview by remember { mutableStateOf<StatementPreview?>(null) }
    var statementImportMessage by remember { mutableStateOf<String?>(null) }
    var statementImportBusy by remember { mutableStateOf(false) }
    var queueSummary by remember { mutableStateOf(ScreenshotQueueSummary(0, 0)) }
    var pendingDeleteUris by remember { mutableStateOf<List<String>>(emptyList()) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    DisposableEffect(
        photoRepository,
        settings.backgroundAutoProcessingEnabled,
        hasPhotoPermission,
    ) {
        val registration = photoRepository.observeChanges {
            mediaStoreChanged = true
            if (settings.backgroundAutoProcessingEnabled && hasFullPhotoReadPermission(context)) {
                LedgerWorkScheduler.enqueueNow(context)
            }
        }
        onDispose { registration.close() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPhotoPermission = hasFullPhotoReadPermission(context)
        LedgerWorkScheduler.reconcile(
            context,
            hasPhotoPermission && settings.cloudEnabled && settings.backgroundAutoProcessingEnabled,
        )
        if (hasPhotoPermission && settings.cloudEnabled && settings.backgroundAutoProcessingEnabled) {
            runCatching { ScreenshotObserverService.start(context) }
        }
        if (!hasPhotoPermission) {
            scope.launch { snackbar.showSnackbar("未获得照片权限，自动识别已暂停") }
        }
    }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        val completedUris = pendingDeleteUris
        pendingDeleteUris = emptyList()
        deleteMessage = if (it.resultCode == android.app.Activity.RESULT_OK) {
            scope.launch {
                screenshotQueueStore.markDeleteState(completedUris, ScreenshotQueueStore.DELETE_DELETED)
                queueSummary = screenshotQueueStore.summary()
            }
            "已按系统确认删除截图"
        } else {
            scope.launch {
                screenshotQueueStore.markDeleteState(completedUris, ScreenshotQueueStore.DELETE_PENDING)
                queueSummary = screenshotQueueStore.summary()
            }
            "已保留截图"
        }
    }
    val legacyDeletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val uri = pendingLegacyDelete
        val sourceUri = pendingLegacyDeleteSourceUri
        pendingLegacyDelete = null
        pendingLegacyDeleteSourceUri = null
        if (!granted || uri == null || sourceUri == null) {
            deleteMessage = "未获得删除权限，已保留截图"
        } else {
            scope.launch {
                val deleted = deleteDirectly(context, uri)
                screenshotQueueStore.markDeleteState(
                    listOf(sourceUri),
                    if (deleted) ScreenshotQueueStore.DELETE_DELETED else ScreenshotQueueStore.DELETE_PENDING,
                )
                queueSummary = screenshotQueueStore.summary()
                pendingDeleteUris = emptyList()
                deleteMessage = if (deleted) {
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
    val statementPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        statementImportBusy = true
        statementPreview = null
        statementImportMessage = null
        scope.launch {
            try {
                when (val result = statementImporter.preview(uri)) {
                    is StatementParseResult.Valid -> {
                        statementPreview = result.preview
                        statementImportMessage = if (result.preview.transactions.isEmpty()) {
                            "格式验证通过，但没有可导入的已完成收支记录"
                        } else {
                            "验证完成，请核对汇总后确认导入"
                        }
                    }
                    is StatementParseResult.Invalid -> statementImportMessage = result.reason
                }
            } catch (_: Exception) {
                statementImportMessage = "账单读取失败，请确认文件未加密且格式完整"
            } finally {
                statementImportBusy = false
            }
        }
    }
    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                ledgerExporter.export(uri, LedgerExportFormat.CSV, settings.enabledLedgerFields)
            }.onSuccess { snackbar.showSnackbar("已导出 $it 条账目") }
                .onFailure { snackbar.showSnackbar("CSV 导出失败") }
        }
    }
    val xlsxExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                ledgerExporter.export(uri, LedgerExportFormat.XLSX, settings.enabledLedgerFields)
            }.onSuccess { snackbar.showSnackbar("已导出 $it 条账目") }
                .onFailure { snackbar.showSnackbar("XLSX 导出失败") }
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
        val originalUri = uri
        val deleteUri = resolveMediaStoreDeleteUri(context, uri)
        if (deleteUri == null) {
            deleteMessage = "所选文件不属于 MediaStore，请在系统文件应用中删除"
            return@delete
        }
        pendingDeleteUris = listOf(originalUri.toString())
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
                        DeleteAttempt.Deleted -> {
                            screenshotQueueStore.markDeleteState(
                                listOf(originalUri.toString()),
                                ScreenshotQueueStore.DELETE_DELETED,
                            )
                            queueSummary = screenshotQueueStore.summary()
                            pendingDeleteUris = emptyList()
                            deleteMessage = "已删除截图"
                        }
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
                    val deleted = deleteDirectly(context, deleteUri)
                    screenshotQueueStore.markDeleteState(
                        listOf(originalUri.toString()),
                        if (deleted) ScreenshotQueueStore.DELETE_DELETED else ScreenshotQueueStore.DELETE_PENDING,
                    )
                    queueSummary = screenshotQueueStore.summary()
                    pendingDeleteUris = emptyList()
                    deleteMessage = if (deleted) {
                        "已删除截图"
                    } else {
                        "删除失败，已保留截图"
                    }
                }
            }
            else -> {
                pendingLegacyDelete = deleteUri
                pendingLegacyDeleteSourceUri = originalUri.toString()
                legacyDeletePermissionLauncher.launch(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        ledgerCount = ledgerStore.all().size
        pendingReviews = pendingReviewStore.pending()
        queueSummary = screenshotQueueStore.summary()
    }
    LaunchedEffect(currentPage) {
        if (currentPage == AppPage.SETTINGS) queueSummary = screenshotQueueStore.summary()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(currentPage.title) },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = LedgerDeepGreen,
                    titleContentColor = Color.White,
                    navigationIconContentColor = LedgerMint,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentPage == AppPage.LEDGER,
                    onClick = { currentPage = AppPage.LEDGER },
                    icon = { Icon(Icons.Default.AutoStories, contentDescription = null) },
                    label = { Text("账本") },
                )
                NavigationBarItem(
                    selected = currentPage == AppPage.RECOGNITION,
                    onClick = { currentPage = AppPage.RECOGNITION },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    label = { Text("识别") },
                )
                NavigationBarItem(
                    selected = currentPage == AppPage.SETTINGS,
                    onClick = { currentPage = AppPage.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (currentPage == AppPage.LEDGER) {
            LedgerHomeScreen(
                budgetStore = budgetStore,
                ledgerStore = ledgerStore,
                refreshKey = ledgerCount,
                modifier = Modifier.padding(padding),
                showMessage = snackbar::showSnackbar,
            )
        } else {
            LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (currentPage == AppPage.SETTINGS) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "云端识别设置",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "照片只在你开启此开关并同意后发送到配置的 VLM 服务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
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
                                Icon(
                                    Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
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
                                        val updated = settings.copy(
                                            cloudEnabled = false,
                                            backgroundAutoProcessingEnabled = false,
                                        )
                                        secureSettings.save(updated, null)
                                        settings = secureSettings.load()
                                        LedgerWorkScheduler.reconcile(context, false)
                                        ScreenshotObserverService.stop(context)
                                    }
                                },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("后台自动处理")
                                Text(
                                    if (settings.backgroundAutoProcessingEnabled) {
                                        "应用未打开时也会扫描并发送新截图"
                                    } else {
                                        "默认关闭，仅手动识别"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.backgroundAutoProcessingEnabled,
                                onCheckedChange = { checked ->
                                    if (checked && !settings.cloudEnabled) {
                                        scope.launch { snackbar.showSnackbar("请先启用云端识别") }
                                    } else if (checked && !hasPhotoPermission) {
                                        scope.launch { snackbar.showSnackbar("请先授予完整照片读取权限") }
                                    } else {
                                        val storedKey = secureSettings.readApiKey()
                                        val validation = validateSettings(
                                            settings.baseUrl,
                                            settings.model,
                                            settings.timeoutSeconds,
                                            storedKey,
                                        )
                                        if (checked && validation != null) {
                                            scope.launch { snackbar.showSnackbar(validation) }
                                            return@Switch
                                        }
                                        val updated = settings.copy(backgroundAutoProcessingEnabled = checked)
                                        secureSettings.save(updated, null)
                                        settings = secureSettings.load()
                                        LedgerWorkScheduler.reconcile(context, checked && settings.cloudEnabled)
                                        if (checked) {
                                            runCatching { ScreenshotObserverService.start(context) }
                                        } else {
                                            ScreenshotObserverService.stop(context)
                                        }
                                        if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                },
                            )
                        }
                        Text(
                            "接口协议",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            VlmApiProtocol.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = protocol == option,
                                    onClick = {
                                        protocol = option
                                        connectionText = null
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = VlmApiProtocol.entries.size,
                                    ),
                                ) {
                                    Text(
                                        if (option == VlmApiProtocol.RESPONSES) {
                                            "Responses"
                                        } else {
                                            "Chat Completions"
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            if (protocol == VlmApiProtocol.CHAT_COMPLETIONS) {
                                "硅基流动等视觉模型：Prompt 与图片在同一条消息中提交"
                            } else {
                                "支持 OpenAI Responses API 及其兼容服务"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                                        secureSettings.save(
                                            settings.copy(
                                                cloudEnabled = false,
                                                backgroundAutoProcessingEnabled = false,
                                            ),
                                            null,
                                        )
                                        settings = secureSettings.load()
                                        LedgerWorkScheduler.reconcile(context, false)
                                        ScreenshotObserverService.stop(context)
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
                                                protocol = protocol,
                                            )
                                        }
                                        testing = false
                                        connectionText = when (result) {
                                            is ConnectionResult.Success ->
                                                if (result.modelAvailable) "连接成功，模型可用"
                                                else "服务可连接，但未从模型列表确认该模型"
                                            is ConnectionResult.Failure -> if (
                                                result.detail == "chat_text_response_unsupported"
                                            ) {
                                                "连接失败：模型或中转未返回预期文本"
                                            } else {
                                                "连接失败：" + result.category.name.lowercase(Locale.ROOT)
                                            }
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
                                            protocol = protocol,
                                            baseUrl = baseUrl.trim().trimEnd('/'),
                                             model = model.trim(),
                                             timeoutSeconds = timeoutSeconds ?: 60,
                                             keepOriginalCopies = keepOriginalCopies,
                                             autoDeleteAfterBook = autoDeleteAfterBook,
                                             packageFilterMode = packageFilterMode,
                                             packageNames = packageNames,
                                             retryBaseMinutes = retryBaseMinutes.toIntOrNull() ?: 5,
                                             retryMaxMinutes = retryMaxMinutes.toIntOrNull() ?: 180,
                                             maxRetryAttempts = maxRetryAttempts.toIntOrNull() ?: 8,
                                             enabledLedgerFields = enabledLedgerFields
                                                 .split(',', '\n', ';')
                                                 .map(String::trim)
                                                 .filter(String::isNotEmpty)
                                                 .toSet(),
                                         ),
                                        key,
                                    )
                                    settings = secureSettings.load()
                                    LedgerWorkScheduler.reconcile(
                                        context,
                                        settings.cloudEnabled && settings.backgroundAutoProcessingEnabled,
                                    )
                                    if (settings.cloudEnabled && settings.backgroundAutoProcessingEnabled) {
                                        runCatching { ScreenshotObserverService.start(context) }
                                    }
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("自动化与数据", style = MaterialTheme.typography.titleMedium)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("应用内保留原图副本")
                                Text("仅成功入账后复制到应用私有目录", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = keepOriginalCopies, onCheckedChange = { keepOriginalCopies = it })
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("入账后加入删除队列")
                                Text("Android 10+ 仍需系统确认", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = autoDeleteAfterBook, onCheckedChange = { autoDeleteAfterBook = it })
                        }
                        Text("截图包名规则", style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            PackageFilterMode.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = packageFilterMode == option,
                                    onClick = { packageFilterMode = option },
                                    shape = SegmentedButtonDefaults.itemShape(index, PackageFilterMode.entries.size),
                                ) {
                                    Text(
                                        when (option) {
                                            PackageFilterMode.ALL -> "全部"
                                            PackageFilterMode.WHITELIST -> "白名单"
                                            PackageFilterMode.BLACKLIST -> "黑名单"
                                        },
                                    )
                                }
                            }
                        }
                        if (packageFilterMode != PackageFilterMode.ALL) {
                            OutlinedButton(
                                onClick = {
                                    showAppSelector = true
                                    if (installedApps.isEmpty() && !loadingInstalledApps) {
                                        loadingInstalledApps = true
                                        scope.launch {
                                            installedApps = withContext(Dispatchers.IO) {
                                                InstalledAppRepository(context.applicationContext).launcherApps()
                                            }
                                            loadingInstalledApps = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("选择应用（已选 ${packageNames.size} 个）")
                            }
                            if (packageNames.isNotEmpty()) {
                                Text(
                                    packageNames.sorted().joinToString("\n") { packageName ->
                                        installedApps.firstOrNull { it.packageName == packageName }?.label
                                            ?.let { "$it · $packageName" }
                                            ?: packageName
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = retryBaseMinutes,
                                onValueChange = { if (it.all(Char::isDigit)) retryBaseMinutes = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("初始重试/分") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                value = retryMaxMinutes,
                                onValueChange = { if (it.all(Char::isDigit)) retryMaxMinutes = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("最长等待/分") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                value = maxRetryAttempts,
                                onValueChange = { if (it.all(Char::isDigit)) maxRetryAttempts = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("最大次数") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        OutlinedTextField(
                            value = enabledLedgerFields,
                            onValueChange = { enabledLedgerFields = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("启用的记账/导出字段") },
                            supportingText = {
                                Text("amount,currency,direction,occurred_at,merchant,counterparty,item_name,platform,external_id,tag,account,payment_method,note")
                            },
                            minLines = 2,
                        )
                        Button(
                            onClick = {
                                val updated = settings.copy(
                                    keepOriginalCopies = keepOriginalCopies,
                                    autoDeleteAfterBook = autoDeleteAfterBook,
                                    packageFilterMode = packageFilterMode,
                                    packageNames = packageNames,
                                    retryBaseMinutes = retryBaseMinutes.toIntOrNull() ?: 5,
                                    retryMaxMinutes = retryMaxMinutes.toIntOrNull() ?: 180,
                                    maxRetryAttempts = maxRetryAttempts.toIntOrNull() ?: 8,
                                    enabledLedgerFields = enabledLedgerFields.split(',', '\n', ';')
                                        .map(String::trim).filter(String::isNotEmpty).toSet(),
                                )
                                secureSettings.save(updated, null)
                                settings = secureSettings.load()
                                secureSettings.setLastDiscoveryAtMillis(0L)
                                if (settings.cloudEnabled && settings.backgroundAutoProcessingEnabled) {
                                    LedgerWorkScheduler.enqueueNow(context)
                                }
                                scope.launch { snackbar.showSnackbar("自动化与字段设置已保存") }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("保存自动化设置") }
                        Text("队列：${queueSummary.active} 待处理 · ${queueSummary.pendingDelete} 待删除确认")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    secureSettings.clearApiPause()
                                    LedgerWorkScheduler.enqueueDiscoveryNow(context)
                                    LedgerWorkScheduler.restartProcessing(context)
                                    scope.launch { snackbar.showSnackbar("已恢复队列处理") }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("立即重试") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val jobs = screenshotQueueStore.pendingDeletes()
                                        if (jobs.isEmpty()) {
                                            snackbar.showSnackbar("没有待删除截图")
                                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            val resolved = jobs.mapNotNull { job ->
                                                resolveMediaStoreDeleteUri(context, Uri.parse(job.sourceUri))
                                                    ?.let { deleteUri -> job.sourceUri to deleteUri }
                                            }
                                            if (resolved.isEmpty()) {
                                                snackbar.showSnackbar("待删除记录不是可删除的图库媒体")
                                                return@launch
                                            }
                                            val uris = resolved.map { it.second }
                                            pendingDeleteUris = resolved.map { it.first }
                                            runCatching {
                                                MediaStore.createDeleteRequest(context.contentResolver, uris)
                                            }.onSuccess { request ->
                                                deleteLauncher.launch(
                                                    IntentSenderRequest.Builder(request.intentSender).build(),
                                                )
                                            }.onFailure {
                                                pendingDeleteUris = emptyList()
                                                snackbar.showSnackbar("无法发起系统批量删除确认")
                                            }
                                        } else {
                                            requestPhotoDelete(Uri.parse(jobs.first().sourceUri))
                                            snackbar.showSnackbar("当前 Android 版本需逐张确认删除")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("确认删除截图") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { csvExportLauncher.launch("ledger-${LocalDate.now()}.csv") },
                                modifier = Modifier.weight(1f),
                            ) { Text("导出 CSV") }
                            OutlinedButton(
                                onClick = { xlsxExportLauncher.launch("ledger-${LocalDate.now()}.xlsx") },
                                modifier = Modifier.weight(1f),
                            ) { Text("导出 XLSX") }
                        }
                    }
                }
            }
            item {
                Text(
                    "应用版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
            }
            if (currentPage == AppPage.RECOGNITION) {
            item {
                Spacer(Modifier.height(4.dp))
                StatementImportCard(
                    preview = statementPreview,
                    message = statementImportMessage,
                    busy = statementImportBusy,
                    onPick = {
                        statementPickerLauncher.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/zip",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onConfirm = {
                        val preview = statementPreview ?: return@StatementImportCard
                        statementImportBusy = true
                        scope.launch {
                            try {
                                val result = statementImporter.commit(preview)
                                ledgerCount = ledgerStore.all().size
                                statementImportMessage =
                                    "已导入 ${result.imported} 条，跳过重复 ${result.duplicates} 条"
                                statementPreview = null
                            } catch (_: Exception) {
                                statementImportMessage = "账单导入失败，未完成的记录可重新导入"
                            } finally {
                                statementImportBusy = false
                            }
                        }
                    },
                    onClear = {
                        statementPreview = null
                        statementImportMessage = null
                    },
                )
            }
            item {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                )
                Text(
                    "最近截图",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                    Text(
                        "处理结果",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(processingResults) { result ->
                    ProcessingResultCard(
                        result = result,
                        onDelete = requestPhotoDelete,
                        onDismiss = {
                            processingResults = processingResults.filterNot {
                                it.candidate.uri == result.candidate.uri
                            }
                        },
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
            if (pendingReviews.isNotEmpty()) {
                item {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                    )
                    Text(
                        "后台待确认",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "后台识别结果已保存为结构化记录，确认后才会写入本地账本。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(pendingReviews, key = { it.id }) { review ->
                    PendingReviewCard(
                        review = review,
                        ledgerStore = ledgerStore,
                        reviewStore = pendingReviewStore,
                        onResolved = {
                            pendingReviews = pendingReviews.filterNot { it.id == review.id }
                            if (it) ledgerCount += 1
                        },
                        onDeleted = {
                            pendingReviews = pendingReviews.filterNot { it.id == review.id }
                        },
                    )
                }
            }
            item {
                Text(
                    "本地账目：" + ledgerCount + " 条",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
            }
        }
        }
    }

    if (showAppSelector) {
        InstalledAppSelectorDialog(
            apps = installedApps,
            selectedPackages = packageNames,
            loading = loadingInstalledApps,
            onDismiss = { showAppSelector = false },
            onConfirm = {
                packageNames = it
                showAppSelector = false
            },
        )
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
                        LedgerWorkScheduler.reconcile(
                            context,
                            settings.backgroundAutoProcessingEnabled && hasPhotoPermission,
                        )
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
private fun InstalledAppSelectorDialog(
    apps: List<InstalledApp>,
    selectedPackages: Set<String>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var selected by remember(selectedPackages, apps) { mutableStateOf(selectedPackages) }
    val visibleApps = remember(apps, search) {
        val query = search.trim()
        if (query.isEmpty()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }
    val unavailablePackages = selected.filter { selectedPackage ->
        apps.none { it.packageName == selectedPackage }
    }.sorted()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索应用或包名") },
                    singleLine = true,
                )
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(visibleApps, key = InstalledApp::packageName) { app ->
                            val checked = app.packageName in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (checked) selected - app.packageName
                                        else selected + app.packageName
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Apps,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selected = if (checked) selected - app.packageName
                                        else selected + app.packageName
                                    },
                                )
                            }
                        }
                        if (search.isBlank()) {
                            items(unavailablePackages, key = { "unavailable:$it" }) { packageName ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selected = selected - packageName }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("已保存的应用", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Checkbox(
                                        checked = true,
                                        onCheckedChange = { selected = selected - packageName },
                                    )
                                }
                            }
                        }
                        if (visibleApps.isEmpty() && unavailablePackages.isEmpty()) {
                            item {
                                Text(
                                    if (search.isBlank()) "没有可选择的应用" else "没有匹配的应用",
                                    modifier = Modifier.padding(vertical = 20.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = !loading) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PendingReviewCard(
    review: PendingReview,
    ledgerStore: LedgerStore,
    reviewStore: PendingReviewStore,
    onResolved: (Boolean) -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember(review.id) { mutableStateOf(false) }
    var saved by remember(review.id) { mutableStateOf(false) }
    var dismissed by remember(review.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("后台识别：${review.sourceUri.substringAfterLast('/').ifBlank { "截图" }}")
            LedgerConfirmationEditor(
                ledger = review.ledger,
                saving = saving,
                saved = saved,
                alreadyStored = dismissed,
                onConfirm = { corrected ->
                    if (saving || saved || dismissed) return@LedgerConfirmationEditor
                    saving = true
                    scope.launch {
                        try {
                            val record = ledgerStore.addIfAbsent(
                                corrected,
                                review.sha256,
                                sourceUri = review.sourceUri,
                                vlmModel = review.vlmModel,
                                vlmRequestId = review.vlmRequestId,
                                screenshotCapturedAtMillis = review.screenshotCapturedAtMillis,
                                localDecision = "USER_CONFIRMED",
                            )
                            reviewStore.confirm(review.id)
                            saved = record != null || ledgerStore.containsHash(review.sha256)
                            onResolved(record != null)
                        } finally {
                            saving = false
                        }
                    }
                },
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        reviewStore.dismiss(review.id)
                        dismissed = true
                        onResolved(false)
                    }
                },
                enabled = !saving && !saved && !dismissed,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("忽略此待确认记录") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (reviewStore.delete(review.id)) onDeleted()
                    }
                },
                enabled = !saving && !saved && !dismissed,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("删除疑似账目", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ProcessingResultCard(
    result: PhotoProcessingResult,
    onDelete: (Uri) -> Unit,
    onDismiss: () -> Unit,
    onAutoBookRetryStored: (Uri, String) -> Unit,
    ledgerStore: LedgerStore,
    onBooked: () -> Unit,
) {
    val cardScope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
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
                            val invalidResponse = decision.reason.startsWith("INVALID_RESPONSE:")
                            val diagnosticCode = decision.reason.substringAfter(':', missingDelimiterValue = "")
                            Text(
                                if (invalidResponse) {
                                    "模型响应无效，未采用任何模型字段（$diagnosticCode）"
                                } else {
                                    "检测到疑似支付截图：" + decision.reason
                                },
                            )
                            Text(
                                if (invalidResponse) {
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
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("删除疑似账目", color = MaterialTheme.colorScheme.error)
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
