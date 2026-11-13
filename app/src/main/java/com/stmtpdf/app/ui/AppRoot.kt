package com.stmtpdf.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stmtpdf.app.R
import com.stmtpdf.app.engine.PdfEngine
import com.stmtpdf.app.export.Exporter
import com.stmtpdf.app.i18n.AppLang
import com.stmtpdf.app.i18n.I18n
import com.stmtpdf.app.i18n.LocaleStore
import com.stmtpdf.app.licensing.LicenceMerge
import com.stmtpdf.app.licensing.LicenceState
import com.stmtpdf.app.licensing.LicenceStore
import com.stmtpdf.app.licensing.LicenceVerifier
import com.stmtpdf.app.licensing.MachineCode
import com.stmtpdf.app.licensing.TrialLedger
import com.stmtpdf.app.net.Api
import com.stmtpdf.app.storage.DraftMeta
import com.stmtpdf.app.storage.HistoryTask
import com.stmtpdf.app.storage.LocalStats
import com.stmtpdf.app.storage.StatementStore
import com.stmtpdf.app.update.UpdateChecker
import com.stmtpdf.app.update.UpdateCheckResult
import com.stmtpdf.app.update.UpdateDownloadService
import com.stmtpdf.app.update.UpdateErrorText
import com.stmtpdf.app.update.UpdateInfo
import com.stmtpdf.app.update.UpdatePhase
import com.stmtpdf.app.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object AppEvents {
    val pendingReturn = mutableStateOf<Uri?>(null)
}

/** 首页最近记录折叠条数；「查看全部」每次再展开一页。 */
private const val RECENT_HOME_LIMIT = 5
private const val RECENT_PAGE_SIZE = 10

@Composable
fun StmtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Teal,
            onPrimary = Pal.Dark,
            background = Pal.Bg,
            onBackground = Pal.Text,
            surface = Pal.Panel,
            onSurface = Pal.Text,
            surfaceVariant = Pal.Panel,
            onSurfaceVariant = Pal.Muted,
            outline = Pal.Line
        ),
        content = content
    )
}

class AppState(private val context: Context) {
    var licence by mutableStateOf(LicenceStore.load(context))
        private set
    var trialRemaining by mutableStateOf(TrialLedger.remaining(context))
        private set

    val active: Boolean get() = licence?.current() != null

    fun capacity(): Int = if (active) licence?.remaining() ?: 0 else trialRemaining

    fun refresh() {
        licence = LicenceStore.load(context)
        trialRemaining = TrialLedger.remaining(context)
    }

    fun consume(pages: Int) {
        val current = licence
        val period = current?.current()
        if (current != null && period != null) {
            period.used += pages
            LicenceStore.save(context, current)
        } else {
            TrialLedger.consume(context, pages)
        }
        LocalStats.recordUsage(context, pages)
        refresh()
    }

    /** 合并应用新授权：重复应用同一订单不清零额度，续费保留历史周期。 */
    fun apply(state: LicenceState) {
        LicenceStore.save(context, LicenceMerge.merge(licence ?: LicenceStore.load(context), state))
        // 成功激活的订单才清除待激活标记
        if (state.order.isNotEmpty() && state.order == LicenceStore.pendingOrder(context)) {
            LicenceStore.clearPendingOrder(context)
        }
        TrialLedger.void(context, state.order)
        refresh()
    }
}

data class Plan(val code: String, val name: String, val cycle: String, val pages: Int, val price: Int) {
    val tier: Int get() = code.getOrNull(1)?.digitToIntOrNull() ?: 1
}

private val PLANS = listOf(
    Plan("t1_monthly", "Starter", "monthly", 400, 15),
    Plan("t2_monthly", "Pro", "monthly", 1000, 29),
    Plan("t3_monthly", "Scale", "monthly", 4000, 39),
    Plan("t1_annual", "Starter", "annual", 4800, 150),
    Plan("t2_annual", "Pro", "annual", 12000, 290),
    Plan("t3_annual", "Scale", "annual", 48000, 390)
)

@Composable
fun AppRoot(engine: PdfEngine, initialUri: Uri?) {
    val context = LocalContext.current
    val state = remember { AppState(context) }
    var tab by remember { mutableStateOf(0) }
    var compareOpen by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf(LocaleStore.load(context)) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showSplash by remember { mutableStateOf(true) }

    val update by UpdateState.flow.collectAsState()
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdatePrompt by remember { mutableStateOf(false) }
    var showInstallPermission by remember { mutableStateOf(false) }
    var showInstallAgain by remember { mutableStateOf(false) }
    var waitingPermission by remember { mutableStateOf(false) }
    var installAfterPermission by remember { mutableStateOf(false) }
    var installLaunched by remember { mutableStateOf(false) }
    var autoInstallAttempted by remember { mutableStateOf(false) }
    var appResumed by remember { mutableStateOf(true) }
    var checkNote by remember { mutableStateOf("") }
    val updateScope = rememberCoroutineScope()
    val notifyScope = rememberCoroutineScope()

    /** 统一的操作反馈：底部 Snackbar，可带一个「分享」类动作。 */
    fun notify(text: String, action: (() -> Unit)? = null) {
        notifyScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = text,
                actionLabel = if (action != null) I18n.t(lang, "share") else null,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) action?.invoke()
        }
    }
    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }

    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun startDownload(info: UpdateInfo) {
        autoInstallAttempted = false
        installLaunched = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!canInstall(context)) {
            installAfterPermission = false
            showInstallPermission = true
            return
        }
        showUpdatePrompt = false
        UpdateDownloadService.start(context, info)
    }

    fun checkUpdate(manual: Boolean) {
        updateScope.launch {
            if (manual) checkNote = "Checking…"
            val result = withContext(Dispatchers.IO) { UpdateChecker.checkDetailed(currentVersion) }
            when (result) {
                is UpdateCheckResult.Available -> {
                    updateInfo = result.info
                    showUpdatePrompt = true
                    checkNote = ""
                }
                UpdateCheckResult.UpToDate ->
                    checkNote = if (manual) "You're on the latest version" else ""
                UpdateCheckResult.Failed ->
                    checkNote = if (manual) "Check failed. Try again later." else ""
            }
        }
    }

    // 从「安装未知应用」设置页返回后自动续跑，无需再点一次
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    appResumed = true
                    if (waitingPermission && canInstall(context)) {
                        waitingPermission = false
                        showInstallPermission = false
                        if (installAfterPermission) {
                            installAfterPermission = false
                            autoInstallAttempted = true
                            val apk = UpdateState.flow.value
                            if (apk.phase == UpdatePhase.READY && apk.apkPath.isNotEmpty()) {
                                installLaunched = true
                                if (!installApk(context, File(apk.apkPath))) {
                                    installLaunched = false
                                    UpdateState.set(
                                        apk.copy(
                                            phase = UpdatePhase.FAILED,
                                            error = "Unable to open the installer."
                                        )
                                    )
                                }
                            }
                        } else {
                            updateInfo?.let { UpdateDownloadService.start(context, it) }
                        }
                    }
                    if (installLaunched && UpdateState.flow.value.phase == UpdatePhase.READY) {
                        installLaunched = false
                        showInstallAgain = true
                    }
                }
                Lifecycle.Event.ON_PAUSE -> appResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 下载完成：直接拉起系统安装器（后台时不弹，交给通知栏「点击安装」）
    LaunchedEffect(update.phase, update.apkPath, appResumed) {
        if (update.phase == UpdatePhase.READY && update.apkPath.isNotEmpty() &&
            !autoInstallAttempted && appResumed
        ) {
            if (!canInstall(context)) {
                installAfterPermission = true
                showInstallPermission = true
                return@LaunchedEffect
            }
            autoInstallAttempted = true
            installLaunched = true
            if (!installApk(context, File(update.apkPath))) {
                installLaunched = false
                UpdateState.set(
                    update.copy(phase = UpdatePhase.FAILED, error = "Unable to open the installer.")
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(1700)
        showSplash = false
        // 启动页淡出后检查一次更新；失败静默，不阻塞使用
        checkUpdate(manual = false)
    }
    LaunchedEffect(Unit) {
        if (initialUri != null) AppEvents.pendingReturn.value = initialUri
    }
    // 启动即与服务端试用台账对账，防止重装后白拿 10 页
    LaunchedEffect(Unit) {
        TrialLedger.refresh(context) { state.refresh() }
    }
    LaunchedEffect(AppEvents.pendingReturn.value) {
        val data = AppEvents.pendingReturn.value ?: return@LaunchedEffect
        AppEvents.pendingReturn.value = null
        val order = data.getQueryParameter("order") ?: data.getQueryParameter("ref")
        if (order.isNullOrBlank()) return@LaunchedEffect
        // 先落盘订单，断网/202 时保留，用于激活页「继续激活」
        LicenceStore.savePendingOrder(context, order)
        val result = withContext(Dispatchers.IO) {
            Api.activate(order, MachineCode.formatted(context))
        }
        val code = result.body?.optString("code") ?: ""
        val verified = if (result.ok && code.isNotBlank()) {
            LicenceVerifier.verify(context, code)
        } else {
            null
        }
        if (verified != null) {
            state.apply(verified) // apply 成功后自动清除 pending
            notify(I18n.t(lang, "msg_activated"))
            tab = 0
        } else {
            notify(I18n.t(lang, "msg_payment_pending"))
        }
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        GlowBackground()
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    androidx.compose.material3.Snackbar(
                        snackbarData = data,
                        containerColor = Color(0xFF16212A),
                        contentColor = Pal.Text,
                        actionColor = Pal.Teal
                    )
                }
            },
            bottomBar = { if (!compareOpen) PillTabBar(tab, lang) { tab = it } }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> HomeScreen(
                        engine, state, lang,
                        onLangPick = {
                            lang = it
                            LocaleStore.save(context, it)
                        },
                        onNeedActivate = { tab = 1 },
                        onFullscreen = { compareOpen = it },
                        onNotify = { text, action -> notify(text, action) }
                    )
                    1 -> ActivateScreen(
                        context, state, lang,
                        onMessage = { notify(it) }
                    )
                    else -> QuotaScreen(
                        context, state, lang,
                        onRenew = { tab = 1 },
                        onCheckUpdate = { checkUpdate(manual = true) },
                        checkNote = checkNote
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = showSplash,
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(320))
        ) {
            SplashOverlay()
        }

        val info = updateInfo
        if (showUpdatePrompt && info != null && update.phase != UpdatePhase.DOWNLOADING) {
            val sizeText = if (info.sizeBytes > 0) " · ${UpdateErrorText.mb(info.sizeBytes)}" else ""
            AlertDialog(
                onDismissRequest = { if (!info.force) showUpdatePrompt = false },
                containerColor = Pal.Panel,
                titleContentColor = Pal.Text,
                textContentColor = Pal.Muted,
                title = {
                    Text("Update available", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text(
                            "v$currentVersion → v${info.version}$sizeText",
                            fontSize = 12.sp, lineHeight = 18.sp
                        )
                        if (info.force) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "This version is no longer supported. Please update to continue.",
                                fontSize = 11.5.sp, lineHeight = 17.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { startDownload(info) }) {
                        Text("Update now", color = Pal.Teal, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    if (!info.force) {
                        TextButton(onClick = { showUpdatePrompt = false }) {
                            Text("Later", color = Pal.Muted)
                        }
                    }
                }
            )
        }

        if (update.phase == UpdatePhase.DOWNLOADING) {
            AlertDialog(
                onDismissRequest = {},
                containerColor = Pal.Panel,
                titleContentColor = Pal.Text,
                title = {
                    Text("Downloading update…", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { update.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Pal.Teal,
                            trackColor = Pal.Line
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${update.percent}% · ${UpdateErrorText.mb(update.received)} / ${UpdateErrorText.mb(update.total)}",
                            color = Pal.Muted, fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        UpdateDownloadService.cancel(context)
                        UpdateState.reset()
                        if (info?.force == true) (context as? Activity)?.finish()
                    }) {
                        Text(if (info?.force == true) "Exit app" else "Cancel", color = Pal.Muted)
                    }
                }
            )
        }

        if (showInstallPermission) {
            AlertDialog(
                onDismissRequest = { showInstallPermission = false },
                containerColor = Pal.Panel,
                titleContentColor = Pal.Text,
                textContentColor = Pal.Muted,
                title = {
                    Text("Allow app installs", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "Allow StmtPDF to install apps to finish updating.",
                        fontSize = 12.sp, lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        waitingPermission = true
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }) {
                        Text("Open settings", color = Pal.Teal, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showInstallPermission = false
                        info?.let { openWebsite(context, it.url) }
                    }) {
                        Text("Download from website", color = Pal.Muted)
                    }
                }
            )
        }

        if (showInstallAgain) {
            AlertDialog(
                onDismissRequest = { showInstallAgain = false },
                containerColor = Pal.Panel,
                titleContentColor = Pal.Text,
                textContentColor = Pal.Muted,
                title = {
                    Text("Ready to install", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "The package is already downloaded. Tap Install again to finish.",
                        fontSize = 12.sp, lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (update.apkPath.isNotEmpty()) {
                            autoInstallAttempted = true
                            installLaunched = true
                            if (!installApk(context, File(update.apkPath))) {
                                installLaunched = false
                                UpdateState.set(
                                    update.copy(
                                        phase = UpdatePhase.FAILED,
                                        error = "Unable to open the installer."
                                    )
                                )
                            }
                        }
                    }) {
                        Text("Install again", color = Pal.Teal, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showInstallAgain = false
                        UpdateState.reset()
                    }) {
                        Text("Later", color = Pal.Muted)
                    }
                }
            )
        }

        if (update.phase == UpdatePhase.FAILED) {
            AlertDialog(
                onDismissRequest = { UpdateState.reset() },
                containerColor = Pal.Panel,
                titleContentColor = Pal.Text,
                textContentColor = Pal.Muted,
                title = {
                    Text("Update failed", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        update.error.ifBlank { "Download failed. Please retry." },
                        fontSize = 12.sp, lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val retry = updateInfo
                        UpdateState.reset()
                        if (retry != null) startDownload(retry)
                    }) {
                        Text("Retry", color = Pal.Teal, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        info?.let { openWebsite(context, it.url) }
                        UpdateState.reset()
                    }) {
                        Text("Download from website", color = Pal.Muted)
                    }
                }
            )
        }
    }
}

