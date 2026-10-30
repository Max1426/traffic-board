@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.liuliang.board.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuliang.board.TrafficApplication
import com.liuliang.board.TrafficNotifier
import com.liuliang.board.BuildConfig
import com.liuliang.board.data.*
import com.liuliang.board.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable fun TrafficBoard(app: TrafficApplication, requested: StateFlow<String?>, consumeRequested: () -> Unit) {
    val state by app.repository.state.collectAsStateWithLifecycle()
    BoardTheme(state.data.preferences) { BoardContent(app, state, requested, consumeRequested) }
}

@Composable private fun BoardContent(app: TrafficApplication, state: BoardState, requested: StateFlow<String?>, consumeRequested: () -> Unit) {
    val repo = app.repository
    val feedback = LocalBoardFeedback.current
    val notificationTarget by requested.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf("home") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(60000) } }
    fun refresh(id: String? = null, stale: Boolean = false) {
        app.scope.launch {
            try { if (id == null) repo.refreshAll(stale) else repo.refresh(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { snackbar.showSnackbar("刷新结果无法保存，请检查手机存储空间后重试") }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) refresh(stale = true) }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(notificationTarget, state.initialized) {
        if (state.initialized && notificationTarget != null) {
            selectedId = notificationTarget
            route = if (state.data.subscriptions.any { it.id == notificationTarget }) "detail" else "home"
            consumeRequested()
        }
    }
    BackHandler(route != "home" && !busy) { route = if (route == "edit" && editingId != null) "detail" else "home"; operationError = null }
    fun runOperation(action: suspend () -> Unit) {
        uiScope.launch {
            busy = true; operationError = null
            try { action(); feedback.emit(FeedbackKind.CONFIRM) }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { operationError = e.message ?: "请检查输入内容"; feedback.emit(FeedbackKind.REJECT) }
            catch (e: Exception) { operationError = "保存失败，请检查手机存储空间后重试"; feedback.emit(FeedbackKind.REJECT) }
            finally { busy = false }
        }
    }
    val backdrop = rememberGraphicsLayer().let { layer -> remember(layer) { BackdropSource(layer) } }
    Scaffold(containerColor = Background, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            FrostedBar(backdrop, top = true) {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                title = { Text(when (route) { "home" -> "流量看板"; "detail" -> "订阅详情"; "settings" -> "设置"; else -> if (editingId == null) "添加订阅" else "编辑订阅" }, fontWeight = FontWeight.Bold, fontSize = 23.sp) },
                navigationIcon = { if (route != "home") HapticIconButton(enabled = !busy, onClick = { route = if (route == "edit" && editingId != null) "detail" else "home"; operationError = null }) { BoardIcon(Glyph.BACK, description = "返回") } },
                actions = { if (route == "home") HapticIconButton(onClick = { route = "settings"; operationError = null }) { BoardIcon(Glyph.SETTINGS, description = "设置") } })
            }
        },
        bottomBar = { if (route == "home" && state.initialized && state.storageError == null) {
            FrostedBar(backdrop, top = false) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.CenterEnd) {
                HapticButton(modifier = Modifier.testTag("add-subscription").heightIn(min = 52.dp),
                    onClick = { editingId = null; operationError = null; route = "edit" }, shape = MaterialTheme.shapes.extraLarge) {
                    BoardIcon(Glyph.PLUS, tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)); Text("添加订阅", fontWeight = FontWeight.Bold)
                }
            }
            }
        } }
    ) { padding ->
        CompositionLocalProvider(LocalPageInsets provides padding) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop).widthIn(max = 720.dp), contentAlignment = Alignment.TopCenter) {
            when {
                !state.initialized -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
                state.storageError != null -> InfoPanel("本地数据暂时无法打开", state.storageError!!, Danger, Modifier.padding(20.dp))
                else -> when (route) {
                    "home" -> HomeScreen(state, now, onOpen = { selectedId = it; route = "detail" },
                        onRefresh = { refresh(it) }, onRefreshAll = { refresh() })
                    "settings" -> SettingsScreen(state.data.settings, state.data.preferences, busy, operationError,
                        onPreferencesChange = { preferences -> runOperation { repo.savePreferences(preferences) } }) { settings -> runOperation {
                        repo.saveSettings(settings); route = "home"; uiScope.launch { snackbar.showSnackbar("提醒设置已保存，下次成功刷新时生效") }
                    } }
                    "edit" -> EditorScreen(state.data.subscriptions.find { it.id == editingId }, busy, operationError) { name, url, ua -> runOperation {
                        val id = repo.saveSubscription(editingId, name, url, ua)
                        selectedId = id; route = "detail"; refresh(id)
                    } }
                    "detail" -> {
                        val sub = state.data.subscriptions.find { it.id == selectedId }
                        if (sub == null) { LaunchedEffect(Unit) { route = "home" } }
                        else DetailScreen(sub, state.data.settings, now, sub.id in state.refreshing, busy, operationError,
                            onRefresh = { refresh(sub.id) }, onEdit = { editingId = sub.id; operationError = null; route = "edit" },
                            onDelete = { runOperation { repo.deleteSubscription(sub.id); route = "home" } })
                    }
                }
            }
        }
    }
    }
}

