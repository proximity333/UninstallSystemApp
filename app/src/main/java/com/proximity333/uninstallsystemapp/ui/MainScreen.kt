@file:OptIn(ExperimentalMaterial3Api::class)

package com.proximity333.uninstallsystemapp.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.proximity333.uninstallsystemapp.data.AndroidUser
import com.proximity333.uninstallsystemapp.data.CriticalSystemPackages
import com.proximity333.uninstallsystemapp.data.InstalledPackage
import com.proximity333.uninstallsystemapp.data.PackageAction
import com.proximity333.uninstallsystemapp.data.PackageFilter
import com.proximity333.uninstallsystemapp.data.PackageState
import com.proximity333.uninstallsystemapp.data.PrivilegeBackend
import com.proximity333.uninstallsystemapp.data.PrivilegeStatus
import com.proximity333.uninstallsystemapp.feature.MainViewModel
import com.proximity333.uninstallsystemapp.feature.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A destructive action awaiting explicit confirmation. */
private data class PendingAction(val packageInfo: InstalledPackage, val action: PackageAction)

@Composable
fun MainScreen(
    state: UiState,
    snackbarHostState: SnackbarHostState,
    viewModel: MainViewModel,
) {
    var detailPackage by remember { mutableStateOf<InstalledPackage?>(null) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "系统应用卸载",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::scan, enabled = !state.scanning) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ConnectionPanel(state = state, viewModel = viewModel)
            FilterBar(state = state, viewModel = viewModel)
            if (state.scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            PackageList(
                state = state,
                onOpen = { detailPackage = it },
                onRetry = viewModel::scan,
            )
        }
    }

    detailPackage?.let { packageInfo ->
        PackageDetailsSheet(
            packageInfo = packageInfo,
            state = state,
            onDismiss = { detailPackage = null },
            onAction = { action ->
                pendingAction = PendingAction(packageInfo, action)
                detailPackage = null
            },
        )
    }

    pendingAction?.let { request ->
        ConfirmActionDialog(
            request = request,
            state = state,
            onDismiss = { pendingAction = null },
            onConfirm = {
                pendingAction = null
                viewModel.perform(request.packageInfo, request.action)
            },
        )
    }
}

@Composable
private fun ConnectionPanel(state: UiState, viewModel: MainViewModel) {
    val connection = state.connection
    val context = LocalContext.current
    val statusColor = when (connection.status) {
        PrivilegeStatus.READY -> Color(0xFF2E7D32)
        PrivilegeStatus.ERROR -> MaterialTheme.colorScheme.error
        PrivilegeStatus.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.tertiary
        PrivilegeStatus.CONNECTING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val backendName = if (connection.backend == PrivilegeBackend.SHIZUKU) "Shizuku" else "Root"
    val statusLabel = when (connection.status) {
        PrivilegeStatus.MISSING -> "未安装"
        PrivilegeStatus.STOPPED -> "未连接"
        PrivilegeStatus.PERMISSION_REQUIRED -> "等待授权"
        PrivilegeStatus.CONNECTING -> "连接中"
        PrivilegeStatus.READY -> "已连接"
        PrivilegeStatus.ERROR -> "连接失败"
    }
    val hint = when {
        connection.backend == PrivilegeBackend.ROOT && connection.status == PrivilegeStatus.CONNECTING ->
            "正在请求 Root 授权，请在 Root 管理器中允许。"
        connection.backend == PrivilegeBackend.ROOT && connection.status == PrivilegeStatus.ERROR ->
            "无法获得 Root 权限，请确认设备已 Root 且授权管理器可用。"
        connection.status == PrivilegeStatus.MISSING ->
            "请先安装 Shizuku，并通过 ADB 或无线调试启动服务。"
        connection.status == PrivilegeStatus.STOPPED ->
            "请在 Shizuku 中启动服务，然后回到本页重试。"
        connection.status == PrivilegeStatus.PERMISSION_REQUIRED ->
            "Shizuku 已运行，需要授予本应用使用权限。"
        connection.status == PrivilegeStatus.ERROR ->
            "连接失败，请检查权限服务是否可用后重试。"
        connection.status == PrivilegeStatus.READY ->
            "可以扫描设备上的应用。卸载、停用和恢复都会先确认。"
        else -> "正在检查权限服务…"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(backendName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(statusLabel, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = connection.backend == PrivilegeBackend.SHIZUKU,
                    onClick = { viewModel.selectBackend(PrivilegeBackend.SHIZUKU) },
                    label = { Text("Shizuku") },
                    enabled = connection.status != PrivilegeStatus.CONNECTING,
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = connection.backend == PrivilegeBackend.ROOT,
                    onClick = { viewModel.selectBackend(PrivilegeBackend.ROOT) },
                    label = { Text("Root") },
                    enabled = connection.status != PrivilegeStatus.CONNECTING,
                )
                Spacer(Modifier.weight(1f))
                when {
                    connection.status == PrivilegeStatus.READY -> Unit
                    connection.status == PrivilegeStatus.CONNECTING ->
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    connection.backend == PrivilegeBackend.SHIZUKU &&
                        connection.status == PrivilegeStatus.MISSING ->
                        TextButton(onClick = { openShizukuGuide(context) }) { Text("安装指南") }
                    connection.backend == PrivilegeBackend.SHIZUKU &&
                        connection.status == PrivilegeStatus.PERMISSION_REQUIRED ->
                        Button(onClick = viewModel::requestAccess) { Text("授权") }
                    else -> TextButton(onClick = viewModel::requestAccess) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(state: UiState, viewModel: MainViewModel) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索应用名或包名") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (state.query.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清除搜索")
                    }
                }
            } else {
                null
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.filter == PackageFilter.SYSTEM,
                onClick = { viewModel.setFilter(PackageFilter.SYSTEM) },
                label = { Text("系统") },
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = state.filter == PackageFilter.ALL,
                onClick = { viewModel.setFilter(PackageFilter.ALL) },
                label = { Text("全部") },
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = state.filter == PackageFilter.USER,
                onClick = { viewModel.setFilter(PackageFilter.USER) },
                label = { Text("用户") },
            )
            Spacer(Modifier.weight(1f))
            UserSelector(
                users = state.users,
                selectedUserId = state.selectedUserId ?: state.currentUserId,
                currentUserId = state.currentUserId,
                onSelect = viewModel::selectUser,
            )
        }
    }
}