/** 是否已允许安装未知来源应用（Android 8.0 以下默认允许）。 */
private fun canInstall(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

/** 通过 FileProvider 拉起系统安装器；返回 false 表示无法启动安装界面。 */
private fun installApk(context: Context, file: File): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}.isSuccess

/** 失败兜底：用浏览器打开安装包直链。 */
private fun openWebsite(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun SplashOverlay() {
    Column(
        Modifier.fillMaxSize().background(Pal.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.9f))

        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(26.dp)).background(Pal.Tile),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.logo_icon),
                contentDescription = "StmtPDF",
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(20.dp))
            )
        }

        Spacer(Modifier.height(30.dp))
        Text(
            "Bank PDF to tables in seconds.",
            color = Pal.Text,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 28.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Skip tedious manual data entry.",
            color = Pal.Muted, fontSize = 13.sp, textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SplashFeature("Parsed locally", "File never uploaded", Modifier.weight(1f))
            SplashFeature("Balance check", "Errors auto-flagged", Modifier.weight(1f))
            SplashFeature("Multi-format", "QBO · Excel · CSV", Modifier.weight(1f))
        }

        Spacer(Modifier.weight(0.9f))
        CircularProgressIndicator(Modifier.size(18.dp), color = Pal.Teal, strokeWidth = 1.5.dp)
        Spacer(Modifier.height(20.dp))
        Text("One device · one licence", color = Pal.Faint, fontSize = 11.sp)
        Spacer(Modifier.weight(0.5f))
        Text("© 2026 StmtPDF", color = Color(0xFF4B5054), fontSize = 10.sp)
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun SplashFeature(title: String, sub: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF14181D))
            .border(1.dp, Color(0xFF20262B), RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Pal.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Text(sub, color = Pal.Faint, fontSize = 8.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/* ---------------------------------------------------------------- Home */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    engine: PdfEngine,
    state: AppState,
    lang: AppLang,
    onLangPick: (AppLang) -> Unit,
    onNeedActivate: () -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onNotify: (String, (() -> Unit)?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 页面层级：首页 → 添加文件页 → 解析页 → 校对页（导出是校对页上的弹层），返回逐级回退
    var stage by remember { mutableStateOf(Stage.HOME) }
    // 校对页是从解析结果进来的，返回时回到解析页；从最近记录进来则回首页
    var reviewFromParse by remember { mutableStateOf(false) }
    // 已选文件队列与解析页状态（对应设计稿 02 / 07）
    var picked by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    // 解析页逐文件状态：0 等待 / 1 解析中 / 2 完成 / 3 失败
    var parseQueue by remember { mutableStateOf<List<ParseFileState>>(emptyList()) }
    var parseIndex by remember { mutableStateOf(0) }
    var parseTotal by remember { mutableStateOf(0) }
    var cancelParse by remember { mutableStateOf(false) }
    // 是否从校对页返回过：返回后解析页右上出现「下一页」
    var cameBackFromReview by remember { mutableStateOf(false) }
    // 取消时直接取消解析协程，避免旧任务与新任务同时跑同一个 WebView 引擎
    var parseJob by remember { mutableStateOf<Job?>(null) }
    // 任务令牌：旧任务收尾时不再改动新任务的界面状态 / 文件队列
    var parseToken by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var docs by remember { mutableStateOf<List<ReviewDoc>>(emptyList()) }
    // 本次会话解析的文档来源 URI：重解析时只替换同一来源的文件，避免同名不同文件被误删
    var docSources by remember { mutableStateOf<Map<Long, Uri>>(emptyMap()) }
    var activeId by remember { mutableStateOf(0L) }
    var compareId by remember { mutableStateOf<Long?>(null) }
    var activeRow by remember { mutableStateOf<ReviewRow?>(null) }
    var editingRow by remember { mutableStateOf<ReviewRow?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(LocalStats.tasks(context)) }
    // 一次「开始解析」= 一个任务：任务号只增不减，最近记录按任务展示
    var taskNo by remember { mutableStateOf(0) }
    var taskAt by remember { mutableStateOf(0L) }
    // 开始解析前的二次确认；已有结果时左滑只回结果页，不重跑
    var confirmParsing by remember { mutableStateOf(false) }
    var confirmReparse by remember { mutableStateOf(false) }
    // 导出流程：先整页选文件，再整页选格式（不再用底部浮窗）
    var exportIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var exportKind by remember { mutableStateOf("qbo") }
    var exporting by remember { mutableStateOf(false) }
    var exportDone by remember { mutableStateOf(0) }
    var exportTotal by remember { mutableStateOf(0) }
    var exportFailed by remember { mutableStateOf(0) }
    // CSV 预设：与桌面端 / 网页端保持同一套取值
    var csvPreset by remember { mutableStateOf("standard") }
    var confirmExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmExportCount by remember { mutableStateOf(0) }
    var quotaBlocked by remember { mutableStateOf(false) }
    // 导出落地：结果列表 / 可选 SAF 选择器 / 低版本存储权限
    var savedBatch by remember { mutableStateOf<List<Exporter.Saved>>(emptyList()) }
    var askLocation by remember { mutableStateOf(LocalStats.askSaveLocation(context)) }
    // 用户指定的导出目录（SAF tree），为空时用默认「下载/StmtPDF」
    var exportDir by remember { mutableStateOf(LocalStats.exportDir(context)) }
    var pendingProceed by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 多文件导出且开启「每次询问」时，选定目录后再真正开始导出
    var pendingBatch by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 校对页退出确认：有改动时先问一句，改动本身随时写草稿，不会丢
    var confirmLeaveReview by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    // 近 7 天草稿（按文件 id）：首页「最近记录」显示未核对行数
    var drafts by remember { mutableStateOf<Map<Long, DraftMeta>>(emptyMap()) }
    // 首页最近记录只显示 5 条，更多走「查看全部」全屏列表（每页 10 条）
    var showAllRecent by remember { mutableStateOf(false) }

    // 选择导出目录：长期授权，之后导出直接写进去
    val pickExportDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            // 取消选目录：丢掉这次待执行的批量导出，避免下次选目录时误触发
            pendingBatch = null
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        exportDir = uri.toString()
        LocalStats.setExportDir(context, uri.toString())
        val batch = pendingBatch
        pendingBatch = null
        batch?.invoke()
    }

    // Android 9 及以下写公共「下载」需要存储权限
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        val action = pendingProceed
        pendingProceed = null
        if (granted) action?.invoke()
        else onNotify(I18n.t(lang, "export_hint_private"), null)
    }

    val activeDoc = docs.firstOrNull { it.id == activeId }
    val compareDoc = compareId?.let { id -> docs.firstOrNull { it.id == id } }

    // 对比页全屏展示时隐藏底部页签，退出首页时复位
    DisposableEffect(Unit) { onDispose { onFullscreen(false) } }

    fun docOf(row: ReviewRow): ReviewDoc? = docs.firstOrNull { doc -> doc.rows.any { it === row } }

    /* 校对页的开关收敛到两个函数：打开时补齐 activeId，关闭时清掉所有浮层状态，
       避免残留的 activeRow / editingRow 让下次进入看起来「点了没反应」。
       fromParse = true 表示刚解析完，返回时回到解析页；否则（最近记录）回首页。 */
    fun openReview(doc: ReviewDoc, fromParse: Boolean = false) {
        activeId = doc.id
        compareId = doc.id
        activeRow = null
        editingRow = null
        showPicker = false
        reviewFromParse = fromParse
        stage = Stage.REVIEW
        onFullscreen(true)
    }

    /** 校对页 → 解析页（本会话解析的）或首页（从最近记录打开的）。 */
    fun closeReview() {
        compareId = null
        activeRow = null
        editingRow = null
        stage = if (reviewFromParse) Stage.PARSING else Stage.HOME
        onFullscreen(reviewFromParse)
    }

    /** 解析页 → 添加文件页：保留已选队列，停掉任务。 */
    fun cancelParsing() {
        cancelParse = true
        parseJob?.cancel()
        parseJob = null
        // 被取消的条目退回「等待」，下次进入不会一直显示解析中
        parseQueue = parseQueue.map {
            if (it.status == 1) it.copy(status = 0, percent = 0) else it
        }
        stage = Stage.PICKED
        onFullscreen(false)
    }

    /** 添加文件页 → 首页：清空本次队列与解析状态。 */
    fun backToHome() {
        picked = emptyList()
        parseQueue = emptyList()
        cameBackFromReview = false
        taskNo = 0
        taskAt = 0L
        stage = Stage.HOME
        onFullscreen(false)
    }

    /** 记录被删除 / 清空后回到首页，避免停留在已无内容的校对或解析页。 */
    fun resetToHome() {
        compareId = null
        activeRow = null
        editingRow = null
        picked = emptyList()
        activeId = 0L
        reviewFromParse = false
        taskNo = 0
        taskAt = 0L
        stage = Stage.HOME
        onFullscreen(false)
    }

    /** 把有改动的文件写成草稿（增量），没有改动就什么都不做。 */
    fun persistDrafts() {
        val targets = docs.filter { it.hasDraft }
        if (targets.isEmpty()) return
        val snapshots = targets.map { doc ->
            Triple(doc.id, doc.draftRows(), doc.unverified to doc.pending)
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                snapshots.forEach { (id, rows, counts) ->
                    StatementStore.saveDraft(context, id, rows, counts.first, counts.second)
                }
            }
            val now = System.currentTimeMillis()
            drafts = drafts + snapshots.associate { (id, _, counts) ->
                id to DraftMeta(now, counts.first, counts.second)
            }
        }
    }

    /** 校对页返回：没改动直接退，有改动先确认（防误退丢进度）。 */
    fun requestCloseReview() {
        if (docs.none { it.hasDraft }) {
            closeReview()
            return
        }
        confirmLeaveReview = true
    }

    /** 打开一条最近记录：内存里已有就直接进，否则从磁盘读（带草稿续接）。 */
    fun openTask(task: HistoryTask) {
        val ids = task.files.map { it.id }
        val missing = ids.filter { id -> docs.none { it.id == id } }
        if (missing.isEmpty()) {
            val first = docs.firstOrNull { it.id == ids.first() }
            if (first != null) openReview(first)
            else onNotify(I18n.t(lang, "record_gone"), null)
            return
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                missing.mapNotNull { id ->
                    val draft = StatementStore.loadDraft(context, id)
                    StatementStore.load(context, id)?.let { record ->
                        buildReviewDoc(record.json, id, record.name, record.pdfPath, draft)
                    }
                }
            }
            if (loaded.isEmpty()) {
                onNotify(I18n.t(lang, "record_gone"), null)
            } else {
                docs = docs + loaded
                // 按任务内的文件顺序进入第一份
                val first = loaded.firstOrNull { it.id == ids.first() } ?: loaded.first()
                openReview(first)
            }
        }
    }

    /** 删除一条最近记录：连解析落盘、草稿与内存态一起清掉。 */
    fun deleteTask(task: HistoryTask) {
        scope.launch {
            val ids = task.files.map { it.id }
            withContext(Dispatchers.IO) {
                LocalStats.removeTask(context, task.no)
                ids.forEach { StatementStore.delete(context, it) }
            }
            history = LocalStats.tasks(context)
            docs = docs.filterNot { it.id in ids }
            docSources = docSources - ids.toSet()
            drafts = drafts - ids.toSet()
            if (activeId in ids) resetToHome()
        }
    }

    /**
     * 选择器返回的 URI：文件名与体积放到 IO 线程读取。
     * 云盘 / 第三方 provider 在主线程做 query / openAssetFileDescriptor 会阻塞甚至被系统强杀，
     * 这一步不再占用主线程，任何 provider 异常也不会带崩应用。
     */
    fun enqueue(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (state.capacity() <= 0) {
            onNotify(I18n.t(lang, "quota_empty"), null)
            onNeedActivate()
            return
        }
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    runCatching {
                        val name = displayNameOf(context, uri)
                        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")
                            ?.use { it.length } ?: 0L
                        PickedFile(uri, name, size)
                    }.getOrNull()
                }.filterNotNull()
            }
            if (items.isEmpty()) {
                onNotify(I18n.t(lang, "pick_failed"), null)
                return@launch
            }
            // 只入队，等用户点「开始解析」再跑引擎；同一文件重复选择不重复入队
            val exists = picked.map { Triple(it.uri, it.name, it.size) }.toSet()
            picked = picked + items.filterNot { Triple(it.uri, it.name, it.size) in exists }
            status = ""
            // 只在首页 / 添加文件页切换层级，避免从其它页面误入
            if (stage == Stage.HOME || stage == Stage.PICKED) {
                stage = Stage.PICKED
                onFullscreen(false)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        enqueue(listOfNotNull(uri))
    }

    // 少数定制 ROM 不支持 ACTION_OPEN_DOCUMENT，回退到 ACTION_GET_CONTENT
    val pickerCompat = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        enqueue(listOfNotNull(uri))
    }

    // 「添加文件」走多选，一次可以把多份对账单一起加入队列
    val pickerMulti = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> enqueue(uris) }

    val pickerMultiCompat = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> enqueue(uris) }

    /** 打开系统文件选择器：launch 本身也可能因缺少对应 Activity 抛异常，不能让应用退出。 */
    fun openPicker() {
        val opened = runCatching { picker.launch(arrayOf("application/pdf")) }.isSuccess
        if (opened) return
        val fallback = runCatching { pickerCompat.launch("application/pdf") }.isSuccess
        if (!fallback) onNotify(I18n.t(lang, "pick_failed"), null)
    }

    /** 多选版本：失败时先退到 GET_MULTIPLE_CONTENTS，再退到单选。 */
    fun openPickerMulti() {
        if (runCatching { pickerMulti.launch(arrayOf("application/pdf")) }.isSuccess) return
        if (runCatching { pickerMultiCompat.launch("application/pdf") }.isSuccess) return
        openPicker()
    }

    /** 解析页里更新某一份的状态。 */
    fun updateQueue(index: Int, block: (ParseFileState) -> ParseFileState) {
        parseQueue = parseQueue.mapIndexed { i, item -> if (i == index) block(item) else item }
    }

    /** 还没解析成功（没有 docId）的文件；已完成的文件不会因为再次进入而重跑。 */
    fun pendingFiles(): List<PickedFile> = picked.filter { file ->
        parseQueue.none { it.uri == file.uri && it.status == 2 && it.docId > 0L }
    }

    /**
     * 用户点「开始解析」或从添加文件页左滑：有待解析文件才先确认；
     * 已有结果时直接回解析结果页，绝不静默重跑覆盖校对数据。
     */
    fun requestParsing() {
        if (picked.isEmpty()) return
        if (pendingFiles().isEmpty()) {
            stage = Stage.PARSING
            onFullscreen(true)
            return
        }
        confirmParsing = true
    }

    /**
     * 依次解析待处理文件；force = true 时整批重解析（需用户二次确认）。
     * 单个文件失败不影响其它文件，全部结束后停在解析页等用户点「开始校对」。
     */
    fun startParsing(force: Boolean = false) {
        if (picked.isEmpty()) return
        if (stage == Stage.PARSING && parseJob != null) return
        val capacity = state.capacity()
        if (capacity <= 0) {
            onNotify(I18n.t(lang, "quota_empty"), null)
            onNeedActivate()
            return
        }
        val targets = if (force) picked else pendingFiles()
        if (targets.isEmpty()) {
            // 全部已解析：只展示结果，不重跑
            stage = Stage.PARSING
            onFullscreen(true)
            return
        }
        // 同一批文件共用一个任务号；离开首页后重新选文件才分配新任务
        if (taskNo == 0) {
            taskNo = LocalStats.nextTaskNo(context)
            taskAt = System.currentTimeMillis()
        }
        val currentTask = taskNo
        val currentAt = taskAt
        cancelParse = false
        cameBackFromReview = false
        stage = Stage.PARSING
        onFullscreen(true)
        // 保留已完成文件的状态，只重置待解析项；队列顺序与已选列表一致
        parseQueue = picked.map { file ->
            val done = if (force) null else parseQueue.firstOrNull {
                it.uri == file.uri && it.status == 2 && it.docId > 0L
            }
            done ?: ParseFileState(file.uri, file.name, file.size)
        }
        parseIndex = 0
        parseTotal = targets.size
        progress = 0f
        parseToken += 1
        val token = parseToken
        parseJob = scope.launch {
            var done = 0
            try {
                for ((index, item) in picked.withIndex()) {
                    if (cancelParse) break
                    if (targets.none { it.uri == item.uri }) continue
                    done += 1
                    parseIndex = done
                    updateQueue(index) { it.copy(status = 1, percent = 4) }
                    progress = ((done - 1).toFloat() / targets.size)
                    try {
                        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
                        val file = File(dir, "input-" + System.currentTimeMillis() + "-" + index + ".pdf")
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(item.uri)?.use { input ->
                                file.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        val json = engine.parse(file, capacity) { no, total ->
                            val pct = (8 + 84 * (no.toFloat() / total).coerceIn(0f, 1f)).toInt()
                            progress = ((done - 1) + pct / 100f) / targets.size
                            updateQueue(index) { it.copy(percent = pct) }
                        }
                        if (cancelParse) break
                        val id = System.currentTimeMillis()
                        val pdfPath = withContext(Dispatchers.IO) {
                            StatementStore.save(context, id, item.name, file, json)
                        }
                        val doc = buildReviewDoc(json, id, item.name, pdfPath)
                        // 同一来源文件重解析：替换旧文档并清掉旧落盘文件；同名不同文件各自保留
                        val replaced = docs.filter { docSources[it.id] == item.uri }
                        docs = docs.filterNot { docSources[it.id] == item.uri } + doc
                        docSources = (docSources - replaced.map { it.id }.toSet()) + (doc.id to item.uri)
                        if (replaced.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                replaced.forEach {
                                    StatementStore.delete(context, it.id)
                                    // 旧历史指向已删除的文件，一并清掉，避免「记录已失效」
                                    LocalStats.removeFile(context, it.id)
                                }
                            }
                        }
                        activeId = doc.id
                        LocalStats.addTaskFile(
                            context, currentTask, currentAt, id, item.name, doc.pages, doc.rows.size
                        )
                        history = LocalStats.tasks(context)
                        updateQueue(index) {
                            it.copy(
                                status = 2,
                                pages = doc.pages,
                                rows = doc.rows.size,
                                percent = 100,
                                docId = id
                            )
                        }
                    } catch (e: TimeoutCancellationException) {
                        updateQueue(index) { it.copy(status = 3, percent = 0) }
                        onNotify(
                            I18n.f(lang, "parse_one_failed", item.name, e.message ?: "timeout"),
                            null
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        updateQueue(index) { it.copy(status = 3, percent = 0) }
                        onNotify(
                            I18n.f(lang, "parse_one_failed", item.name, e.message ?: "unknown"),
                            null
                        )
                    }
                }
            } finally {
                if (parseToken == token) {
                    parseJob = null
                    progress = 1f
                }
                // 清掉本次复制的临时 PDF
                runCatching {
                    File(context.cacheDir, "pdf").listFiles()
                        ?.filter { it.name.startsWith("input-") }
                        ?.forEach { it.delete() }
                }
            }
        }
    }

    /** 解析页「解析完成 / 下一页」：带着最后一份已完成的结果进入校对页。 */
    fun finishParsing() {
        if (parseJob != null) return
        val id = parseQueue.lastOrNull { it.docId > 0L }?.docId ?: 0L
        val doc = docs.firstOrNull { it.id == id } ?: return
        cameBackFromReview = true
        openReview(doc, fromParse = true)
    }

    /** 解析页删除某个错误文件：同时清掉它的校对结果、落盘文件与最近记录。 */
    fun deleteParsedFile(item: ParseFileState) {
        if (parseJob != null || item.status == 1) return
        parseQueue = parseQueue.filterNot { it.uri == item.uri }
        picked = picked.filterNot { it.uri == item.uri }
        if (item.docId > 0L) {
            docs = docs.filterNot { it.id == item.docId }
            docSources = docSources - item.docId
            if (activeId == item.docId) activeId = 0L
            scope.launch {
                withContext(Dispatchers.IO) {
                    StatementStore.delete(context, item.docId)
                    LocalStats.removeFile(context, item.docId)
                }
                history = LocalStats.tasks(context)
            }
        }
        if (parseQueue.isEmpty()) backToHome()
    }

    /** 校对页「导出」：进入整页文件选择，默认勾选当前文件。 */
    fun openExport() {
        if (activeId <= 0L) return
        exportIds = setOf(activeId)
        stage = Stage.EXPORT_PICK
        onFullscreen(true)
    }

    /** 导出页逐级返回：格式页 → 文件选择页 → 校对页。 */
    fun closeExport() {
        stage = Stage.REVIEW
        onFullscreen(true)
    }

    /** 逐份导出：每份写成功才扣自己的页数，失败的份数单独统计。 */
    fun runBatch(selected: List<ReviewDoc>, kind: String, preset: String, dirUri: String?) {
        if (state.capacity() < selected.sumOf { it.pages }) {
            quotaBlocked = true
            return
        }
        val start: () -> Unit = {
            exporting = true
            exportTotal = selected.size
            exportDone = 0
            exportFailed = 0
            savedBatch = emptyList()
            stage = Stage.EXPORT_FORMAT
            onFullscreen(true)
            scope.launch {
                val used = mutableSetOf<String>()
                val mime = exportMime(kind)
                for ((i, doc) in selected.withIndex()) {
                    runCatching {
                        val base64 = withContext(Dispatchers.Default) {
                            engine.export(kind, doc.rowsJson(), doc.baseName, preset)
                        }
                        val fileName = uniqueFileName(doc.baseName, kind, used)
                        val saved = withContext(Dispatchers.IO) {
                            val tree = dirUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                            (tree?.let { Exporter.saveToTree(context, it, base64, fileName, mime) })
                                ?: Exporter.save(context, base64, fileName, mime)
                        }
                        state.consume(doc.pages)
                        LocalStats.markExported(context, doc.id, kind)
                        savedBatch = savedBatch + saved
                    }.onFailure { exportFailed += 1 }
                    exportDone = i + 1
                }
                history = LocalStats.tasks(context)
                exporting = false
                // 导出结束后回到校对页，结果弹窗压在上面
                if (savedBatch.isNotEmpty()) stage = Stage.REVIEW
            }
        }
        val needsStorage = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsStorage) {
            pendingProceed = start
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            start()
        }
    }

    /** 统一格式导出选中文件；开启「每次询问」时先选一次目录。 */
    fun doExport(selected: List<ReviewDoc>) {
        val kind = exportKind
        val preset = if (kind == "csv") csvPreset else ""
        if (askLocation) {
            pendingBatch = { runBatch(selected, kind, preset, exportDir) }
            val opened = runCatching { pickExportDir.launch(null) }.isSuccess
            if (!opened) {
                pendingBatch = null
                onNotify(I18n.t(lang, "pick_failed"), null)
            }
            return
        }
        runBatch(selected, kind, preset, exportDir)
    }

    /** 选中文件统一格式导出；仍有未核对行时先二次确认。 */
    fun requestExport() {
        val selected = docs.filter { it.id in exportIds }
        if (selected.isEmpty()) {
            onNotify(I18n.t(lang, "export_pick_none"), null)
            return
        }
        if (state.capacity() < selected.sumOf { it.pages }) {
            quotaBlocked = true
            return
        }
        val pending = selected.sumOf { it.pending }
        if (pending > 0) {
            confirmExportCount = pending
            confirmExport = { doExport(selected) }
        } else {
            doExport(selected)
        }
    }

    // 逐级返回：解析页 → 添加文件页 → 首页；导出格式页 → 文件选择页 → 校对页
    BackHandler(enabled = stage == Stage.PARSING) { cancelParsing() }
    BackHandler(enabled = stage == Stage.PICKED) { backToHome() }
    BackHandler(enabled = stage == Stage.EXPORT_PICK) { closeExport() }
    BackHandler(enabled = stage == Stage.EXPORT_FORMAT) { if (!exporting) stage = Stage.EXPORT_PICK }
    // 校对页兜底：有改动时按返回先确认；无改动直接回上一级
    BackHandler(enabled = stage == Stage.REVIEW) { requestCloseReview() }

    // 启动时清掉超过 7 天的草稿，并读出现有草稿角标
    LaunchedEffect(Unit) {
        drafts = withContext(Dispatchers.IO) { StatementStore.draftMetas(context) }
    }

    // 校对编辑自动落草稿：停止改动 800ms 后写盘，避免误退 / 后台被杀丢进度
    LaunchedEffect(Unit) {
        snapshotFlow {
            docs.map { doc ->
                doc.rows.joinToString("|") { row ->
                    "${row.date},${row.desc},${row.amount},${row.balance},${row.verified}"
                }
            }
        }.collect {
            if (docs.none { it.hasDraft }) return@collect
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(800)
                persistDrafts()
            }
        }
    }

    // 退回后台立刻落盘，覆盖「直接上滑杀进程」的情况
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (stage == Stage.REVIEW) persistDrafts()
    }

    // 解析是否全部结束、且至少一份成功：决定「开始校对」是否可点
    val parseAllDone = parseQueue.isNotEmpty() && parseJob == null &&
        parseQueue.none { it.status == 0 || it.status == 1 }
    val parseOkCount = parseQueue.count { it.status == 2 }
    val canFinishParse = parseAllDone && parseOkCount > 0

    // 左右滑动切换流程页：右滑回上一级，左滑进下一级（下一页）
    Box(
        Modifier.fillMaxSize().pointerInput(stage, picked.size, canFinishParse) {
            var drag = 0f
            val threshold = 72.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragCancel = { drag = 0f },
                onHorizontalDrag = { _, amount -> drag += amount },
                onDragEnd = {
                    if (drag > threshold) {
                        when (stage) {
                            // 校对页的右滑由 CompareScreen 的行列表区处理，避免和 PDF 缩放冲突
                            Stage.REVIEW -> Unit
                            Stage.PARSING -> cancelParsing()
                            Stage.PICKED -> backToHome()
                            Stage.EXPORT_PICK -> closeExport()
                            Stage.EXPORT_FORMAT -> if (!exporting) stage = Stage.EXPORT_PICK
                            Stage.HOME -> Unit
                        }
                    } else if (drag < -threshold) {
                        when (stage) {
                            Stage.HOME -> if (picked.isNotEmpty()) {
                                stage = Stage.PICKED
                                onFullscreen(false)
                            }
                            // 不再无条件重跑：有结果时只回结果页，没结果才弹确认
                            Stage.PICKED -> requestParsing()
                            Stage.PARSING -> if (canFinishParse) finishParsing()
                            Stage.EXPORT_PICK -> if (exportIds.isNotEmpty()) stage = Stage.EXPORT_FORMAT
                            Stage.REVIEW, Stage.EXPORT_FORMAT -> Unit
                        }
                    }
                    drag = 0f
                }
            )
        }
    ) {
    if (stage == Stage.PARSING) {
        ParsingScreen(
            lang = lang,
            queue = parseQueue,
            index = parseIndex,
            total = parseTotal,
            running = parseJob != null,
            canFinish = canFinishParse,
            showNext = cameBackFromReview && canFinishParse,
            onBack = { cancelParsing() },
            onFinish = { finishParsing() },
            onDelete = { item -> deleteParsedFile(item) },
            onReparse = { confirmReparse = true }
        )
    } else if (stage == Stage.EXPORT_PICK) {
        ExportPickScreen(
            docs = docs,
            selected = exportIds,
            lang = lang,
            onBack = { closeExport() },
            onToggle = { id ->
                exportIds = if (id in exportIds) exportIds - id else exportIds + id
            },
            onToggleAll = {
                exportIds = if (exportIds.size == docs.size) emptySet() else docs.map { it.id }.toSet()
            },
            onNext = { stage = Stage.EXPORT_FORMAT }
        )
    } else if (stage == Stage.EXPORT_FORMAT) {
        ExportFormatScreen(
            docs = docs,
            selected = exportIds,
            kind = exportKind,
            lang = lang,
            preset = csvPreset,
            capacity = state.capacity(),
            askLocation = askLocation,
            exportDir = exportDir,
            exporting = exporting,
            exportDone = exportDone,
            exportTotal = exportTotal,
            onBack = { if (!exporting) stage = Stage.EXPORT_PICK },
            onKind = { exportKind = it },
            onPreset = { csvPreset = it },
            onAskLocation = {
                askLocation = it
                LocalStats.setAskSaveLocation(context, it)
            },
            onPickDir = {
                runCatching { pickExportDir.launch(null) }
                    .onFailure { onNotify(I18n.t(lang, "pick_failed"), null) }
            },
            onExport = { requestExport() }
        )
    } else {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(14.dp))
        BrandRow(
            chip = if (state.active) I18n.t(lang, "chip_active")
            else I18n.f(lang, "chip_trial", state.trialRemaining),
            lang = lang,
            onLangPick = onLangPick
        )

        if (stage == Stage.HOME || picked.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            QuotaStrip(lang = lang, state = state)

            Spacer(Modifier.height(22.dp))
            HomeSlogan(lang)

            Spacer(Modifier.height(18.dp))
            PickerCard(lang = lang, capacity = state.capacity()) {
                if (state.capacity() <= 0) {
                    onNotify(I18n.t(lang, "quota_empty"), null)
                    onNeedActivate()
                } else {
                    // 首页第一次选文件也支持多选，与「添加文件」保持一致
                    openPickerMulti()
                }
            }
        } else {
            Spacer(Modifier.height(18.dp))
            PickedContent(
                lang = lang,
                files = picked,
                pendingCount = pendingFiles().size,
                onAdd = { openPickerMulti() },
                onRemove = { index ->
                    val next = picked.filterIndexed { i, _ -> i != index }
                    picked = next
                    // 移除最后一份文件后回到首页
                    if (next.isEmpty()) backToHome()
                },
                onStart = { requestParsing() }
            )
            Spacer(Modifier.height(28.dp))
        }

        if (stage == Stage.HOME || picked.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            RecentSection(
                tasks = history,
                drafts = drafts,
                lang = lang,
                onOpen = { task -> openTask(task) },
                onShowAll = { showAllRecent = true },
                onDelete = { task -> deleteTask(task) },
                onClear = {
                    scope.launch {
                        withContext(Dispatchers.IO) { StatementStore.clear(context) }
                        LocalStats.clearHistory(context)
                        history = LocalStats.tasks(context)
                        docs = emptyList()
                        docSources = emptyMap()
                        drafts = emptyMap()
                        resetToHome()
                    }
                }
            )

            Spacer(Modifier.height(20.dp))
            FormatChips(lang)
            Spacer(Modifier.height(14.dp))
            PrivacyLine(lang)
        }

        Spacer(Modifier.height(28.dp))
    }

    val fullDoc = compareDoc
    if (fullDoc != null && stage == Stage.REVIEW) {
        CompareScreen(
            docs = docs,
            activeId = fullDoc.id,
            lang = lang,
            onSelect = {
                activeId = it
                compareId = it
                activeRow = null
            },
            onClose = { requestCloseReview() },
            onExport = { openExport() }
        )
    }
    } // 非解析态
    } // Box

    // 导出结果：一份或多份都走同一个结果弹窗
    if (savedBatch.isNotEmpty() || exportFailed > 0) {
        val saved = savedBatch
        AlertDialog(
            onDismissRequest = {
                savedBatch = emptyList()
                exportFailed = 0
            },
            containerColor = Pal.Panel,
            title = {
                Text(
                    if (saved.isEmpty()) {
                        I18n.t(lang, "export_failed_title")
                    } else {
                        I18n.f(lang, "export_batch_saved", saved.size)
                    },
                    color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    saved.forEach { item ->
                        Text(item.path, color = Pal.Text, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (exportFailed > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            I18n.f(lang, "export_batch_failed", exportFailed),
                            color = Pal.Danger, fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        I18n.t(
                            lang,
                            when {
                                saved.any { it.picked } -> "export_saved_picker"
                                saved.any { it.isPrivate } -> "export_hint_private"
                                else -> "export_hint_public"
                            }
                        ),
                        color = Pal.Faint, fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Row {
                    if (saved.size == 1) {
                        val one = saved.first()
                        TextButton(onClick = {
                            savedBatch = emptyList()
                            exportFailed = 0
                            Exporter.share(context, one.uri, one.mime)
                        }) {
                            Text(I18n.t(lang, "share"), color = Pal.Teal, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            savedBatch = emptyList()
                            exportFailed = 0
                            if (!Exporter.open(context, one.uri, one.mime)) {
                                onNotify(I18n.t(lang, "open_failed"), null)
                            }
                        }) {
                            Text(I18n.t(lang, "export_open"), color = Pal.Teal, fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = {
                        savedBatch = emptyList()
                        exportFailed = 0
                    }) {
                        Text(I18n.t(lang, "export_ok"), color = Pal.Text, fontSize = 12.sp)
                    }
                }
            }
        )
    }

    if (showPicker) {
        FilePickerSheet(
            docs = docs,
            activeId = activeId,
            lang = lang,
            onPick = {
                showPicker = false
                activeRow = null
                activeId = it
            },
            onDismiss = { showPicker = false }
        )
    }

    // 最近记录「查看全部」：全屏列表，每页 10 条，下拉加载更多
    if (showAllRecent) {
        RecentAllSheet(
            tasks = history,
            drafts = drafts,
            lang = lang,
            onOpen = { task ->
                showAllRecent = false
                openTask(task)
            },
            onDelete = { task -> deleteTask(task) },
            onDismiss = { showAllRecent = false }
        )
    }

    val sheetRow = activeRow
    val sheetDoc = sheetRow?.let { docOf(it) }
    if (sheetDoc != null && sheetRow != null) {
        val index = sheetDoc.rows.indexOfFirst { it === sheetRow }
        ReviewSheet(
            doc = sheetDoc,
            row = sheetRow,
            lang = lang,
            canGoPrev = index > 0,
            hasOtherDocs = docs.size > 1,
            onDismiss = { activeRow = null },
            onPrev = { if (index > 0) activeRow = sheetDoc.rows[index - 1] },
            onEdit = { editingRow = sheetRow },
            onVerify = { sheetRow.verified = true },
            onNextFile = {
                val current = docs.indexOfFirst { it.id == sheetDoc.id }
                val next = docs.getOrNull((current + 1) % docs.size)
                if (next != null) {
                    activeId = next.id
                    activeRow = next.rows.firstOrNull { it.hasIssue && !it.verified }
                        ?: next.rows.firstOrNull { !it.verified }
                        ?: next.rows.firstOrNull()
                }
            }
        )
    }

    val editRow = editingRow
    if (editRow != null) {
        ReviewEditDialog(
            row = editRow,
            lang = lang,
            onDismiss = { editingRow = null },
            onSave = { date, desc, amountText, balanceText ->
                val amount = amountText.replace(",", "").toDoubleOrNull() ?: editRow.amount
                val balance = if (balanceText.isBlank()) {
                    null
                } else {
                    balanceText.replace(",", "").toDoubleOrNull()
                }
                editRow.date = date
                editRow.desc = desc
                editRow.amount = amount
                editRow.balance = balance
                docOf(editRow)?.let { recomputeIssues(it) }
                editingRow = null
            }
        )
    }

    // 校对页退出确认：改动已在本地草稿，退出后可从「最近记录」继续 7 天
    if (confirmLeaveReview) {
        AlertDialog(
            onDismissRequest = { confirmLeaveReview = false },
            containerColor = Pal.Panel,
            title = {
                Text(
                    I18n.t(lang, "leave_review_title"), color = Pal.Text,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    I18n.t(lang, "leave_review_body"), color = Pal.Muted,
                    fontSize = 12.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeaveReview = false
                    persistDrafts()
                    closeReview()
                }) {
                    Text(
                        I18n.t(lang, "leave_review_exit"), color = Pal.Teal,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeaveReview = false }) {
                    Text(I18n.t(lang, "leave_review_stay"), color = Pal.Muted, fontSize = 12.sp)
                }
            }
        )
    }

    val pendingExport = confirmExport
    if (pendingExport != null) {
        AlertDialog(
            onDismissRequest = { confirmExport = null },
            containerColor = Pal.Panel,
            titleContentColor = Pal.Text,
            textContentColor = Pal.Muted,
            title = {
                Text(
                    I18n.t(lang, "export_confirm_title"),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    I18n.f(lang, "export_confirm_body", confirmExportCount),
                    fontSize = 12.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = null
                    pendingExport()
                }) {
                    Text(
                        I18n.t(lang, "export_continue"), color = Pal.Teal,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExport = null }) {
                    Text(I18n.t(lang, "cancel"), color = Pal.Muted)
                }
            }
        )
    }

    // 开始解析前的确认：文件数、已解析保留数、在本机解析
    if (confirmParsing) {
        val pending = pendingFiles().size
        val kept = picked.size - pending
        AlertDialog(
            onDismissRequest = { confirmParsing = false },
            containerColor = Pal.Panel,
            titleContentColor = Pal.Text,
            textContentColor = Pal.Muted,
            title = {
                Text(
                    I18n.t(lang, "parse_confirm_title"),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    if (kept > 0) {
                        I18n.f(lang, "parse_confirm_resume", kept, pending)
                    } else {
                        I18n.f(lang, "parse_confirm_body", pending)
                    },
                    fontSize = 12.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmParsing = false
                    startParsing()
                }) {
                    Text(
                        I18n.t(lang, "parse_confirm_go"), color = Pal.Teal,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmParsing = false }) {
                    Text(I18n.t(lang, "cancel"), color = Pal.Muted)
                }
            }
        )
    }

    // 重新解析会覆盖校对结果，必须显式确认
    if (confirmReparse) {
        AlertDialog(
            onDismissRequest = { confirmReparse = false },
            containerColor = Pal.Panel,
            titleContentColor = Pal.Text,
            textContentColor = Pal.Muted,
            title = {
                Text(
                    I18n.t(lang, "reparse_title"),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    I18n.t(lang, "reparse_body"),
                    fontSize = 12.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReparse = false
                    startParsing(force = true)
                }) {
                    Text(
                        I18n.t(lang, "reparse_go"), color = Pal.Danger,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReparse = false }) {
                    Text(I18n.t(lang, "cancel"), color = Pal.Muted)
                }
            }
        )
    }

    if (quotaBlocked) {
        AlertDialog(
            onDismissRequest = { quotaBlocked = false },
            containerColor = Pal.Panel,
            titleContentColor = Pal.Text,
            textContentColor = Pal.Muted,
            title = {
                Text(
                    I18n.t(lang, "quota_empty_title"),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(I18n.t(lang, "quota_empty"), fontSize = 12.sp, lineHeight = 18.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    quotaBlocked = false
                    onNeedActivate()
                }) {
                    Text(I18n.t(lang, "view_plans"), color = Pal.Teal, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { quotaBlocked = false }) {
                    Text(I18n.t(lang, "cancel"), color = Pal.Muted)
                }
            }
        )
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onPick: () -> Unit
) {
    Box(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color(0xFF16324F) else Color(0xFF0F151C))
            .border(
                1.dp,
                if (selected) Pal.Teal else Pal.Line,
                RoundedCornerShape(9.dp)
            )
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Pal.Teal else Pal.Muted,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

/** 首页待解析队列项：选择后先入队，点「开始解析」才跑引擎。 */
private data class PickedFile(val uri: Uri, val name: String, val size: Long)

/** 解析页里的单个文件：状态 0 等待 / 1 解析中 / 2 完成 / 3 失败。 */
private data class ParseFileState(
    val uri: Uri,
    val name: String,
    val size: Long,
    val status: Int = 0,
    val pages: Int = 0,
    val rows: Int = 0,
    val percent: Int = 0,
    val docId: Long = 0L
)

/** 首页的页面层级：选文件 → 解析 → 校对；导出是校对页之后的两个整页步骤。 */
private enum class Stage { HOME, PICKED, PARSING, REVIEW, EXPORT_PICK, EXPORT_FORMAT }

/** 首页额度条：剩余页数 + 今日已用。 */
@Composable
private fun QuotaStrip(lang: AppLang, state: AppState) {
    val context = LocalContext.current
    val remaining = state.capacity()
    val total = if (state.active) {
        (state.licence?.current()?.pages ?: 0).coerceAtLeast(remaining).coerceAtLeast(1)
    } else {
        TrialLedger.TRIAL_PAGES
    }
    val ratio = (remaining.toFloat() / total).coerceIn(0f, 1f)
    val usedToday = remember(remaining, state.active) { LocalStats.todayUsage(context) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.active) I18n.f(lang, "quota_active", formatCount(remaining))
                else I18n.f(lang, "quota_guest", formatCount(remaining), total),
                color = Pal.Muted, fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                I18n.f(lang, "quota_today", usedToday),
                color = Pal.Teal, fontSize = 10.sp, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                .background(Color(0xFF101820))
        ) {
            if (ratio > 0f) {
                Box(
                    Modifier.fillMaxWidth(ratio).height(3.dp).clip(CircleShape)
                        .background(Pal.accentGradient)
                )
            }
        }
    }
}

/** 首页宣传语：STMTPDF + 主标题 + 两行定位描述。 */
@Composable
private fun HomeSlogan(lang: AppLang) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "STMTPDF", color = Pal.Teal, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 3.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            I18n.t(lang, "home_h1"), color = Pal.Text, fontSize = 27.sp,
            fontWeight = FontWeight.Bold, lineHeight = 35.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            I18n.t(lang, "home_sub1"), color = Pal.Muted, fontSize = 11.sp, lineHeight = 18.sp
        )
        Text(
            I18n.t(lang, "home_sub2"), color = Pal.Muted, fontSize = 11.sp, lineHeight = 18.sp
        )
    }
}