@Composable private fun HomeScreen(state: BoardState, now: Long, onOpen: (String) -> Unit, onRefresh: (String) -> Unit, onRefreshAll: () -> Unit) {
    val subscriptions = state.data.subscriptions
    val warnings = subscriptions.count { it.failed || it.status in setOf(RefreshStatus.PARTIAL, RefreshStatus.NO_INFO) || AlertEngine.warning(it, state.data.settings, now) != null }
    LazyColumn(Modifier.fillMaxSize().testTag("home-list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = LocalPageInsets.current.calculateTopPadding() + 8.dp, bottom = LocalPageInsets.current.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { BoardIcon(Glyph.CHART, tint = LocalContentColor.current); Spacer(Modifier.width(10.dp)); Text("订阅总览", style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.height(16.dp))
                Text("剩余流量，一眼看清", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { Text(subscriptions.size.toString().padStart(2, '0'), style = MaterialTheme.typography.displaySmall); Text("已添加订阅", fontSize = 13.sp) }
                    Column(Modifier.weight(1f)) { Text(warnings.toString().padStart(2, '0'), style = MaterialTheme.typography.displaySmall); Text("需要留意", fontSize = 13.sp) }
                }
                Spacer(Modifier.height(18.dp))
                Text("每 6 小时自动更新 · 支持手动刷新", style = MaterialTheme.typography.bodySmall)
            }
            }
        }
        if (subscriptions.isNotEmpty() && (state.data.settings.usageEnabled || state.data.settings.expiryEnabled)) item { NotificationAccess(compact = true) }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("我的订阅", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (subscriptions.isNotEmpty()) HapticTextButton(onClick = onRefreshAll, enabled = state.refreshing.isEmpty()) {
                BoardIcon(Glyph.REFRESH, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (state.refreshing.isEmpty()) "全部刷新" else "更新中…")
            }
        } }
        if (subscriptions.isEmpty()) item {
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).padding(horizontal = 26.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(76.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraLarge), contentAlignment = Alignment.Center) { BoardIcon(Glyph.CLOUD, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                Spacer(Modifier.height(22.dp)); Text("从第一个订阅开始", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(12.dp)); Text("添加服务商提供的订阅链接，即可查看\n套餐流量与到期时间。", color = Muted, fontSize = 14.sp, lineHeight = 23.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(24.dp)); Text("链接加密保存在本机", color = Mint, fontSize = 12.sp)
            }
        }
        items(subscriptions, key = { it.id }) { sub -> SubscriptionCard(sub, state.data.settings, now, sub.id in state.refreshing, { onOpen(sub.id) }, { onRefresh(sub.id) }) }
        if (subscriptions.isNotEmpty()) item { Text("统计来自服务商，可能存在延迟。未提供的信息将显示为未知。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp) }
    }
}