@Composable
private fun UserSelector(
    users: List<AndroidUser>,
    selectedUserId: Int?,
    currentUserId: Int?,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("空间 ${selectedUserId ?: "-"}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Text("▾", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            users.forEach { user ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (user.id == currentUserId) {
                                "当前空间 (${user.id})"
                            } else {
                                "${user.name} (${user.id})"
                            },
                        )
                    },
                    onClick = {
                        onSelect(user.id)
                        expanded = false
                    },
                    leadingIcon = if (user.id == selectedUserId) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun PackageList(
    state: UiState,
    onOpen: (InstalledPackage) -> Unit,
    onRetry: () -> Unit,
) {
    val filtered = remember(state.packages, state.query, state.filter) {
        state.packages.asSequence()
            .filter {
                when (state.filter) {
                    PackageFilter.SYSTEM -> it.isSystem
                    PackageFilter.USER -> !it.isSystem
                    PackageFilter.ALL -> true
                }
            }
            .filter {
                state.query.isBlank() ||
                    it.label.contains(state.query, ignoreCase = true) ||
                    it.packageName.contains(state.query, ignoreCase = true)
            }
            .sortedWith(compareByDescending<InstalledPackage> { it.isSystem }.thenBy { it.label.lowercase() })
            .toList()
    }
    val userId = state.selectedUserId ?: state.currentUserId

    when {
        state.scanning && state.packages.isEmpty() -> CenteredLoading("正在读取应用列表…")
        !state.hasScanned -> CenteredMessage(
            title = "尚未扫描",
            detail = if (state.connection.status == PrivilegeStatus.READY) {
                "点按右上角刷新，读取设备上的应用。"
            } else {
                "连接 Shizuku 或 Root 后会自动扫描系统应用。"
            },
        )
        state.scanError != null && state.packages.isEmpty() -> ErrorState(state.scanError, onRetry)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${filtered.size} 个应用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "点按应用查看详情",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.scanError != null) {
                item(key = "error") {
                    ErrorBanner(state.scanError, onRetry)
                }
            }
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    CenteredMessage(
                        title = "没有匹配的应用",
                        detail = "尝试切换筛选条件或修改搜索内容。",
                        modifier = Modifier.padding(vertical = 48.dp),
                    )
                }
            }
            items(filtered, key = { it.packageName }) { packageInfo ->
                PackageRow(
                    packageInfo = packageInfo,
                    userId = userId,
                    onClick = { onOpen(packageInfo) },
                )
            }
        }
    }
}