/** 首页选择框：虚线圈 + 文档图标 + 页数上限。 */
@Composable
private fun PickerCard(lang: AppLang, capacity: Int, onPick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(186.dp).clip(RoundedCornerShape(18.dp))
            .background(Pal.Tile).clickable(onClick = onPick)
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = 18.dp.toPx()
            drawRoundRect(
                color = Pal.Teal.copy(alpha = 0.42f),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(10.dp.toPx(), 7.dp.toPx())
                    )
                )
            )
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Canvas(Modifier.size(34.dp)) {
                val w = size.width
                val h = size.height
                val stroke = 1.6.dp.toPx()
                drawRoundRect(
                    color = Pal.Teal,
                    topLeft = Offset(w * 0.16f, h * 0.08f),
                    size = Size(w * 0.68f, h * 0.84f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(stroke)
                )
                listOf(0.32f, 0.48f, 0.64f).forEach { y ->
                    drawLine(
                        color = Pal.Teal,
                        start = Offset(w * 0.30f, h * y),
                        end = Offset(w * 0.70f, h * y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                I18n.t(lang, "pick_title"), color = Pal.Text,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(7.dp))
            Text(
                I18n.f(lang, "pick_sub", capacity.coerceAtLeast(0)),
                color = Pal.Faint, fontSize = 10.sp
            )
        }
    }
}

/** 已选文件页：文件列表 + 添加文件 + 开始解析 + 多文件提示。 */
@Composable
private fun PickedContent(
    lang: AppLang,
    files: List<PickedFile>,
    pendingCount: Int,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onStart: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            I18n.t(lang, "picked_title"), color = Pal.Text,
            fontSize = 23.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            I18n.t(lang, "picked_sub"), color = Pal.Muted,
            fontSize = 11.sp, lineHeight = 17.sp
        )
        Spacer(Modifier.height(18.dp))

        files.forEachIndexed { index, file ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            PanelCard(radius = 16.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                            .background(Pal.Teal.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "PDF", color = Pal.Teal, fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.name, color = Pal.Text, fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            I18n.f(lang, "file_ready", fileSizeText(file.size)),
                            color = Pal.Muted, fontSize = 9.5.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .border(1.dp, Pal.Warm.copy(alpha = 0.55f), CircleShape)
                            .clickable { onRemove(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", color = Pal.Warm, fontSize = 14.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                .background(Pal.Panel).border(1.dp, Pal.Line2, RoundedCornerShape(14.dp))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Text(
                I18n.t(lang, "btn_add_file"), color = Pal.Text,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        GradientButton(
            when {
                pendingCount == 0 -> I18n.t(lang, "parse_view_result")
                pendingCount < files.size -> I18n.f(lang, "parse_new_count", pendingCount)
                else -> I18n.t(lang, "btn_start_parse")
            },
            height = 52.dp, radius = 14.dp, onClick = onStart
        )
        Spacer(Modifier.height(10.dp))
        Text(
            I18n.t(lang, "parse_local_note"), color = Pal.Faint, fontSize = 10.sp,
            lineHeight = 16.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(22.dp))
        SectionLabel(I18n.t(lang, "pick_more"))
        Spacer(Modifier.height(9.dp))
        PanelCard(radius = 16.dp) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onAdd)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                        .background(Pal.Teal.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = Pal.Teal, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        I18n.t(lang, "multi_title"), color = Pal.Text,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        I18n.t(lang, "multi_sub"), color = Pal.Muted, fontSize = 9.5.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        FormatChips(lang, I18n.t(lang, "formats_after"))
        Spacer(Modifier.height(14.dp))
        PrivacyLine(lang)
    }
}

/** 解析页：汇总卡 + 逐文件队列（可删除）+ 底部「解析完成」（未完成置灰）。 */
@Composable
private fun ParsingScreen(
    lang: AppLang,
    queue: List<ParseFileState>,
    index: Int,
    total: Int,
    running: Boolean,
    canFinish: Boolean,
    showNext: Boolean,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onDelete: (ParseFileState) -> Unit,
    onReparse: () -> Unit
) {
    val okCount = queue.count { it.status == 2 }
    val rowSum = queue.filter { it.status == 2 }.sumOf { it.rows }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 14.dp)
            .navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Pal.Panel)
                    .border(1.dp, Pal.Line2, CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (running) I18n.t(lang, "parsing_title") else I18n.t(lang, "parsed_title"),
                color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            // 从校对页返回后才出现「下一页」，未校对时不显示
            if (showNext) {
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(Pal.Teal.copy(alpha = 0.10f))
                        .border(1.dp, Pal.Teal.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .clickable(onClick = onFinish)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        I18n.t(lang, "next_page"), color = Pal.Teal,
                        fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PanelCard(radius = 16.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            I18n.f(lang, "parsed_summary", queue.size, rowSum),
                            color = Pal.Text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            I18n.f(lang, "parsed_progress", okCount, queue.size),
                            color = Pal.Faint, fontSize = 9.5.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (queue.isEmpty()) 0f else okCount.toFloat() / queue.size
                        },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = Pal.Teal,
                        trackColor = Color(0xFF101820)
                    )
                    Spacer(Modifier.height(9.dp))
                    Text(
                        if (running) I18n.f(lang, "parsing_running", index, total)
                        else I18n.t(lang, "parsed_all_done"),
                        color = if (running) Pal.Muted else Pal.Teal, fontSize = 9.sp
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel(I18n.t(lang, "parse_queue"))
            Spacer(Modifier.height(9.dp))
            PanelCard(radius = 16.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    queue.forEachIndexed { i, item ->
                        if (i > 0) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF16222A)))
                        }
                        ParseQueueRow(
                            lang = lang,
                            number = i + 1,
                            item = item,
                            canDelete = !running,
                            onDelete = { onDelete(item) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                I18n.t(lang, "privacy_line"), color = Pal.Faint, fontSize = 9.5.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(10.dp))
        GradientButton(
            text = if (showNext) I18n.t(lang, "next_page") else I18n.t(lang, "parse_done_btn"),
            height = 52.dp,
            radius = 14.dp,
            enabled = canFinish,
            onClick = onFinish
        )
        // 已有结果时给一个显式的重解析入口，避免用户以为「左滑」会重跑
        if (!running && okCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                I18n.t(lang, "reparse"),
                color = Pal.Faint, fontSize = 10.5.sp,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onReparse)
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 解析页队列中的一行：序号 + 文件名 + 状态 + 删除。 */
@Composable
private fun ParseQueueRow(
    lang: AppLang,
    number: Int,
    item: ParseFileState,
    canDelete: Boolean,
    onDelete: () -> Unit
) {
    val accent = when (item.status) {
        2 -> Pal.Teal
        3 -> Pal.Danger
        1 -> Pal.Cyan
        else -> Pal.Faint
    }
    val meta = when (item.status) {
        1 -> I18n.f(lang, "queue_parsing_pct", item.percent)
        2 -> I18n.f(lang, "queue_meta", item.pages, item.rows)
        3 -> I18n.t(lang, "queue_failed")
        else -> fileSizeText(item.size)
    }
    val statusText = when (item.status) {
        1 -> "${item.percent}%"
        2 -> I18n.t(lang, "queue_done")
        3 -> I18n.t(lang, "queue_error")
        else -> I18n.t(lang, "queue_waiting")
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            String.format(Locale.US, "%02d", number), color = Pal.Faint,
            fontSize = 9.5.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name, color = Pal.Text, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(meta, color = Pal.Faint, fontSize = 9.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(statusText, color = accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        if (canDelete && item.status != 1) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .border(1.dp, Pal.Faint.copy(alpha = 0.45f), CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = Pal.Faint, fontSize = 13.sp)
            }
        }
    }
}

/* ---------------------------------------------------------------- 导出（整页两步） */

/** 导出第一步：整页选择要导出的文件，支持全选 / 单选。 */
@Composable
private fun ExportPickScreen(
    docs: List<ReviewDoc>,
    selected: Set<Long>,
    lang: AppLang,
    onBack: () -> Unit,
    onToggle: (Long) -> Unit,
    onToggleAll: () -> Unit,
    onNext: () -> Unit
) {
    val chosen = docs.filter { it.id in selected }
    val allSelected = docs.isNotEmpty() && chosen.size == docs.size
    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 14.dp)
            .navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundBack(onBack)
            Spacer(Modifier.width(10.dp))
            Text(
                I18n.t(lang, "export_pick_title"), color = Pal.Text,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            InfoChip("${chosen.size}/${docs.size}", Pal.Teal)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            I18n.t(lang, "export_pick_sub"), color = Pal.Muted,
            fontSize = 10.5.sp, lineHeight = 16.sp
        )

        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggleAll)
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CheckDot(allSelected)
                Spacer(Modifier.width(9.dp))
                Text(
                    I18n.t(lang, "select_all"), color = Pal.Text,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    I18n.f(lang, "selected_count", chosen.size, chosen.sumOf { it.pages }),
                    color = Pal.Faint, fontSize = 9.5.sp
                )
            }

            Spacer(Modifier.height(8.dp))
            PanelCard(radius = 16.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    docs.forEachIndexed { index, doc ->
                        if (index > 0) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF16222A)))
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onToggle(doc.id) }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CheckDot(doc.id in selected)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                String.format(Locale.US, "%02d", index + 1),
                                color = Pal.Faint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    doc.name, color = Pal.Text, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    I18n.f(lang, "history_meta", doc.pages, doc.rows.size),
                                    color = Pal.Muted, fontSize = 9.sp
                                )
                            }
                            if (doc.pending > 0) {
                                Spacer(Modifier.width(8.dp))
                                InfoChip("${doc.pending}", Pal.Warm)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                I18n.t(lang, "export_pick_note"), color = Pal.Faint, fontSize = 9.sp,
                lineHeight = 14.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        GradientButton(
            I18n.t(lang, "export_next_step"),
            height = 52.dp, radius = 14.dp,
            enabled = chosen.isNotEmpty(),
            onClick = onNext
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** 导出第二步：整页选格式（6 种全部平铺）+ CSV 预设 + 保存位置 + 导出。 */
@Composable
private fun ExportFormatScreen(
    docs: List<ReviewDoc>,
    selected: Set<Long>,
    kind: String,
    lang: AppLang,
    preset: String,
    capacity: Int,
    askLocation: Boolean,
    exportDir: String?,
    exporting: Boolean,
    exportDone: Int,
    exportTotal: Int,
    onBack: () -> Unit,
    onKind: (String) -> Unit,
    onPreset: (String) -> Unit,
    onAskLocation: (Boolean) -> Unit,
    onPickDir: () -> Unit,
    onExport: () -> Unit
) {
    val chosen = docs.filter { it.id in selected }
    val totalPages = chosen.sumOf { it.pages }
    val formats = listOf(
        "qbo" to I18n.t(lang, "fmt_qbo_desc"),
        "xlsx" to I18n.t(lang, "fmt_xlsx_desc"),
        "csv" to I18n.t(lang, "fmt_csv_desc"),
        "qif" to I18n.t(lang, "fmt_qif_desc"),
        "qfx" to I18n.t(lang, "fmt_qfx_desc"),
        "ofx" to I18n.t(lang, "fmt_ofx_desc")
    )
    val labels = mapOf(
        "qbo" to "QBO", "xlsx" to "Excel", "csv" to "CSV",
        "qif" to "QIF", "qfx" to "QFX", "ofx" to "OFX"
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 14.dp)
            .navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundBack(onBack)
            Spacer(Modifier.width(10.dp))
            Text(
                I18n.t(lang, "export_format_title"), color = Pal.Text,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            InfoChip("${chosen.size}", Pal.Teal)
        }

        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PanelCard(radius = 14.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        I18n.f(lang, "export_batch_meta", chosen.size, totalPages),
                        color = Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        I18n.f(lang, "export_quota_hint", totalPages, capacity),
                        color = Pal.Faint, fontSize = 9.5.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel(I18n.t(lang, "formats_label"))
            Spacer(Modifier.height(9.dp))
            formats.chunked(2).forEachIndexed { rowIndex, rowItems ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { (code, desc) ->
                        FormatCard(
                            title = labels[code] ?: code.uppercase(Locale.US),
                            desc = desc,
                            selected = kind == code,
                            modifier = Modifier.weight(1f)
                        ) { if (!exporting) onKind(code) }
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (kind == "csv") {
                Spacer(Modifier.height(12.dp))
                Text(I18n.t(lang, "csv_preset"), color = Pal.Faint, fontSize = 9.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "standard" to I18n.t(lang, "preset_standard"),
                        "xero" to "Xero",
                        "sage" to "Sage",
                        "freshbooks" to "FreshBooks",
                        "tally" to "Tally"
                    ).forEach { (code, label) ->
                        PresetChip(
                            label = label,
                            selected = preset == code,
                            modifier = Modifier.weight(1f)
                        ) { if (!exporting) onPreset(code) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            PanelCard(radius = 14.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                I18n.t(lang, "export_ask_location"), color = Pal.Text,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                I18n.t(lang, "export_ask_location_desc"), color = Pal.Faint,
                                fontSize = 9.5.sp
                            )
                        }
                        Switch(
                            checked = askLocation,
                            onCheckedChange = { if (!exporting) onAskLocation(it) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF16222A)))
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                I18n.t(lang, "save_location"), color = Pal.Faint,
                                fontSize = 9.sp, letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                exportDir?.let { value ->
                                    Exporter.treeLabel(Uri.parse(value))
                                        .ifBlank { I18n.t(lang, "save_location_default") }
                                } ?: I18n.t(lang, "save_location_default"),
                                color = Pal.Text, fontSize = 11.5.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(Pal.Teal.copy(alpha = 0.10f))
                                .border(1.dp, Pal.Teal.copy(alpha = 0.55f), RoundedCornerShape(50))
                                .clickable { if (!exporting) onPickDir() }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                I18n.t(lang, "save_location_pick"), color = Pal.Teal,
                                fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrivacyLine(lang)
        }

        Spacer(Modifier.height(10.dp))
        GradientButton(
            if (exporting) {
                I18n.f(lang, "exporting_meta", exportDone, exportTotal)
            } else {
                I18n.t(lang, "export_now")
            },
            height = 52.dp, radius = 14.dp,
            enabled = !exporting && chosen.isNotEmpty(),
            onClick = onExport
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RoundBack(onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Pal.Panel)
            .border(1.dp, Pal.Line2, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("‹", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckDot(checked: Boolean, accent: Color = Pal.Teal) {
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
            .background(if (checked) accent.copy(alpha = 0.16f) else Color(0xFF0F151C))
            .border(1.dp, if (checked) accent else Pal.Line2, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Text("✓", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FormatCard(
    title: String,
    desc: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF0D2A2A) else Color(0xFF0F151C))
            .border(
                1.dp,
                if (selected) Pal.Teal else Pal.Line,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, color = if (selected) Pal.Teal else Pal.Text,
                fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            CheckDot(selected)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            desc, color = Pal.Faint, fontSize = 9.sp,
            lineHeight = 13.sp, minLines = 2
        )
    }
}

/** 近期草稿：任务内任一份有草稿就显示，行数按有草稿的文件累计。 */
private fun draftOfTask(task: HistoryTask, drafts: Map<Long, DraftMeta>): DraftMeta? =
    task.files.mapNotNull { drafts[it.id] }
        .reduceOrNull { a, b ->
            DraftMeta(
                maxOf(a.updatedAt, b.updatedAt),
                a.unverified + b.unverified,
                a.pending + b.pending
            )
        }

/** 最近记录：一条 = 一次解析任务；首页只显示 5 条，更多进「查看全部」。 */
@Composable
private fun RecentSection(
    tasks: List<HistoryTask>,
    drafts: Map<Long, DraftMeta>,
    lang: AppLang,
    onOpen: (HistoryTask) -> Unit,
    onShowAll: () -> Unit,
    onDelete: (HistoryTask) -> Unit,
    onClear: () -> Unit
) {
    val shown = tasks.take(RECENT_HOME_LIMIT)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            I18n.t(lang, "recent"), color = Pal.Muted,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        if (tasks.isNotEmpty()) {
            Text(
                I18n.t(lang, "clear_history"),
                color = Pal.Faint, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    PanelCard {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (tasks.isEmpty()) {
                Text(
                    I18n.t(lang, "recent_empty"), color = Pal.Faint, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)
                )
            } else {
                shown.forEachIndexed { index, item ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF16222A)))
                    }
                    HistoryRow(
                        item, index % 2 == 1, lang,
                        draft = draftOfTask(item, drafts),
                        onClick = { onOpen(item) },
                        onDelete = { onDelete(item) }
                    )
                }
                if (tasks.size > shown.size) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF16222A)))
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onShowAll)
                            .padding(horizontal = 4.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            I18n.f(lang, "recent_more", tasks.size),
                            color = Pal.Teal, fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text("›", color = Pal.Teal, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/** 最近记录全文列表：每页 10 条，点「加载更多」再展开下一页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentAllSheet(
    tasks: List<HistoryTask>,
    drafts: Map<Long, DraftMeta>,
    lang: AppLang,
    onOpen: (HistoryTask) -> Unit,
    onDelete: (HistoryTask) -> Unit,
    onDismiss: () -> Unit
) {
    var visible by remember(tasks.size) {
        mutableStateOf(minOf(tasks.size, RECENT_PAGE_SIZE).coerceAtLeast(1))
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Pal.Panel,
        contentColor = Pal.Text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Pal.Line2) }
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp)
                .navigationBarsPadding().padding(bottom = 14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    I18n.t(lang, "recent_all_title"), color = Pal.Text,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    I18n.f(lang, "recent_all_count", tasks.size),
                    color = Pal.Muted, fontSize = 10.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "✕", color = Pal.Faint, fontSize = 13.sp,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss)
                        .padding(8.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                tasks.take(visible).forEachIndexed { index, item ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF16222A)))
                    }
                    HistoryRow(
                        item, index % 2 == 1, lang,
                        draft = draftOfTask(item, drafts),
                        onClick = { onOpen(item) },
                        onDelete = { onDelete(item) }
                    )
                }
                if (visible < tasks.size) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Pal.Line2, RoundedCornerShape(10.dp))
                            .clickable {
                                visible = minOf(tasks.size, visible + RECENT_PAGE_SIZE)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            I18n.f(lang, "recent_load_more", tasks.size - visible),
                            color = Pal.Muted, fontSize = 11.5.sp
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 导出格式胶囊行。 */
@Composable
private fun FormatChips(lang: AppLang, caption: String = I18n.t(lang, "formats_label")) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(caption)
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("QBO", "Excel", "CSV", "QIF", "QFX", "OFX").forEach { fmt ->
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Pal.Panel)
                        .border(1.dp, Pal.Line, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(fmt, color = Pal.Muted, fontSize = 9.5.sp)
                }
            }
        }
    }
}

/** 零上传说明。 */
@Composable
private fun PrivacyLine(lang: AppLang) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(Pal.Teal))
        Spacer(Modifier.width(7.dp))
        Text(I18n.t(lang, "privacy_line"), color = Pal.Faint, fontSize = 9.5.sp)
    }
}