@Composable private fun SubscriptionCard(sub: Subscription, settings: AlertSettings, now: Long, refreshing: Boolean, onOpen: () -> Unit, onRefresh: () -> Unit, expandName: Boolean = false) {
    val warning = AlertEngine.warning(sub, settings, now)
    val color = if (sub.failed) Danger else if (warning != null || sub.status in setOf(RefreshStatus.PARTIAL, RefreshStatus.NO_INFO)) Amber else Accent
    OutlinedCard(modifier = Modifier.fillMaxWidth().then(if (expandName) Modifier else Modifier.clickable(onClick = feedbackClick(onOpen))), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = Panel), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Column(Modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(PanelRaised, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { BoardIcon(Glyph.CLOUD) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sub.name, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = if (expandName) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                Text(sub.host, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HapticIconButton(onClick = onRefresh, enabled = !refreshing) { if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else BoardIcon(Glyph.REFRESH, tint = Muted, description = "刷新${sub.name}") }
        }
        Spacer(Modifier.height(20.dp))
        Text("已用 / 总量", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        Text("${Display.bytes(sub.info?.used)} / ${Display.bytes(sub.info?.limit)}", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("剩余 ${Display.bytes(sub.info?.remaining)}", color = if (sub.info?.remaining == null) Muted else if (warning != null) Amber else Mint, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(Display.percent(sub.info?.percent), color = color, fontSize = 13.sp)
        }
        Spacer(Modifier.height(9.dp))
        LinearProgressIndicator(progress = { ((sub.info?.percent ?: 0.0) / 100).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = color, trackColor = PanelRaised)
        Spacer(Modifier.height(16.dp))
        AdaptivePair({ Caption("到期时间", Display.date(sub.info?.expire)) }, { Caption("最近成功获取", sub.lastSuccessAt?.let { Display.date(it, true) } ?: "尚未获取") })
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(7.dp))
            Text(if (refreshing) "正在获取最新信息…" else if (sub.failed && sub.info != null) "更新失败 · 当前显示上次成功数据" else warning ?: Display.status(sub.status), color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
            BoardIcon(Glyph.ARROW, Modifier.size(18.dp), tint = Muted)
        }
    }
    }
}

@Composable private fun AdaptivePair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val large = LocalDensity.current.fontScale > 1.25f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 310.dp || large) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { first(); second() }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) { Box(Modifier.weight(1f)) { first() }; Box(Modifier.weight(1f)) { second() } }
    }
}

@Composable private fun Caption(label: String, value: String, valueColor: Color = Ink) {
    Column { Text(label, color = Muted, fontSize = 12.sp); Spacer(Modifier.height(5.dp)); Text(value, color = valueColor, fontSize = 13.sp, lineHeight = 20.sp) }
}

@Composable private fun DetailScreen(sub: Subscription, settings: AlertSettings, now: Long, refreshing: Boolean, busy: Boolean, error: String?, onRefresh: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var showUrl by rememberSaveable(sub.id) { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalPageInsets.current).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SubscriptionCard(sub, settings, now, refreshing, {}, onRefresh, expandName = true)
        if (sub.failed) InfoPanel("刷新未成功", "${Display.status(sub.status)}。${if (sub.info != null) "当前数值来自上次成功获取，请留意更新时间。" else "尚无可用统计数据。"}", Danger)
        if (sub.status == RefreshStatus.NO_INFO || sub.status == RefreshStatus.PARTIAL) InfoPanel("信息由服务商提供", "服务商未返回完整统计字段，缺少的流量或到期信息无法从链接中推算。", Amber)
        SectionPanel("流量明细") {
            AdaptivePair({ Caption("累计上传", Display.bytes(sub.info?.upload), Accent) }, { Caption("累计下载", Display.bytes(sub.info?.download), Accent) })
            HorizontalDivider(color = PanelRaised)
            AdaptivePair({ Caption("完整到期时间", Display.date(sub.info?.expire, true)) }, { Caption("最近尝试刷新", sub.lastAttemptAt?.let { Display.date(it, true) } ?: "尚未刷新") })
            Text("流量按 1024 进制显示（GiB / MiB）；到期时间使用手机本地时区。", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
        }
        SectionPanel("订阅信息") {
            Caption("来源", sub.host)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("订阅链接", modifier = Modifier.weight(1f), color = Muted, fontSize = 12.sp); HapticTextButton(onClick = { showUrl = !showUrl }) { Text(if (showUrl) "隐藏链接" else "显示链接") } }
            if (showUrl) SelectionContainer { Text(sub.url, fontSize = 13.sp, lineHeight = 21.sp) } else Text("••••••••••••••••••••", color = Muted)
            Caption("请求标识（User-Agent）", sub.userAgent)
        }
        error?.let { Text(it, color = Danger) }
        HapticButton(onClick = onRefresh, enabled = !refreshing && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = MaterialTheme.shapes.extraLarge) { Text(if (refreshing) "正在刷新…" else "刷新订阅") }
        HapticOutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = MaterialTheme.shapes.extraLarge) { Text("编辑订阅") }
        HapticTextButton(onClick = { deleteDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("删除订阅", color = Danger) }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除这个订阅？") },
        text = { Text("将移除「${sub.name}」的链接与本地统计。不会影响服务商账号。") },
        confirmButton = { HapticTextButton(onClick = { deleteDialog = false; onDelete() }) { Text("删除", color = Danger) } },
        dismissButton = { HapticTextButton(onClick = { deleteDialog = false }) { Text("取消") } })
}

