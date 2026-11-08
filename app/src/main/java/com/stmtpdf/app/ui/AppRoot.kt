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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.stmtpdf.app.storage.HistoryItem
import com.stmtpdf.app.storage.LocalStats
import com.stmtpdf.app.update.UpdateChecker
import com.stmtpdf.app.update.UpdateCheckResult
import com.stmtpdf.app.update.UpdateDownloadService
import com.stmtpdf.app.update.UpdateErrorText
import com.stmtpdf.app.update.UpdateInfo
import com.stmtpdf.app.update.UpdatePhase
import com.stmtpdf.app.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object AppEvents {
    val pendingReturn = mutableStateOf<Uri?>(null)
}

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
            bottomBar = { PillTabBar(tab, lang) { tab = it } }
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
    onNotify: (String, (() -> Unit)?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var parsing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var rowsJson by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var pagesInfo by remember { mutableStateOf("") }
    var baseName by remember { mutableStateOf("statement") }
    var history by remember { mutableStateOf(LocalStats.history(context)) }
    var previewExpanded by remember { mutableStateOf(false) }
    var showFormats by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val capacity = state.capacity()
        if (capacity <= 0) {
            onNotify(I18n.t(lang, "quota_empty"), null)
            onNeedActivate()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            parsing = true
            progress = 0f
            rows = emptyList()
            rowsJson = ""
            pagesInfo = ""
            status = I18n.t(lang, "parse_reading")
            try {
                val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
                val file = File(dir, "input-" + System.currentTimeMillis() + ".pdf")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                baseName = file.nameWithoutExtension
                val json = engine.parse(file, capacity) { no, total ->
                    progress = (no.toFloat() / total).coerceIn(0f, 1f)
                    status = I18n.f(lang, "parse_progress", no, total)
                }
                val pages = json.optInt("pages", 0)
                val total = json.optInt("totalPages", pages)
                val arr = json.optJSONArray("rows") ?: JSONArray()
                val list = mutableListOf<Map<String, Any?>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        mapOf(
                            "date" to o.optString("date"),
                            "desc" to o.optString("desc"),
                            "amount" to o.optDouble("amount", 0.0),
                            "balance" to if (o.isNull("balance")) null else o.optDouble("balance", 0.0),
                            "conf" to o.optInt("conf", 0)
                        )
                    )
                }
                rowsJson = arr.toString()
                rows = list
                pagesInfo = I18n.f(lang, "parse_summary", pages, total, list.size)
                state.consume(pages)
                LocalStats.addConversion(context, baseName, pages, list.size)
                history = LocalStats.history(context)
                status = ""
            } catch (e: Throwable) {
                status = I18n.f(lang, "parse_failed", e.message ?: "unknown")
            } finally {
                parsing = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(14.dp))
        BrandRow(
            chip = if (state.active) I18n.t(lang, "chip_active")
            else I18n.f(lang, "chip_trial", state.trialRemaining),
            lang = lang,
            onLangPick = onLangPick
        )

        Spacer(Modifier.height(28.dp))
        Text(
            I18n.t(lang, "home_kicker"), color = Pal.Teal, fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium, letterSpacing = 2.2.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            I18n.t(lang, "home_title"), color = Pal.Text, fontSize = 31.sp,
            fontWeight = FontWeight.Bold, lineHeight = 36.sp, letterSpacing = (-0.8).sp
        )
        Spacer(Modifier.height(10.dp))
        Text(I18n.t(lang, "home_sub"), color = Pal.Muted, fontSize = 13.sp)

        Spacer(Modifier.height(24.dp))
        UploadCard(
            lang = lang,
            parsing = parsing,
            capacity = state.capacity(),
            trialPages = TrialLedger.TRIAL_PAGES,
            active = state.active,
            onPick = {
                if (state.capacity() <= 0) {
                    onNotify(I18n.t(lang, "quota_empty"), null)
                    onNeedActivate()
                } else {
                    picker.launch(arrayOf("application/pdf"))
                }
            }
        )

        if (parsing || status.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = Pal.Teal,
                trackColor = Pal.Line
            )
            Spacer(Modifier.height(6.dp))
            Text(status, color = Pal.Faint, fontSize = 11.sp)
        }

        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(I18n.t(lang, "results"))
                Spacer(Modifier.weight(1f))
                Text(pagesInfo, color = Pal.Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(10.dp))
            PanelCard {
                Column(Modifier.padding(14.dp)) {
                    val visible = if (previewExpanded) rows.take(50) else rows.take(8)
                    visible.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(row["date"] as String, color = Pal.Muted, fontSize = 11.sp)
                            Text(
                                formatMoney(row["amount"] as Double),
                                color = if ((row["amount"] as Double) < 0) Pal.Danger else Pal.Teal,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            row["desc"] as String,
                            color = Pal.Text, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (rows.size > 8) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (previewExpanded) I18n.t(lang, "preview_collapse")
                            else I18n.f(lang, "preview_expand", rows.size),
                            color = Pal.Teal, fontSize = 10.5.sp,
                            modifier = Modifier.clickable { previewExpanded = !previewExpanded }
                        )
                    }
                    if (previewExpanded && rows.size > 50) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            I18n.f(lang, "preview_limit", rows.size),
                            color = Pal.Faint, fontSize = 10.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(I18n.t(lang, "recent"))
        Spacer(Modifier.height(10.dp))
        RecentCard(history, lang)

        Spacer(Modifier.height(if (rows.isNotEmpty()) 108.dp else 24.dp))
    }

    if (rows.isNotEmpty()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Pal.Bg)))
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            GradientButton(
                I18n.f(lang, "export_all", rows.size),
                height = 50.dp,
                radius = 15.dp
            ) { showFormats = true }
        }
    }
    }

    if (showFormats) {
        ModalBottomSheet(
            onDismissRequest = { showFormats = false },
            containerColor = Pal.Panel
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    .navigationBarsPadding().padding(bottom = 20.dp)
            ) {
                Text(
                    I18n.t(lang, "export_choose"), color = Pal.Text,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(pagesInfo, color = Pal.Faint, fontSize = 10.5.sp)
                Spacer(Modifier.height(16.dp))
                FormatOption("QBO", I18n.t(lang, "fmt_qbo_desc")) {
                    showFormats = false
                    export(context, engine, "qbo", rowsJson, baseName, lang, onNotify) {
                        history = LocalStats.history(context)
                    }
                }
                Spacer(Modifier.height(8.dp))
                FormatOption("Excel", I18n.t(lang, "fmt_xlsx_desc")) {
                    showFormats = false
                    export(context, engine, "xlsx", rowsJson, baseName, lang, onNotify) {
                        history = LocalStats.history(context)
                    }
                }
                Spacer(Modifier.height(8.dp))
                FormatOption("CSV", I18n.t(lang, "fmt_csv_desc")) {
                    showFormats = false
                    export(context, engine, "csv", rowsJson, baseName, lang, onNotify) {
                        history = LocalStats.history(context)
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatOption(title: String, desc: String, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F151C))
            .border(1.dp, Pal.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(desc, color = Pal.Muted, fontSize = 10.5.sp)
        }
        Text("→", color = Pal.Teal, fontSize = 14.sp)
    }
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
private fun RecentCard(history: List<HistoryItem>, lang: AppLang) {
    PanelCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            if (history.isEmpty()) {
                Text(
                    I18n.t(lang, "recent_empty"),
                    color = Pal.Faint, fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            } else {
                history.take(3).forEachIndexed { index, item ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF16222A)))
                    }
                    HistoryRow(item, index % 2 == 1, lang)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, warm: Boolean, lang: AppLang) {
    val accent = if (warm) Pal.Warm else Pal.Teal
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                item.format.take(3), color = accent, fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name, color = Pal.Text, fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                I18n.f(lang, "history_meta", item.pages, item.rows),
                color = Pal.Muted, fontSize = 9.5.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        InfoChip(item.format, accent)
    }
}

private fun formatMoney(v: Double): String {
    val sign = if (v < 0) "-" else ""
    return sign + "$" + String.format(Locale.US, "%.2f", kotlin.math.abs(v))
}

private fun export(
    context: Context,
    engine: PdfEngine,
    kind: String,
    rowsJson: String,
    baseName: String,
    lang: AppLang,
    notify: (String, (() -> Unit)?) -> Unit,
    onExported: () -> Unit
) {
    kotlinx.coroutines.MainScope().launch {
        try {
            val base64 = withContext(Dispatchers.Default) { engine.export(kind, rowsJson, baseName) }
            val fileName = baseName + "." + kind
            val mime = when (kind) {
                "qbo" -> "application/x-ofx"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else -> "text/csv"
            }
            val (uri, _) = withContext(Dispatchers.IO) { Exporter.save(context, base64, fileName, mime) }
            LocalStats.markExported(context, baseName, kind)
            onExported()
            notify(I18n.f(lang, "export_saved", fileName)) {
                Exporter.share(context, uri, mime)
            }
        } catch (e: Throwable) {
            notify(I18n.f(lang, "export_failed", e.message ?: "unknown"), null)
        }
    }
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
