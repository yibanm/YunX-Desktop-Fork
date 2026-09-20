package com.yunx.app.ui.screens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.AppContext
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.backup.AuthCrypto
import com.yunx.app.data.backup.WebDavBackupManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.DownloadSaver
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.Log
import com.yunx.app.util.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 可选的下载线程数档位（最高 512） */
private val threadOptions = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512)

/** 按平台下载线程数设置项 */
private data class ThreadPlatform(val platform: String, val label: String)

private val threadPlatforms = listOf(
    ThreadPlatform(DownloadPlatform.QUARK, "夸克网盘"),
    ThreadPlatform(DownloadPlatform.UC, "UC 网盘"),
    ThreadPlatform(DownloadPlatform.XUNLEI, "迅雷网盘"),
    ThreadPlatform(DownloadPlatform.BAIDU, "百度网盘"),
    ThreadPlatform(DownloadPlatform.C139, "139 网盘"),
    ThreadPlatform(DownloadPlatform.PAN123, "123 云盘"),
)

/**
 * 设置页：下载线程数设置 + 主题外观 + 检查更新 + 日志与网盘认证。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    onThemeClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSupportClick: () -> Unit,
    backupManager: AuthBackupManager,
    /** 用应用内置下载器下载更新 APK（URL + 文件名），由 MainScreen 注入 DownloadManager */
    onDownloadUpdateApk: (url: String, fileName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showThreadsDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    // 检查更新结果（非空时弹更新对话框）
    var updateRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    // 网盘认证导出弹窗（AES 加密 + 导出范围）
    var showExportAuthDialog by remember { mutableStateOf(false) }
    // 网盘认证导入：加密文件内容（非空时弹解密密码框）
    var pendingImportContent by remember { mutableStateOf<String?>(null) }
    var showImportAuthDialog by remember { mutableStateOf(false) }
    // 导出/导入处理中（PBKDF2 21万次迭代派生密钥，偶发 1~3s，期间显示加载弹窗）
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    // 按平台线程数：二级弹窗当前选择的平台
    var selectedThreadPlatform by remember { mutableStateOf(threadPlatforms.first()) }
    var showPlatformThreadDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 下载保存目录：本地状态驱动 UI 刷新，同时同步 Preferences
    val settingsRepo = remember { SettingsRepository() }
    var downloadDirUri by remember { mutableStateOf(settingsRepo.downloadDirUri) }
    var showDevMenu by remember { mutableStateOf(false) }
    // 网络与下载策略（本地状态驱动 UI，同时同步 Preferences）
    var maxConcurrent by remember { mutableStateOf(settingsRepo.maxConcurrentDownloads) }
    var speedLimitBps by remember { mutableStateOf(settingsRepo.downloadSpeedLimit) }
    var retryCount by remember { mutableStateOf(settingsRepo.downloadRetryCount) }
    var showConcurrencyDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showRetryDialog by remember { mutableStateOf(false) }
    // 用户体验与系统适配：下载时阻止休眠 / 通知中心进度
    var keepAwake by remember { mutableStateOf(settingsRepo.keepAwakeWhileDownloading) }
    var showSpeed by remember { mutableStateOf(settingsRepo.notificationShowSpeed) }

    // 下载缓存位置：运行时注入 AppContext.customCacheDir（默认 = ~/.yunx-pc/cache）
    var cachePath by remember { mutableStateOf(settingsRepo.downloadCacheDir) }
    LaunchedEffect(cachePath) { AppContext.customCacheDir = cachePath }
    // 清除缓存二次确认
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    // 本地备份目录：默认 文档/YunX-Desktop，可自定义
    var localBackupPath by remember { mutableStateOf(settingsRepo.localBackupDir) }
    LaunchedEffect(localBackupPath) {
        AppContext.customBackupDir = localBackupPath
        settingsRepo.localBackupDir = localBackupPath
    }

    // WebDAV 备份弹窗
    var showWebDavDialog by remember { mutableStateOf(false) }
    // 本地备份弹窗
    var showLocalBackupDialog by remember { mutableStateOf(false) }
    var webdavServer by remember { mutableStateOf("") }
    var webdavUser by remember { mutableStateOf("") }
    var webdavPassword by remember { mutableStateOf("") }
    var includeFavorites by remember { mutableStateOf(true) }
    var includeLinkHistory by remember { mutableStateOf(true) }
    var includeDownloadRecords by remember { mutableStateOf(true) }
    var includeAuth by remember { mutableStateOf(false) }
    var isWebDavBusy by remember { mutableStateOf(false) }
    // WebDAV 连接自检结果
    var webdavTestSteps by remember { mutableStateOf<List<WebDavBackupManager.TestStep>>(emptyList()) }
    // 定时备份间隔（小时，0=关闭）
    var webdavInterval by remember { mutableStateOf(settingsRepo.webdavBackupIntervalHours) }
    var localInterval by remember { mutableStateOf(settingsRepo.localBackupIntervalHours) }
    var intervalMenuExpanded by remember { mutableStateOf(false) }
    var localIntervalMenuExpanded by remember { mutableStateOf(false) }
    // 远端 / 本地备份文件列表
    var remoteBackups by remember { mutableStateOf<List<WebDavBackupManager.BackupFile>>(emptyList()) }
    var showRemoteBackups by remember { mutableStateOf(false) }
    var localBackups by remember { mutableStateOf<List<WebDavBackupManager.BackupFile>>(emptyList()) }
    var showLocalBackups by remember { mutableStateOf(false) }
    val webDavManager = remember { WebDavBackupManager() }

    // 打开 WebDAV 弹窗时回填已保存的服务器 / 账号 / 定时间隔
    LaunchedEffect(showWebDavDialog) {
        if (showWebDavDialog) {
            webdavServer = settingsRepo.webdavServerUrl
            webdavUser = settingsRepo.webdavUsername
            webdavPassword = settingsRepo.webdavPassword
            webdavInterval = settingsRepo.webdavBackupIntervalHours
            webdavTestSteps = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionLabel("下载")
        SettingsItem(
            icon = Icons.Outlined.Tune,
            title = "下载线程数",
            description = "按网盘分别设置分片并发数（默认 32，最高 512）",
            onClick = { showThreadsDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 下载保存目录：系统目录选择器（桌面版为普通目录路径选择）
        // 已自定义时卡片右侧内嵌「恢复默认」操作（不单独外露按钮）
        SettingsItem(
            icon = Icons.Outlined.FolderOpen,
            title = "下载保存目录",
            description = downloadDirUri?.let { "已自定义：${DownloadSaver.safDirDisplay(it)}" }
                ?: "系统默认 Download（点击自定义）",
            onClick = {
                // 原生目录选择器在独立线程打开，避免阻塞 UI 线程导致水波动画卡顿
                scope.launch {
                    val dir = withContext(Dispatchers.IO) { DesktopActions.pickDirectory() }
                    if (dir != null) {
                        settingsRepo.downloadDirUri = dir
                        downloadDirUri = dir
                        SnackbarController.show("下载保存目录已更新")
                    }
                }
            },
            trailing = if (downloadDirUri != null) {
                {
                    TextButton(
                        onClick = {
                            downloadDirUri = null
                            settingsRepo.downloadDirUri = null
                            SnackbarController.show("已恢复默认下载目录")
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "恢复默认",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                null
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 下载缓存位置：点击卡片自定义目录；右侧「打开文件夹 / 删除缓存」带文字标签
        SettingsItem(
            icon = Icons.Outlined.FolderOpen,
            title = "下载缓存位置",
            description = cachePath?.let { "已自定义：$it（点击修改）" }
                ?: "默认 ${AppContext.cacheDir.absolutePath}（点击自定义）",
            onClick = {
                scope.launch {
                    val dir = withContext(Dispatchers.IO) { DesktopActions.pickDirectory() }
                    if (!dir.isNullOrBlank()) {
                        cachePath = dir
                        settingsRepo.downloadCacheDir = dir
                        SnackbarController.show("缓存路径已更新，重启后生效")
                    }
                }
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 打开缓存目录（图标+文字合并为一个按钮）
                    Button(
                        onClick = {
                            val ok = DesktopActions.openFile(AppContext.cacheDir.absolutePath)
                            if (!ok) SnackbarController.show("缓存目录不存在")
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打开文件夹", fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // 删除缓存（图标+文字合并为一个按钮，红色）
                    Button(
                        onClick = { showClearCacheConfirm = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除缓存", fontSize = 11.sp)
                    }
                    if (cachePath != null) {
                        TextButton(
                            onClick = {
                                cachePath = null
                                settingsRepo.downloadCacheDir = null
                                SnackbarController.show("已恢复默认缓存路径，重启后生效")
                            },
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text(
                                text = "恢复默认",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 网络与下载策略
        SettingsItem(
            icon = Icons.Outlined.Layers,
            title = "最大同时下载任务数",
            description = "同时下载 $maxConcurrent 个任务（限制后台并发，避免占满带宽）",
            onClick = { showConcurrencyDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Speed,
            title = "下载速度限制",
            description = speedLimitText(speedLimitBps),
            onClick = { showSpeedDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Refresh,
            title = "失败自动重试",
            description = if (retryCount == 0) "失败后不自动重试" else "失败后自动重试 $retryCount 次（断点续传）",
            onClick = { showRetryDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 用户体验与系统适配：下载时阻止休眠 / 通知中心进度
        SettingsItem(
            icon = Icons.Outlined.Power,
            title = "下载时阻止电脑休眠",
            description = "下载期间系统不会自动进入睡眠，任务结束后恢复",
            onClick = {
                keepAwake = !keepAwake
                settingsRepo.keepAwakeWhileDownloading = keepAwake
            },
            trailing = { Switch(checked = keepAwake, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Notifications,
            title = "通知中心下载进度",
            description = if (showSpeed) "Windows 通知中心进度条 + 下载速度" else "Windows 通知中心仅显示进度条（隐藏速度）",
            onClick = {
                showSpeed = !showSpeed
                settingsRepo.notificationShowSpeed = showSpeed
            },
            trailing = { Switch(checked = showSpeed, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("外观")
        SettingsItem(
            icon = Icons.Outlined.Palette,
            title = "主题与外观",
            description = "主题色、动态色彩与深色模式",
            onClick = onThemeClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("通用")
        SettingsItem(
            icon = Icons.Outlined.SystemUpdate,
            title = "检查更新",
            description = "检查 GitHub 是否有新版本可用",
            onClick = {
                scope.launch {
                    SnackbarController.show("正在检查更新…")
                    val release = runCatching { UpdateChecker.fetchLatestRelease() }.getOrNull()
                    val current = UpdateChecker.currentVersion()
                    if (release == null) {
                        SnackbarController.show("检查更新失败，请检查网络")
                    } else if (UpdateChecker.compareVersions(release.tagName, current) > 0) {
                        updateRelease = release
                    } else {
                        SnackbarController.show("已是最新版本")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.Article,
            title = "导出日志",
            description = "导出崩溃日志与应用信息，便于排查问题",
            onClick = { showLogDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("网盘认证")
        SettingsItem(
            icon = Icons.Outlined.Backup,
            title = "导出网盘认证",
            description = "使用至少 8 位口令加密 Cookie/JWT 后导出",
            onClick = { showExportAuthDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.Restore,
            title = "导入网盘认证",
            description = "选择加密或明文的认证备份文件，恢复网盘登录",
            onClick = {
                val path = DesktopActions.pickFile(
                    filters = listOf(
                        "认证备份文件 (*.yunx;*.json)" to "*.yunx;*.json",
                        "所有文件 (*.*)" to "*.*"
                    )
                )
                if (path != null) {
                    scope.launch {
                        isImporting = true
                        try {
                            // 读取文件内容：先判断是否加密备份，加密则弹密码框
                            val text = runCatching { java.io.File(path).readText() }.getOrNull()
                            if (text == null) {
                                SnackbarController.show("读取文件失败")
                                return@launch
                            }
                            if (AuthCrypto.isEncrypted(text)) {
                                // 加密备份：关闭加载弹窗，弹解密密码框（解密在确认后执行）
                                pendingImportContent = text
                                showImportAuthDialog = true
                            } else {
                                // 明文备份：直接导入
                                val count = runCatching {
                                    withContext(Dispatchers.IO) { backupManager.importJson(text) }
                                }.getOrElse { e ->
                                    SnackbarController.show("导入失败：${e.message}")
                                    return@launch
                                }
                                SnackbarController.show("已恢复 $count 个平台的认证信息")
                            }
                        } finally {
                            isImporting = false
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("备份与同步")
        SettingsItem(
            icon = Icons.Outlined.Backup,
            title = "WebDAV 备份",
            description = "将收藏、历史、下载记录等备份到 WebDAV 服务器",
            onClick = { showWebDavDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.Save,
            title = "本地备份",
            description = "备份到本地目录，支持定时备份和还原",
            onClick = { showLocalBackupDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("关于")
        SettingsItem(
            icon = Icons.Outlined.Info,
            title = "关于云析",
            description = "版本信息、支持平台与技术说明",
            onClick = onAboutClick,
            onLongClick = { showDevMenu = true } // 长按打开隐藏开发调试菜单
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.VolunteerActivism,
            title = "支持开发",
            description = "查看作者信息，去GitHub点个star支持项目",
            onClick = onSupportClick
        )
    }

    // 清除缓存二次确认
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("清除缓存") },
            text = { Text("确定要清除所有下载缓存文件吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                AppContext.downloadTmpDir.deleteRecursively()
                                AppContext.mergeDir.deleteRecursively()
                                AppContext.downloadTmpDir.mkdirs()
                                AppContext.mergeDir.mkdirs()
                            }
                        }
                        SnackbarController.show("下载缓存已清除")
                    }
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") }
            }
        )
    }

    // 导出日志方式选择弹窗
    FadeAlertDialog(
        visible = showLogDialog,
        onDismissRequest = { showLogDialog = false },
        title = { Text("导出日志") },
        text = {
            Column {
                Text(
                    text = "选择日志导出方式：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        showLogDialog = false
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { LogExporter.export() }
                            if (file != null && DesktopActions.revealFile(file.absolutePath)) {
                                SnackbarController.show("日志已导出，已在文件夹中显示")
                            } else {
                                SnackbarController.show("导出日志失败")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导出日志（在文件夹中显示）")
                }
                TextButton(
                    onClick = {
                        showLogDialog = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LogExporter.saveToDownloads()
                            }
                            SnackbarController.show(if (ok) "已保存到下载目录" else "保存失败")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存到下载目录")
                }
                TextButton(
                    onClick = {
                        showLogDialog = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LogExporter.clearLog()
                            }
                            SnackbarController.show(if (ok) "日志缓存已清空" else "清空失败")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清空日志缓存")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showLogDialog = false }) { Text("取消") }
        }
    )

    // 隐藏开发调试菜单（长按「关于云析」打开）
    FadeAlertDialog(
        visible = showDevMenu,
        onDismissRequest = { showDevMenu = false },
        title = { Text("开发调试") },
        text = {
            Column {
                Button(
                    onClick = {
                        showDevMenu = false
                        // 调试用途：直接弹出更新弹窗（不判断是否已是最新版），预览弹窗 UI
                        scope.launch {
                            val release = runCatching { UpdateChecker.fetchLatestRelease() }.getOrNull()
                            updateRelease = release ?: UpdateChecker.Release(
                                tagName = "v1.2.4（预览）",
                                body = "这是调试预览弹窗，用于查看更新弹窗 UI（含镜像站下载按钮）。",
                                assets = emptyList(),
                                publishedAt = "",
                                htmlUrl = ""
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("显示检查更新弹窗") }
            }
        },
        confirmButton = {
            TextButton(onClick = { showDevMenu = false }) { Text("关闭") }
        }
    )

    // 检查更新结果弹窗（发现新版本时展示，下载走应用内置下载器）
    updateRelease?.let { release ->
        UpdateDialog(
            currentVersion = UpdateChecker.currentVersion(),
            release = release,
            onDownload = {
                updateRelease = null
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", true) }
                if (apk != null) {
                    onDownloadUpdateApk(apk.downloadUrl, apk.name)
                    SnackbarController.show("已加入下载 ${apk.name}")
                } else {
                    SnackbarController.show("未找到 APK 下载链接")
                }
            },
            onDownloadMirror = {
                updateRelease = null
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", true) }
                if (apk != null) {
                    onDownloadUpdateApk(UpdateChecker.mirrorUrl(apk.downloadUrl), apk.name)
                    SnackbarController.show("已通过镜像站加入下载 ${apk.name}")
                } else {
                    SnackbarController.show("未找到 APK 下载链接")
                }
            },
            onLater = { updateRelease = null },
            onIgnore = {
                AppContext.miscPrefs.put("ignored_version", release.tagName)
                updateRelease = null
            }
        )
    }

    // 线程数选择弹窗（按平台）
    FadeAlertDialog(
        visible = showThreadsDialog,
        onDismissRequest = { showThreadsDialog = false },
        title = { Text("下载线程数") },
        text = {
            Column {
                Text(
                    text = "按网盘分别设置分片并发数；线程数不是越多越好，适当调整",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                threadPlatforms.forEach { item ->
                    val current = settingsRepo.downloadThreadsFor(item.platform)
                    val isXunlei = item.platform == DownloadPlatform.XUNLEI
                    val recommended = when (item.platform) {
                        DownloadPlatform.QUARK -> 32
                        DownloadPlatform.UC -> 32
                        DownloadPlatform.C139 -> 16
                        DownloadPlatform.PAN123 -> 16
                        DownloadPlatform.XUNLEI -> 8
                        DownloadPlatform.BAIDU -> 16
                        else -> 16
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isXunlei) {
                                selectedThreadPlatform = item
                                showPlatformThreadDialog = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (isXunlei) "固定 8 线程" else "$current 线程",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isXunlei) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                text = "推荐 $recommended 线程",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!isXunlei) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showThreadsDialog = false }) { Text("取消") }
        }
    )

    // 单个平台线程数选择（二级弹窗）
    FadeAlertDialog(
        visible = showPlatformThreadDialog,
        onDismissRequest = { showPlatformThreadDialog = false },
        title = { Text("${selectedThreadPlatform.label}线程数") },
        text = {
            val current = settingsRepo.downloadThreadsFor(selectedThreadPlatform.platform)
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                threadOptions.chunked(2).forEach { rowValues ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowValues.forEach { value ->
                            RadioThreadRow(
                                value = value,
                                threads = current,
                                onSelect = { v ->
                                    settingsRepo.setDownloadThreads(selectedThreadPlatform.platform, v)
                                    showPlatformThreadDialog = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 奇数个时补空占位，保持两列对齐
                        if (rowValues.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showPlatformThreadDialog = false }) { Text("取消") }
        }
    )

    // 导出网盘认证弹窗（AES 加密密码 + 导出范围）
    ExportAuthDialog(
        visible = showExportAuthDialog,
        onDismiss = { showExportAuthDialog = false },
        onConfirm = { password, onlyLoggedIn ->
            showExportAuthDialog = false
            isExporting = true
            scope.launch {
                try {
                    val content = runCatching {
                        withContext(Dispatchers.IO) { backupManager.export(password, onlyLoggedIn) }
                    }.onFailure { Log.e("YunX-Auth", "export failed", it) }.getOrNull()
                    if (content == null) {
                        SnackbarController.show("导出失败")
                        return@launch
                    }
                    val encrypted = true
                    // 加载弹窗先关闭，再弹系统「另存为」对话框（EDT 上模态阻塞）
                    isExporting = false
                    val target = DesktopActions.saveFile(
                        defaultName = backupManager.defaultBackupFileName(encrypted),
                        title = "导出网盘认证",
                        filters = listOf("云析认证备份 (*.yunx)" to "*.yunx", "所有文件 (*.*)" to "*.*"),
                        defaultExtension = "yunx"
                    ) ?: return@launch
                    val saved = withContext(Dispatchers.IO) {
                        backupManager.saveTo(content, java.io.File(target))
                    }
                    SnackbarController.show(
                        if (saved) "已导出到 ${java.io.File(target).parent}" else "导出失败"
                    )
                } finally {
                    isExporting = false
                }
            }
        }
    )

    // 导入加密备份弹窗（解密密码）
    ImportAuthDialog(
        visible = showImportAuthDialog,
        onDismiss = {
            showImportAuthDialog = false
            pendingImportContent = null
        },
        onConfirm = { password ->
            showImportAuthDialog = false
            val content = pendingImportContent
            pendingImportContent = null
            if (content != null) {
                isImporting = true
                scope.launch {
                    try {
                        val count = try {
                            withContext(Dispatchers.IO) { backupManager.import(content, password) }
                        } catch (e: javax.crypto.AEADBadTagException) {
                            SnackbarController.show("密码错误，解密失败")
                            return@launch
                        } catch (e: Exception) {
                            SnackbarController.show("导入失败：${e.message}")
                            return@launch
                        }
                        SnackbarController.show("已恢复 $count 个平台的认证信息")
                    } finally {
                        isImporting = false
                    }
                }
            }
        }
    )

    // 导出/导入处理中：转圈加载弹窗（PBKDF2 派生密钥耗时较长，避免用户以为界面卡死）
    OperationLoadingDialog(visible = isExporting, message = "正在导出认证…")
    OperationLoadingDialog(visible = isImporting, message = "正在导入认证…")

    // WebDAV 备份与同步弹窗
    FadeAlertDialog(
        visible = showWebDavDialog,
        onDismissRequest = { if (!isWebDavBusy) showWebDavDialog = false },
        title = { Text("WebDAV 备份与同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 服务器预设一键填充
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebDavBackupManager.PRESETS.forEach { (name, url) ->
                        AssistChip(
                            onClick = { webdavServer = url },
                            label = { Text(name, fontSize = 12.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = webdavServer,
                    onValueChange = { webdavServer = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://dav.example.com") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = webdavUser,
                    onValueChange = { webdavUser = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("用户名") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = webdavPassword,
                    onValueChange = { webdavPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Text(
                    text = "备份内容",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BackupCheckboxRow(checked = includeFavorites, label = "收藏的网盘链接") { includeFavorites = it }
                BackupCheckboxRow(checked = includeLinkHistory, label = "网盘解析历史") { includeLinkHistory = it }
                BackupCheckboxRow(checked = includeDownloadRecords, label = "下载记录") { includeDownloadRecords = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeAuth, onCheckedChange = { includeAuth = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("网盘认证", color = MaterialTheme.colorScheme.error)
                        Text(
                            text = "包含敏感数据，切勿随意分享",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                // 定时备份：关闭 / 每小时 / 每天 / 每周
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WebDAV定时：", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { intervalMenuExpanded = true }) {
                        Text(webdavIntervalLabel(webdavInterval))
                    }
                    DropdownMenu(
                        expanded = intervalMenuExpanded,
                        onDismissRequest = { intervalMenuExpanded = false }
                    ) {
                        listOf(
                            0 to "关闭",
                            1 to "每小时",
                            24 to "每天",
                            168 to "每周"
                        ).forEach { (hours, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    webdavInterval = hours
                                    settingsRepo.webdavBackupIntervalHours = hours
                                    intervalMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                // 连接自检：逐步显示 连通→建目录→上传→下载，便于定位 403 等问题
                OutlinedButton(
                    onClick = {
                        if (webdavServer.isBlank() || webdavUser.isBlank()) {
                            SnackbarController.show("请先填写服务器地址与用户名")
                            return@OutlinedButton
                        }
                        settingsRepo.webdavServerUrl = webdavServer.trim()
                        settingsRepo.webdavUsername = webdavUser.trim()
                        settingsRepo.webdavPassword = webdavPassword
                        val config = WebDavBackupManager.Config(webdavServer.trim(), webdavUser.trim(), webdavPassword)
                        isWebDavBusy = true
                        webdavTestSteps = emptyList()
                        scope.launch {
                            try {
                                webdavTestSteps = withContext(Dispatchers.IO) {
                                    webDavManager.testConnection(config)
                                }
                            } catch (e: Exception) {
                                webdavTestSteps = listOf(
                                    WebDavBackupManager.TestStep("连接与认证", false, e.message ?: "测试失败")
                                )
                            } finally {
                                isWebDavBusy = false
                            }
                        }
                    },
                    enabled = !isWebDavBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("测试连接（先点这里确认能上传，再备份）")
                }
                if (webdavTestSteps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    val allPassed = webdavTestSteps.all { it.ok }
                    Surface(
                        tonalElevation = 1.dp,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        color = if (allPassed)
                            androidx.compose.ui.graphics.Color(0xFFE8F5E9)
                        else
                            androidx.compose.ui.graphics.Color(0xFFFFEBEE),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                if (allPassed) "全部通过，可以正常备份" else "有步骤未通过，按下面提示处理",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (allPassed)
                                    androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                else
                                    androidx.compose.ui.graphics.Color(0xFFC62828)
                            )
                            Spacer(Modifier.height(6.dp))
                            webdavTestSteps.forEach { step ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Icon(
                                        if (step.ok) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                                        contentDescription = null,
                                        tint = if (step.ok)
                                            androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                        else
                                            androidx.compose.ui.graphics.Color(0xFFC62828),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(step.name, style = MaterialTheme.typography.labelMedium)
                                        Text(
                                            step.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (isWebDavBusy) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (webdavServer.isBlank() || webdavUser.isBlank()) {
                        SnackbarController.show("请填写服务器地址与用户名")
                        return@Button
                    }
                    settingsRepo.webdavServerUrl = webdavServer.trim()
                    settingsRepo.webdavUsername = webdavUser.trim()
                    settingsRepo.webdavPassword = webdavPassword
                    val config = WebDavBackupManager.Config(webdavServer.trim(), webdavUser.trim(), webdavPassword)
                    val options = WebDavBackupManager.BackupOptions(
                        includeFavorites = includeFavorites,
                        includeLinkHistory = includeLinkHistory,
                        includeDownloadRecords = includeDownloadRecords,
                        includeAuth = includeAuth
                    )
                    isWebDavBusy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { webDavManager.backup(config, options) }
                            SnackbarController.show("已备份到 WebDAV")
                        } catch (e: Exception) {
                            SnackbarController.show("备份失败：${e.message}")
                        } finally {
                            isWebDavBusy = false
                        }
                    }
                },
                enabled = !isWebDavBusy
            ) { Text("备份到 WebDAV") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (webdavServer.isBlank() || webdavUser.isBlank()) {
                        SnackbarController.show("请填写服务器地址与用户名")
                        return@TextButton
                    }
                    settingsRepo.webdavServerUrl = webdavServer.trim()
                    settingsRepo.webdavUsername = webdavUser.trim()
                    settingsRepo.webdavPassword = webdavPassword
                    val config = WebDavBackupManager.Config(webdavServer.trim(), webdavUser.trim(), webdavPassword)
                    isWebDavBusy = true
                    scope.launch {
                        try {
                            remoteBackups = withContext(Dispatchers.IO) { webDavManager.listBackups(config) }
                            showRemoteBackups = true
                        } catch (e: Exception) {
                            SnackbarController.show("获取备份列表失败：${e.message}")
                        } finally {
                            isWebDavBusy = false
                        }
                    }
                },
                enabled = !isWebDavBusy
            ) { Text("从 WebDAV 还原") }
        }
    )

    // 本地备份弹窗
    FadeAlertDialog(
        visible = showLocalBackupDialog,
        onDismissRequest = { if (!isWebDavBusy) showLocalBackupDialog = false },
        title = { Text("本地备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 备份目录：默认 文档/YunX-Desktop，可自定义、可打开
                val backupPathDisplay = localBackupPath ?: AppContext.backupDir.absolutePath
                Text(
                    text = "备份目录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = backupPathDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        scope.launch {
                            val dir = withContext(Dispatchers.IO) { DesktopActions.pickDirectory() }
                            if (!dir.isNullOrBlank()) {
                                localBackupPath = dir
                                SnackbarController.show("备份目录已更新")
                            }
                        }
                    }) { Text("选择", fontSize = 12.sp) }
                    TextButton(onClick = {
                        val ok = DesktopActions.openFile(backupPathDisplay)
                        if (!ok) SnackbarController.show("无法打开备份目录")
                    }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("打开", fontSize = 12.sp)
                    }
                    if (localBackupPath != null) {
                        TextButton(onClick = { localBackupPath = null }) {
                            Text("默认", fontSize = 12.sp)
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    text = "备份内容",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BackupCheckboxRow(checked = includeFavorites, label = "收藏的网盘链接") { includeFavorites = it }
                BackupCheckboxRow(checked = includeLinkHistory, label = "网盘解析历史") { includeLinkHistory = it }
                BackupCheckboxRow(checked = includeDownloadRecords, label = "下载记录") { includeDownloadRecords = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeAuth, onCheckedChange = { includeAuth = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("网盘认证", color = MaterialTheme.colorScheme.error)
                        Text(
                            text = "包含敏感数据，切勿随意分享",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("定时备份：", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { localIntervalMenuExpanded = true }) {
                        Text(webdavIntervalLabel(localInterval))
                    }
                    DropdownMenu(
                        expanded = localIntervalMenuExpanded,
                        onDismissRequest = { localIntervalMenuExpanded = false }
                    ) {
                        listOf(
                            0 to "关闭",
                            1 to "每小时",
                            24 to "每天",
                            168 to "每周"
                        ).forEach { (hours, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    localInterval = hours
                                    settingsRepo.localBackupIntervalHours = hours
                                    localIntervalMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                if (isWebDavBusy) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val options = WebDavBackupManager.BackupOptions(
                        includeFavorites = includeFavorites,
                        includeLinkHistory = includeLinkHistory,
                        includeDownloadRecords = includeDownloadRecords,
                        includeAuth = includeAuth
                    )
                    isWebDavBusy = true
                    scope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) { webDavManager.backupLocal(options) }
                            SnackbarController.show("已本地备份：${f.name}")
                        } catch (e: Exception) {
                            SnackbarController.show("本地备份失败：${e.message}")
                        } finally {
                            isWebDavBusy = false
                        }
                    }
                },
                enabled = !isWebDavBusy
            ) { Text("立即备份") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    isWebDavBusy = true
                    scope.launch {
                        try {
                            localBackups = withContext(Dispatchers.IO) { webDavManager.listLocalBackups() }
                            showLocalBackups = true
                        } catch (e: Exception) {
                            SnackbarController.show("获取本地备份失败：${e.message}")
                        } finally {
                            isWebDavBusy = false
                        }
                    }
                },
                enabled = !isWebDavBusy
            ) { Text("还原备份") }
        }
    )

    // 远端 WebDAV / 本地备份列表弹窗（选择某条后还原）
    if (showRemoteBackups) {
        RestoreBackupListDialog(
            title = "从 WebDAV 还原",
            backups = remoteBackups,
            onDismiss = { showRemoteBackups = false },
            onRestore = { fileName ->
                showRemoteBackups = false
                val config = WebDavBackupManager.Config(webdavServer.trim(), webdavUser.trim(), webdavPassword)
                scope.launch {
                    isWebDavBusy = true
                    try {
                        val n = withContext(Dispatchers.IO) { webDavManager.restore(config, fileName) }
                        SnackbarController.show("已从 WebDAV 还原 $n 条记录")
                    } catch (e: Exception) {
                        SnackbarController.show("还原失败：${e.message}")
                    } finally {
                        isWebDavBusy = false
                    }
                }
            }
        )
    }
    if (showLocalBackups) {
        RestoreBackupListDialog(
            title = "本地还原",
            backups = localBackups,
            onDismiss = { showLocalBackups = false },
            onRestore = { fileName ->
                showLocalBackups = false
                scope.launch {
                    isWebDavBusy = true
                    try {
                        val n = withContext(Dispatchers.IO) { webDavManager.restoreLocal(fileName) }
                        SnackbarController.show("已从本地还原 $n 条记录")
                    } catch (e: Exception) {
                        SnackbarController.show("还原失败：${e.message}")
                    } finally {
                        isWebDavBusy = false
                    }
                }
            },
            onOpenFolder = {
                val ok = DesktopActions.openFile(webDavManager.localDirPath())
                if (!ok) SnackbarController.show("无法打开备份目录")
            }
        )
    }

    // 最大同时下载任务数
    FadeAlertDialog(
        visible = showConcurrencyDialog,
        onDismissRequest = { showConcurrencyDialog = false },
        title = { Text("最大同时下载任务数") },
        text = {
            val options = listOf(1, 2, 3, 5, 8)
            Column {
                options.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = maxConcurrent == v,
                            onClick = {
                                maxConcurrent = v
                                settingsRepo.maxConcurrentDownloads = v
                                showConcurrencyDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("同时下载 $v 个任务", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showConcurrencyDialog = false }) { Text("取消") }
        }
    )

    // 下载速度限制：预设档位 + 自定义（KB/s）
    val speedPresets = listOf(0L, 1L * 1024 * 1024, 2L * 1024 * 1024, 5L * 1024 * 1024, 10L * 1024 * 1024)
    // 临时状态提升到弹窗外，供 text 与 confirmButton 共同访问；每次打开弹窗时重置
    var tempSelected by remember { mutableStateOf<Long?>(null) }
    var customKb by remember { mutableStateOf("") }
    LaunchedEffect(showSpeedDialog) {
        if (showSpeedDialog) {
            tempSelected = null
            // 自定义输入：打开时若当前是自定义档位，带出原值（重新打开保留）
            customKb = if (speedLimitBps > 0 && speedLimitBps !in speedPresets) (speedLimitBps / 1024).toString() else ""
        }
    }
    // 自定义选中态：显式识别「-1=自定义」哨兵；未操作时按当前值是否为自定义档位判断
    val isCustom = when {
        tempSelected == -1L -> true
        tempSelected == null -> speedLimitBps > 0 && speedLimitBps !in speedPresets
        else -> false
    }
    FadeAlertDialog(
        visible = showSpeedDialog,
        onDismissRequest = { showSpeedDialog = false },
        title = { Text("下载速度限制") },
        text = {
            val effective = tempSelected ?: speedLimitBps
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                speedPresets.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !isCustom && effective == v,
                            onClick = { tempSelected = v }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (v == 0L) "不限速" else speedLimitText(v),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                // 自定义档位：点击单选即可选中（进入自定义模式）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isCustom,
                        onClick = {
                            tempSelected = -1L
                            // 当前已是自定义值时带出原值，便于修改
                            if (speedLimitBps > 0 && speedLimitBps !in speedPresets && customKb.isBlank()) {
                                customKb = (speedLimitBps / 1024).toString()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = customKb,
                        onValueChange = {
                            customKb = it.filter(Char::isDigit).take(6)
                            // 输入即视为选择自定义
                            tempSelected = -1L
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("自定义 KB/s") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 以当前选中项为准：选自定义则应用输入；选预设则应用预设值
                    if (isCustom) {
                        val kb = customKb.toLongOrNull()?.coerceAtLeast(1L)
                        if (kb != null) {
                            speedLimitBps = kb * 1024
                            settingsRepo.downloadSpeedLimit = kb * 1024
                        }
                        // 自定义输入为空：保持原值
                    } else if (tempSelected != null) {
                        val v = tempSelected ?: speedLimitBps
                        speedLimitBps = v
                        settingsRepo.downloadSpeedLimit = v
                    }
                    // 未做任何选择：保持当前值
                    showSpeedDialog = false
                }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { showSpeedDialog = false }) { Text("取消") }
        }
    )

    // 失败自动重试次数
    FadeAlertDialog(
        visible = showRetryDialog,
        onDismissRequest = { showRetryDialog = false },
        title = { Text("失败自动重试") },
        text = {
            val options = listOf(0, 1, 2, 3, 5, 8, 10)
            Column {
                options.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = retryCount == v,
                            onClick = {
                                retryCount = v
                                settingsRepo.downloadRetryCount = v
                                showRetryDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (v == 0) "不自动重试" else "失败后自动重试 $v 次",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showRetryDialog = false }) { Text("取消") }
        }
    )
}

/** 导出网盘认证弹窗：AES 加密密码 + 导出范围（仅已登录 / 全部绑定） */
@Composable
private fun ExportAuthDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String, onlyLoggedIn: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var onlyLoggedIn by remember { mutableStateOf(true) }
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text("导出网盘认证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "设置至少 8 位密码对认证文件进行 AES 加密。密码请务必牢记，丢失无法找回。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("加密密码（至少 8 位）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Text(
                    text = "导出范围",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = onlyLoggedIn,
                        onClick = { onlyLoggedIn = true }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("仅导出当前已登录的网盘", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !onlyLoggedIn,
                        onClick = { onlyLoggedIn = false }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出全部绑定的网盘", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password, onlyLoggedIn) },
                enabled = password.length >= 8
            ) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 导入加密备份弹窗：输入解密密码 */
@Composable
private fun ImportAuthDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text("导入网盘认证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "该备份文件已加密，请输入导出时设置的密码进行解密。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("解密密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank()
            ) { Text("解密并导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 备份内容复选框行 */
@Composable
private fun BackupCheckboxRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** WebDAV 定时间隔显示文案（小时 → 关闭/每小时/每天/每周） */
private fun webdavIntervalLabel(hours: Int): String = when (hours) {
    0 -> "关闭"
    1 -> "每小时"
    24 -> "每天"
    168 -> "每周"
    else -> "每 $hours 小时"
}

/** 备份文件列表弹窗：列出文件名 + 日期 + 大小，点击某条触发还原 */
@Composable
private fun RestoreBackupListDialog(
    title: String,
    backups: List<WebDavBackupManager.BackupFile>,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
    onOpenFolder: (() -> Unit)? = null
) {
    FadeAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (backups.isEmpty()) {
                Text("暂无备份文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    backups.forEach { b ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { onRestore(b.name) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = b.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${formatBackupDate(b.lastModified)} · ${formatBackupSize(b.size)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = "还原",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (onOpenFolder != null) {
            {
                TextButton(onClick = onOpenFolder) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("打开文件夹")
                }
            }
        } else null
    )
}

private fun formatBackupDate(ts: Long): String {
    if (ts <= 0) return "未知时间"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
}

private fun formatBackupSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024.0; i++ }
    return String.format("%.1f %s", v, units[i])
}

/** 操作处理中弹窗：转圈加载 + 提示文案，禁止关闭（防止中途取消导致导入/导出状态不一致） */
@Composable
private fun OperationLoadingDialog(visible: Boolean, message: String) {
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = {},
        title = { Text(message) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    /** 长按回调（隐藏菜单等）；null 时不启用长按 */
    onLongClick: (() -> Unit)? = null,
    /** 自定义尾部内容（如「恢复默认」操作）；null 时显示默认 ChevronRight */
    trailing: @Composable (() -> Unit)? = null
) {
    val shape = MaterialTheme.shapes.large
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 线程数单选行（用于弹窗两列布局，每行占半宽） */
@Composable
private fun RadioThreadRow(
    value: Int,
    threads: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = threads == value,
            onClick = { onSelect(value) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$value 线程",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/** 速度限制展示文案：0=不限速；>=1MB/s 显示 MB/s，否则 KB/s */
private fun speedLimitText(bps: Long): String {
    if (bps <= 0) return "不限速"
    return if (bps >= 1024 * 1024) {
        val mb = bps / (1024.0 * 1024.0)
        if (mb >= 10) String.format("%.0f MB/s", mb) else String.format("%.1f MB/s", mb)
    } else {
        "${bps / 1024} KB/s"
    }
}