@Composable private fun EditorScreen(sub: Subscription?, busy: Boolean, error: String?, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable(sub?.id) { mutableStateOf(sub?.name ?: "") }
    // URLs remain only in memory while editing; do not put credentials into Activity saved state.
    var url by remember(sub?.id) { mutableStateOf(sub?.url ?: "") }
    var ua by rememberSaveable(sub?.id) { mutableStateOf(sub?.userAgent ?: Subscription.DEFAULT_UA) }
    var showUrl by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(LocalPageInsets.current).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        InfoPanel("添加你的订阅", "使用服务商提供的订阅链接，应用会读取套餐流量和到期信息。", Accent)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("订阅名称") }, placeholder = { Text("例如：日常使用、备用订阅") }, modifier = Modifier.fillMaxWidth().testTag("name-input"), enabled = !busy, singleLine = true)
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("订阅链接") }, placeholder = { Text("https://…") },
            visualTransformation = if (showUrl) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            trailingIcon = { HapticIconButton(onClick = { showUrl = !showUrl }) { BoardIcon(Glyph.EYE, description = if (showUrl) "隐藏链接" else "显示链接") } },
            modifier = Modifier.fillMaxWidth().testTag("url-input"), enabled = !busy, singleLine = true)
        HapticTextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置 · 请求标识") }
        if (advanced) {
            OutlinedTextField(value = ua, onValueChange = { ua = it }, label = { Text("User-Agent") }, modifier = Modifier.fillMaxWidth().testTag("ua-input"), enabled = !busy, singleLine = true)
            Text("部分服务商会根据请求标识返回统计信息。默认包含 clash，可按服务商要求调整。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        }
        error?.let { Text(it, color = Danger, modifier = Modifier.testTag("editor-error")) }
        HapticButton(onClick = { onSave(name, url, ua) }, enabled = !busy, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (busy) "正在保存…" else "保存并刷新", fontWeight = FontWeight.Bold) }
        Text("链接加密保存在本机，不上传到额外的服务器。修改链接或请求标识后，会重新获取统计信息。", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
    }
}