/** 文件体积：不足 1 MB 用 KB。 */
private fun fileSizeText(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
}

@Composable
private fun UploadCard(
    lang: AppLang,
    parsing: Boolean,
    capacity: Int,
    trialPages: Int,
    active: Boolean,
    onPick: () -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(270.dp, 150.dp).align(Alignment.Center).background(
                Brush.radialGradient(listOf(Pal.Teal.copy(alpha = 0.12f), Color.Transparent))
            )
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Pal.Panel)
                .border(1.dp, Pal.Line2, RoundedCornerShape(24.dp)).padding(16.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Pal.Teal.copy(alpha = 0.03f))
                    .border(1.dp, Pal.Teal.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(46.dp).background(Pal.Teal.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("↑", color = Pal.Teal, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    I18n.t(lang, "upload_title"), color = Pal.Text,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (active) I18n.f(lang, "upload_sub_active", formatCount(capacity))
                    else I18n.f(lang, "upload_sub_trial", trialPages),
                    color = Pal.Muted, fontSize = 10.5.sp
                )
                Spacer(Modifier.height(14.dp))
                GradientButton(
                    text = if (parsing) I18n.t(lang, "upload_parsing") else I18n.t(lang, "upload_button"),
                    height = 42.dp,
                    radius = 13.dp,
                    enabled = !parsing,
                    onClick = onPick
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: HistoryTask,
    warm: Boolean,
    lang: AppLang,
    draft: DraftMeta? = null,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val accent = if (warm) Pal.Warm else Pal.Teal
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                item.format.take(3), color = accent, fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                I18n.f(lang, "task_label", item.no), color = Pal.Text, fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            Text(
                I18n.f(lang, "task_meta", item.files.size, item.pages, item.rows) +
                    historyTime(item.at, lang).let { if (it.isBlank()) "" else " · $it" },
                color = Pal.Muted, fontSize = 9.5.sp, maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.files.joinToString(" / ") { it.name },
                color = Pal.Faint, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (draft != null) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Pal.Warm))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        I18n.f(lang, "draft_badge", draft.unverified),
                        color = Pal.Warm, fontSize = 9.sp, maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (onDelete != null) {
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .border(1.dp, Pal.Faint.copy(alpha = 0.45f), CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = Pal.Faint, fontSize = 13.sp)
            }
        } else {
            InfoChip(item.format, accent)
        }
    }
}