@Composable
private fun PackageRow(packageInfo: InstalledPackage, userId: Int?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PackageIcon(packageInfo.packageName, packageInfo.isSystem)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    packageInfo.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    packageInfo.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StateBadge(packageInfo.stateFor(userId ?: -1))
                    if (packageInfo.isSystem) {
                        Tag(if (packageInfo.isUpdatedSystemApp) "系统更新" else "系统")
                    }
                }
            }
            Text(
                packageInfo.versionName.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(max = 72.dp)
                    .padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun PackageDetailsSheet(
    packageInfo: InstalledPackage,
    state: UiState,
    onDismiss: () -> Unit,
    onAction: (PackageAction) -> Unit,
) {
    val userId = state.selectedUserId ?: state.currentUserId ?: 0
    val userState = packageInfo.stateFor(userId)
    val protected = packageInfo.packageName in CriticalSystemPackages.names
    val canUninstall = userState == PackageState.INSTALLED &&
        packageInfo.permits(PackageAction.UNINSTALL, userId)
    val canDisable = packageInfo.permits(PackageAction.DISABLE, userId)
    val canRestore = packageInfo.permits(PackageAction.RESTORE, userId)
    val disabledBySystem = userState == PackageState.DISABLED && userId !in packageInfo.userDisabledIds

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PackageIcon(packageInfo.packageName, packageInfo.isSystem, size = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        packageInfo.label,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        packageInfo.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            InfoRow("版本", packageInfo.versionName ?: "未知")
            InfoRow("版本号", packageInfo.versionCode?.toString() ?: "未知")
            InfoRow(
                "类型",
                when {
                    packageInfo.isUpdatedSystemApp -> "系统应用（含更新）"
                    packageInfo.isSystem -> "系统应用"
                    else -> "用户应用"
                },
            )
            InfoRow("APK 路径", packageInfo.systemPath ?: "未知")
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("各空间状态", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            state.users.forEach { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            append(if (user.id == state.currentUserId) "当前空间" else user.name)
                            append(" (")
                            append(user.id)
                            append(")")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StateBadge(packageInfo.stateFor(user.id))
                }
            }
            if (disabledBySystem) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "该应用由系统停用，本工具无法直接启用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (protected) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "该应用被列为受保护的系统包。卸载或停用可能导致系统无法启动或关键功能失效，操作已被阻止。",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                if (packageInfo.isSystem) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            "卸载系统应用可能影响依赖它的功能，且部分 ROM 会拒绝卸载。若不确定，请先尝试「停用」，停用可随时恢复。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (canUninstall) {
                    Button(
                        onClick = { onAction(PackageAction.UNINSTALL) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text("卸载（清除当前空间数据）")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (canDisable) {
                    OutlinedButton(
                        onClick = { onAction(PackageAction.DISABLE) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("停用（保留数据）")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (canRestore) {
                    Button(
                        onClick = { onAction(PackageAction.RESTORE) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("恢复到当前空间")
                    }
                }
                if (!canUninstall && !canDisable && !canRestore) {
                    Text(
                        "当前空间没有可执行的操作。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmActionDialog(
    request: PendingAction,
    state: UiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val packageInfo = request.packageInfo
    val action = request.action
    val actionLabel = when (action) {
        PackageAction.UNINSTALL -> "卸载"
        PackageAction.DISABLE -> "停用"
        PackageAction.RESTORE -> "恢复"
    }
    val userLabel = state.users.firstOrNull { it.id == state.selectedUserId }?.let { user ->
        if (user.id == state.currentUserId) "当前空间 (${user.id})" else "${user.name} (${user.id})"
    } ?: "当前空间"
    val warning = when (action) {
        PackageAction.UNINSTALL -> if (packageInfo.isSystem) {
            "这是系统应用。卸载会清除该空间内的应用数据，依赖它的系统功能可能失效。若设备保留了 APK，可稍后恢复；否则可能需要重刷系统才能找回。"
        } else {
            "卸载会清除该空间内的应用数据，且无法通过本工具恢复。"
        }
        PackageAction.DISABLE -> "停用后该应用在当前空间不可用，但数据会保留，可以随时恢复。"
        PackageAction.RESTORE -> "将使用系统保留的 APK 把该应用恢复到当前空间。"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认$actionLabel？") },
        text = {
            Column {
                Text(packageInfo.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    packageInfo.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("空间：$userLabel", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (action == PackageAction.UNINSTALL) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (action == PackageAction.UNINSTALL) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
            ) {
                Text(actionLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            label,
            modifier = Modifier.width(76.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PackageIcon(packageName: String, isSystem: Boolean, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { loadPackageIcon(context, packageName) }
    }
    val shape = RoundedCornerShape(12.dp)
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(shape),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(
                    if (isSystem) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                packageName.substringAfterLast('.').take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = if (isSystem) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun StateBadge(state: PackageState?) {
    val (text, color) = when (state) {
        PackageState.INSTALLED -> "已安装" to MaterialTheme.colorScheme.primary
        PackageState.DISABLED -> "已停用" to MaterialTheme.colorScheme.tertiary
        PackageState.RESTORABLE -> "可恢复" to MaterialTheme.colorScheme.secondary
        PackageState.NOT_FOUND -> "未安装" to MaterialTheme.colorScheme.outline
        PackageState.UNKNOWN, null -> "未知" to MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun Tag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun CenteredLoading(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    detail: String,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private fun openShizukuGuide(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")),
        )
    }
}

private fun loadPackageIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
    val info = context.packageManager.getApplicationInfo(
        packageName,
        PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS,
    )
    info.loadIcon(context.packageManager).toBitmap(96, 96).asImageBitmap()
}.getOrNull()