@Composable private fun SettingsScreen(settings: AlertSettings, preferences: UiPreferences, busy: Boolean, error: String?, onPreferencesChange: (UiPreferences) -> Unit, onSave: (AlertSettings) -> Unit) {
    var usageEnabled by rememberSaveable { mutableStateOf(settings.usageEnabled) }
    var expiryEnabled by rememberSaveable { mutableStateOf(settings.expiryEnabled) }
    var usageText by rememberSaveable { mutableStateOf(settings.usageThresholds.joinToString(", ")) }
    var daysText by rememberSaveable { mutableStateOf(settings.expiryDays.joinToString(", ")) }
    var validation by remember { mutableStateOf<String?>(null) }
    val feedback = LocalBoardFeedback.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(LocalPageInsets.current).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionPanel("外观") {
            Text("Material 3", style = MaterialTheme.typography.labelLarge, color = Accent)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(selected = preferences.themeMode == mode, onClick = feedbackClick { onPreferencesChange(preferences.copy(themeMode = mode)) },
                        enabled = !busy, shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size), modifier = Modifier.testTag("theme-${mode.name}")) { Text(mode.label) }
                }
            }
            SettingSwitch("壁纸动态配色", preferences.dynamicColors, !busy && Build.VERSION.SDK_INT >= 31, "dynamic-colors") { onPreferencesChange(preferences.copy(dynamicColors = it)) }
            Text(if (Build.VERSION.SDK_INT >= 31) "使用手机壁纸的系统配色；关闭后使用默认蓝色主题。外观设置即时保存。" else "当前安卓版本使用默认蓝色主题；Android 12 及以上支持壁纸动态配色。", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        SectionPanel("触感反馈") {
            SettingSwitch("按键震动", preferences.hapticsEnabled, !busy, "haptics-switch") { onPreferencesChange(preferences.copy(hapticsEnabled = it)) }
            Text("按钮、卡片和开关提供轻量触感，保存与输入错误使用对应反馈。遵循手机的系统触感设置，开关即时保存。", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        NotificationAccess(compact = false)
        SectionPanel("流量提醒") {
            SettingSwitch("额度不足时提醒", usageEnabled, !busy) { usageEnabled = it }
            OutlinedTextField(value = usageText, onValueChange = { usageText = it; validation = null }, label = { Text("已使用百分比（%）") }, supportingText = { Text("填写 1–100，以逗号分隔，最多 8 个阈值") }, enabled = usageEnabled && !busy, modifier = Modifier.fillMaxWidth().testTag("usage-thresholds"), singleLine = true)
        }
        SectionPanel("到期提醒") {
            SettingSwitch("临近到期时提醒", expiryEnabled, !busy) { expiryEnabled = it }
            OutlinedTextField(value = daysText, onValueChange = { daysText = it; validation = null }, label = { Text("到期前天数") }, supportingText = { Text("填写 0–365，以逗号分隔；已到期也会提醒") }, enabled = expiryEnabled && !busy, modifier = Modifier.fillMaxWidth().testTag("expiry-thresholds"), singleLine = true)
        }
        (validation ?: error)?.let { Text(it, color = Danger) }
        HapticButton(onClick = {
            val usage = if (usageEnabled) InputRules.thresholds(usageText, true) else settings.usageThresholds
            val days = if (expiryEnabled) InputRules.thresholds(daysText, false) else settings.expiryDays
            if (usage == null || days == null) { validation = "请检查阈值范围，并用逗号分隔多个数字"; feedback.emit(FeedbackKind.REJECT) }
            else onSave(AlertSettings(usageEnabled, expiryEnabled, usage, days))
        }, enabled = !busy, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (busy) "正在保存…" else "保存设置") }
        InfoPanel("刷新与提醒说明", "每 6 小时自动获取一次，系统省电可能推迟更新。打开应用时会补充刷新超过 6 小时的数据，也可手动刷新。提醒在成功获取新数据后判断。\n\n同一阈值不会重复提醒；用量回落到阈值以下、到期日变化或新增阈值后，可再次触发。未允许通知时，应用内仍显示警示。", Muted)
        Text("流量看板 ${BuildConfig.VERSION_NAME} · 数据保存在本机", color = Muted, fontSize = 12.sp)
    }
}

@Composable private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, tag: String = label, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); Spacer(Modifier.width(12.dp)); HapticSwitch(checked, onCheckedChange = onChecked, enabled = enabled, modifier = Modifier.testTag(tag)) }
}

@Composable private fun NotificationAccess(compact: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    fun permitted(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(TrafficNotifier.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    var allowed by remember { mutableStateOf(permitted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true; allowed = permitted() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = permitted() }
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    if (allowed && compact) return
    Column(Modifier.fillMaxWidth().background(if (allowed) Panel else MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large).padding(16.dp)) {
        Text(if (allowed) "系统通知已开启" else "开启通知，及时留意额度", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (allowed) Mint else Amber)
        if (!compact) { Spacer(Modifier.height(8.dp)); Text("通知权限只用于流量和到期提醒。通知关闭时，应用内警示仍可查看。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp) }
        if (!allowed) HapticTextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !asked) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text(if (asked || Build.VERSION.SDK_INT < 33) "前往系统设置" else "开启通知") }
    }
}

@Composable private fun SectionPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp); content()
    }
}

@Composable private fun InfoPanel(title: String, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(17.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BoardIcon(Glyph.INFO, tint = color)
        Column { Text(title, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Spacer(Modifier.height(7.dp)); Text(text, color = Muted, fontSize = 13.sp, lineHeight = 21.sp) }
    }
}