/** 最近记录的时间：今天 / 昨天 / 月日。 */
private fun historyTime(at: Long, lang: AppLang): String {
    if (at <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val yesterday = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val isYesterday = sameYear && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> I18n.f(lang, "time_today", clock)
        isYesterday -> I18n.f(lang, "time_yesterday", clock)
        sameYear -> SimpleDateFormat("M/d", Locale.US).format(Date(at))
        else -> SimpleDateFormat("yyyy/M/d", Locale.US).format(Date(at))
    }
}

/** 导出文件的 MIME 类型。 */
private fun exportMime(kind: String): String = when (kind) {
    "qbo", "qfx", "ofx" -> "application/x-ofx"
    "qif" -> "application/qif"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    else -> "text/csv"
}

/** 同一次批量导出里文件名重复时加序号，避免后一份覆盖前一份。 */
private fun uniqueFileName(baseName: String, kind: String, used: MutableSet<String>): String {
    val safe = baseName.ifBlank { "statement" }
    var name = "$safe.$kind"
    var index = 2
    while (!used.add(name.lowercase(Locale.US))) {
        name = "$safe ($index).$kind"
        index += 1
    }
    return name
}

/* ---------------------------------------------------------------- Activate */

@Composable
private fun ActivateScreen(
    context: Context,
    state: AppState,
    lang: AppLang,
    onMessage: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf(false) }
    val emailFocus = remember { FocusRequester() }
    var cycle by remember { mutableStateOf("monthly") }
    var tier by remember { mutableStateOf(2) }
    var retrying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val plans = PLANS.filter { it.cycle == cycle }
    // 每次重组都读最新值：激活成功后 apply() 会清除
    val pendingOrder = LicenceStore.pendingOrder(context)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(14.dp))
        BrandRow(I18n.t(lang, "chip_secure"), Pal.Warm)

        Spacer(Modifier.height(28.dp))
        Text(I18n.t(lang, "activate_title"), color = Pal.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(I18n.t(lang, "activate_sub"), color = Pal.Muted, fontSize = 11.5.sp)

        Spacer(Modifier.height(24.dp))
        Text(
            I18n.t(lang, "email_label"), color = Pal.Faint, fontSize = 9.5.sp,
            letterSpacing = 1.4.sp
        )
        Spacer(Modifier.height(8.dp))
        EmailField(email, lang, emailError, emailFocus) {
            email = it
            emailError = false
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                I18n.t(lang, "plans_title"), color = Pal.Text,
                fontSize = 13.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            CycleToggle(cycle, lang) { cycle = it }
        }
        Spacer(Modifier.height(12.dp))

        plans.forEach { plan ->
            PlanCard(
                plan = plan,
                lang = lang,
                selected = plan.tier == tier,
                popular = plan.tier == 2,
                onClick = { tier = plan.tier }
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(8.dp))
        GradientButton(I18n.t(lang, "cta_checkout")) {
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailError = true
                return@GradientButton
            }
            val plan = PLANS.firstOrNull { it.code == "t${tier}_$cycle" } ?: return@GradientButton
            scope.launch {
                val machine = MachineCode.formatted(context)
                val result = withContext(Dispatchers.IO) {
                    Api.createCheckout(email, plan.code, machine)
                }
                val url = result.body?.optString("checkout_url") ?: ""
                if (result.ok && url.isNotBlank()) {
                    openUrl(context, url, lang)
                    onMessage(I18n.t(lang, "checkout_opened"))
                } else {
                    val reason = result.body?.optString("error") ?: "network_error"
                    onMessage(I18n.f(lang, "checkout_failed", reason))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        CenterNote(I18n.t(lang, "secure_note"))

        if (pendingOrder.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    I18n.f(lang, "pending_resume", pendingOrder),
                    color = Pal.Muted, fontSize = 10.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlineButton(
                    if (retrying) I18n.t(lang, "upload_parsing") else I18n.t(lang, "retry"),
                    Modifier.width(76.dp).height(30.dp)
                ) {
                    if (retrying) return@OutlineButton
                    retrying = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            Api.activate(pendingOrder, MachineCode.formatted(context))
                        }
                        val code = result.body?.optString("code") ?: ""
                        val verified = if (result.ok && code.isNotBlank()) {
                            LicenceVerifier.verify(context, code)
                        } else {
                            null
                        }
                        if (verified != null) {
                            state.apply(verified) // apply 成功后清除 pending
                            onMessage(I18n.t(lang, "msg_activated"))
                        } else {
                            onMessage(I18n.t(lang, "msg_payment_pending"))
                        }
                        retrying = false
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (emailError) {
        AlertDialog(
            onDismissRequest = { emailError = false },
            containerColor = Pal.Panel,
            titleContentColor = Pal.Text,
            textContentColor = Pal.Muted,
            title = {
                Text(I18n.t(lang, "error_email"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(I18n.t(lang, "email_dialog_body"), fontSize = 12.sp, lineHeight = 18.sp)
            },
            confirmButton = {
                TextButton(onClick = { emailError = false }) {
                    Text(I18n.t(lang, "update_ok"), color = Pal.Teal, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun EmailField(
    value: String,
    lang: AppLang,
    error: Boolean,
    focusRequester: FocusRequester,
    onChange: (String) -> Unit
) {
    val valid = Patterns.EMAIL_ADDRESS.matcher(value).matches()
    LaunchedEffect(error) { if (error) focusRequester.requestFocus() }
    Row(
        Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
            .background(Pal.Panel).border(
                if (error) 1.5.dp else 1.dp,
                if (error) Pal.Danger else Pal.Line2,
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(I18n.t(lang, "email_placeholder"), color = Pal.Faint, fontSize = 13.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Pal.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(Pal.Teal),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        }
        if (valid) {
            Box(
                Modifier.size(16.dp).background(Pal.Teal.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Pal.Teal, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CycleToggle(cycle: String, lang: AppLang, onChange: (String) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(15.dp)).background(Pal.Panel)
            .border(1.dp, Pal.Line2, RoundedCornerShape(15.dp)).padding(4.dp)
    ) {
        listOf("monthly" to I18n.t(lang, "monthly"), "annual" to I18n.t(lang, "annual")).forEach { (value, label) ->
            val active = cycle == value
            Box(
                Modifier.width(54.dp).height(22.dp).clip(RoundedCornerShape(11.dp))
                    .then(if (active) Modifier.background(Pal.Teal.copy(alpha = 0.18f)) else Modifier)
                    .clickable { onChange(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, color = if (active) Pal.Teal else Pal.Dim, fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: Plan,
    lang: AppLang,
    selected: Boolean,
    popular: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Pal.Teal.copy(alpha = 0.05f) else Pal.Panel)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) Pal.Teal else Pal.Line,
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .background(Pal.Teal.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Text("T${plan.tier}", color = Pal.Teal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plan.name, color = Pal.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                if (popular) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Pal.Teal.copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            I18n.t(lang, "popular"), color = Pal.Teal,
                            fontSize = 7.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (plan.cycle == "annual") I18n.f(lang, "pages_per_year", formatCount(plan.pages))
                else I18n.f(lang, "pages_per_month", formatCount(plan.pages)),
                color = Pal.Muted, fontSize = 9.5.sp
            )
        }
        Text("$${plan.price}", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(
            I18n.t(lang, if (plan.cycle == "annual") "per_year" else "per_month"),
            color = Pal.Faint, fontSize = 9.sp
        )
    }
}

/* ---------------------------------------------------------------- Quota */

@Composable
private fun QuotaScreen(
    context: Context,
    state: AppState,
    lang: AppLang,
    onRenew: () -> Unit,
    onCheckUpdate: () -> Unit,
    checkNote: String
) {
    val licence = state.licence
    val period = licence?.current()
    val total = period?.pages ?: licence?.periods?.lastOrNull()?.pages ?: TrialLedger.TRIAL_PAGES
    val remaining = state.capacity()
    val expiry = licence?.expiry() ?: "—"
    val planText = if (state.active) planDisplay(lang, licence?.plan ?: "") else I18n.t(lang, "free_trial")
    val usage = remember { LocalStats.monthlyUsage(context, 6) }
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
        }.getOrDefault("0.1.0")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(14.dp))
        BrandRow(if (state.active) I18n.t(lang, "chip_active") else I18n.t(lang, "chip_trial_short"))

        Spacer(Modifier.height(28.dp))
        Text(I18n.t(lang, "quota_title"), color = Pal.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(I18n.t(lang, "quota_sub"), color = Pal.Muted, fontSize = 11.5.sp)

        Spacer(Modifier.height(8.dp))
        QuotaRing(remaining, total, planText)

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoTile(I18n.t(lang, "expiry_label"), expiry, Modifier.weight(1f))
            InfoTile(
                I18n.t(lang, "days_left_label"),
                if (state.active) I18n.f(lang, "days_value", daysLeft(expiry)) else "—",
                Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(I18n.t(lang, "usage_label"))
            Spacer(Modifier.weight(1f))
            Text(
                I18n.f(lang, "usage_total", formatCount(usage.sumOf { it.second })),
                color = Pal.Teal, fontSize = 9.sp, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(14.dp))
        UsageBars(usage)

        Spacer(Modifier.height(26.dp))
        PanelCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.active) planText else I18n.t(lang, "inactive_title"),
                        color = Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.active) I18n.f(lang, "active_meta", formatCount(total), expiry)
                        else I18n.t(lang, "inactive_meta"),
                        color = Pal.Muted, fontSize = 9.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                OutlineButton(
                    I18n.t(lang, if (state.active) "renew" else "view_plans"),
                    Modifier.width(80.dp).height(32.dp)
                ) { onRenew() }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("v$version", color = Pal.Faint, fontSize = 9.5.sp)
            Spacer(Modifier.weight(1f))
            OutlineButton(
                I18n.t(lang, "check_update"),
                Modifier.width(112.dp).height(32.dp)
            ) { onCheckUpdate() }
        }
        if (checkNote.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(checkNote, color = Pal.Teal, fontSize = 10.sp)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.height(72.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Panel)
            .border(1.dp, Pal.Line, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, color = Pal.Faint, fontSize = 8.5.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(6.dp))
        Text(value, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun UsageBars(usage: List<Pair<String, Int>>) {
    val max = (usage.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(88.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        usage.forEach { (label, pages) ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.fillMaxWidth(0.62f)
                        .height((10 + 46f * pages / max).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Pal.accentGradient)
                )
                Spacer(Modifier.height(8.dp))
                Text(label, color = Pal.Faint, fontSize = 7.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun planDisplay(lang: AppLang, code: String): String {
    if (code.isEmpty()) return I18n.t(lang, "free_trial")
    val parts = code.split("_")
    val tier = parts.getOrNull(0)?.removePrefix("t") ?: "1"
    val name = when (tier) {
        "1" -> "Starter"
        "2" -> "Pro"
        "3" -> "Scale"
        else -> "Plan"
    }
    val cycle = I18n.t(lang, if (parts.getOrNull(1) == "annual") "cycle_annual" else "cycle_monthly")
    return "T$tier $name · $cycle"
}

private fun daysLeft(expiry: String): Long {
    if (expiry.length < 10) return 0
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val end = fmt.parse(expiry)?.time ?: return 0
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val endDay = Calendar.getInstance().apply {
            timeInMillis = end
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        ((endDay - today) / 86_400_000L).coerceAtLeast(0)
    } catch (e: Throwable) {
        0
    }
}

private fun openUrl(context: Context, url: String, lang: AppLang) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, I18n.t(lang, "open_browser_failed"), Toast.LENGTH_SHORT).show()
    }
}
