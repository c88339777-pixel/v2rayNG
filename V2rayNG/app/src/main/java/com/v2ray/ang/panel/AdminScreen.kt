package com.v2ray.ang.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private val ConfigTypes = listOf("VLESS", "VMESS", "Trojan", "Shadowsocks", "WireGuard", "Other")

private val PanelBorder = Color(0xFF26364D)
private val PanelCard = Color(0xFF111827)
private val Success = Color(0xFF22C55E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    state: AdminUiState,
    onRefresh: () -> Unit,
    onOpenVpn: () -> Unit,
    onLogout: () -> Unit,
    onSaveConfig: (id: String?, name: String, type: String, config: String) -> Unit,
    onToggleConfig: (id: String, active: Boolean) -> Unit,
    onDeleteConfig: (id: String) -> Unit,
    onCreateUser: (username: String, password: String) -> Unit,
    onToggleUser: (id: String, active: Boolean) -> Unit,
    onDeleteUser: (id: String) -> Unit,
    onSaveUser: (
        id: String,
        newPassword: String,
        startDate: String,
        expiryDate: String,
        totalGb: String,
        usedGb: String,
    ) -> Unit,
    onLoadAssigned: (userId: String) -> Unit,
    onSetAssigned: (userId: String, configId: String, assigned: Boolean) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }

    var configDialog by rememberSaveable { mutableStateOf<String?>(null) } // "" = new, id = edit
    var showNewUser by rememberSaveable { mutableStateOf(false) }
    var editUserId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteConfigId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteUserId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("مدیریت Xylen VPN", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی لیست‌ها")
                    }
                    IconButton(onClick = onOpenVpn) {
                        Icon(Icons.Filled.VpnKey, contentDescription = "صفحه اتصال VPN")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "خروج از حساب مدیر")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (tab == 0) configDialog = "" else showNewUser = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = if (tab == 0) "افزودن کانفیگ" else "افزودن کاربر"
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TabButton("کانفیگ‌ها (${state.configs.size})", tab == 0, Modifier.weight(1f)) { tab = 0 }
                TabButton("کاربران (${state.users.size})", tab == 1, Modifier.weight(1f)) { tab = 1 }
            }

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }

            if (tab == 0) {
                ConfigList(
                    configs = state.configs,
                    onEdit = { configDialog = it },
                    onToggle = onToggleConfig,
                    onDelete = { deleteConfigId = it },
                )
            } else {
                UserList(
                    users = state.users,
                    onEdit = { editUserId = it },
                    onToggle = onToggleUser,
                    onDelete = { deleteUserId = it },
                )
            }
        }
    }

    // ----- dialogs -----

    configDialog?.let { id ->
        val existing = state.configs.firstOrNull { it.id == id }

        ConfigDialog(
            initial = existing,
            onDismiss = { configDialog = null },
            onSave = { name, type, config ->
                onSaveConfig(existing?.id, name, type, config)
                configDialog = null
            },
        )
    }

    if (showNewUser) {
        NewUserDialog(
            onDismiss = { showNewUser = false },
            onCreate = { username, password ->
                onCreateUser(username, password)
                showNewUser = false
            },
        )
    }

    editUserId?.let { id ->
        val user = state.users.firstOrNull { it.id == id }

        if (user == null) {
            editUserId = null
        } else {
            UserEditDialog(
                user = user,
                configs = state.configs,
                assignedIds = if (state.assignedUserId == user.id) state.assignedConfigIds else emptySet(),
                assignedLoading = state.assignedUserId != user.id || state.assignedLoading,
                assignedReady = state.assignedUserId == user.id && state.assignedReady,
                busyIds = if (state.assignedUserId == user.id) state.assigningConfigIds else emptySet(),
                onLoadAssigned = { onLoadAssigned(user.id) },
                onToggleAssigned = { configId, assigned -> onSetAssigned(user.id, configId, assigned) },
                onDismiss = { editUserId = null },
                onSave = { pass, start, expiry, total, used ->
                    onSaveUser(user.id, pass, start, expiry, total, used)
                    editUserId = null
                },
            )
        }
    }

    deleteConfigId?.let { id ->
        ConfirmDelete(
            text = "این کانفیگ برای همه‌ی کاربران حذف می‌شود. ادامه می‌دهید؟",
            onDismiss = { deleteConfigId = null },
            onConfirm = {
                onDeleteConfig(id)
                deleteConfigId = null
            },
        )
    }

    deleteUserId?.let { id ->
        ConfirmDelete(
            text = "این کاربر حذف می‌شود. ادامه می‌دهید؟",
            onDismiss = { deleteUserId = null },
            onConfirm = {
                onDeleteUser(id)
                deleteUserId = null
            },
        )
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else PanelCard,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else PanelBorder),
    ) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConfigList(
    configs: List<AdminConfig>,
    onEdit: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (configs.isEmpty()) {
        EmptyHint("هنوز کانفیگی اضافه نشده است. با دکمه‌ی + یکی بسازید.")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(configs, key = { it.id }) { config ->
            Card(
                colors = CardDefaults.cardColors(containerColor = PanelCard),
                border = BorderStroke(1.dp, PanelBorder),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            config.name,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            config.type,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = config.active,
                        onCheckedChange = { onToggle(config.id, it) },
                    )
                    IconButton(onClick = { onEdit(config.id) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "ویرایش ${config.name}")
                    }
                    IconButton(onClick = { onDelete(config.id) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "حذف ${config.name}",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun expiryText(user: AdminUser): Pair<String, Color> {
    val expiry = user.planExpiresAt?.take(10) ?: return "بدون محدودیت زمانی" to Color(0xFF94A3B8)

    return try {
        val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(expiry))

        when {
            days < 0 -> "منقضی شده ($expiry)" to Color(0xFFEF4444)
            days <= 3 -> "$days روز تا انقضا ($expiry)" to Color(0xFFF59E0B)
            else -> "$days روز تا انقضا ($expiry)" to Success
        }
    } catch (_: Exception) {
        expiry to Color(0xFF94A3B8)
    }
}

private fun gbText(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

@Composable
private fun UserList(
    users: List<AdminUser>,
    onEdit: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (users.isEmpty()) {
        EmptyHint("هنوز کاربری ساخته نشده است. با دکمه‌ی + یکی بسازید.")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(users, key = { it.id }) { user ->
            val (expiry, expiryColor) = expiryText(user)

            Card(
                modifier = Modifier.clickable { onEdit(user.id) },
                colors = CardDefaults.cardColors(containerColor = PanelCard),
                border = BorderStroke(1.dp, PanelBorder),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(user.username, fontWeight = FontWeight.Bold)
                            Text(
                                expiry,
                                color = expiryColor,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = user.active,
                            onCheckedChange = { onToggle(user.id, it) },
                        )
                        IconButton(onClick = { onEdit(user.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "ویرایش ${user.username}")
                        }
                        IconButton(onClick = { onDelete(user.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "حذف ${user.username}",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    val total = user.totalGb

                    Text(
                        text = if (total == null) {
                            "مصرف: ${gbText(user.usedGb)} گیگ (حجم نامحدود)"
                        } else {
                            "مصرف: ${gbText(user.usedGb)} از ${gbText(total)} گیگ"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (total != null && total > 0) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (user.usedGb / total).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDelete(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حذف") },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("حذف", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

@Composable
private fun ConfigDialog(
    initial: AdminConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, type: String, config: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(initial?.type?.ifBlank { null } ?: "VLESS") }
    var config by rememberSaveable { mutableStateOf(initial?.config.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "افزودن کانفیگ" else "ویرایش کانفیگ") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("نام کانفیگ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConfigTypes.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(if (option == "Other") "سایر" else option) },
                        )
                    }
                }

                OutlinedTextField(
                    value = config,
                    onValueChange = { config = it },
                    label = { Text("متن کانفیگ (لینک)") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, type, config) }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

@Composable
private fun NewUserDialog(
    onDismiss: () -> Unit,
    onCreate: (username: String, password: String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("افزودن کاربر") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("نام کاربری (انگلیسی)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("رمز (حداقل ۸ کاراکتر)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(username, password) }) { Text("ساخت") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

@Composable
private fun UserEditDialog(
    user: AdminUser,
    configs: List<AdminConfig>,
    assignedIds: Set<String>,
    assignedLoading: Boolean,
    assignedReady: Boolean,
    busyIds: Set<String>,
    onLoadAssigned: () -> Unit,
    onToggleAssigned: (configId: String, assigned: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        newPassword: String,
        startDate: String,
        expiryDate: String,
        totalGb: String,
        usedGb: String,
    ) -> Unit,
) {
    LaunchedEffect(user.id) { onLoadAssigned() }

    var password by rememberSaveable(user.id) { mutableStateOf("") }
    var start by rememberSaveable(user.id) { mutableStateOf(user.planStart?.take(10).orEmpty()) }
    var expiry by rememberSaveable(user.id) { mutableStateOf(user.planExpiresAt?.take(10).orEmpty()) }
    var total by rememberSaveable(user.id) { mutableStateOf(user.totalGb?.let { gbText(it) }.orEmpty()) }
    var used by rememberSaveable(user.id) { mutableStateOf(gbText(user.usedGb)) }

    fun setDays(days: Long) {
        start = LocalDate.now().toString()
        expiry = LocalDate.now().plusDays(days).toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("کاربر: ${user.username}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("رمز جدید (خالی = بدون تغییر)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("اشتراک", fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { setDays(30) }) { Text("۳۰ روز از امروز") }
                    OutlinedButton(onClick = { setDays(60) }) { Text("۶۰ روز") }
                    OutlinedButton(onClick = { setDays(90) }) { Text("۹۰ روز") }
                }

                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("شروع (2026-12-31 یا خالی)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it },
                    label = { Text("انقضا (2026-12-31 یا خالی = نامحدود)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = total,
                    onValueChange = { total = it },
                    label = { Text("حجم کل (گیگ، خالی = نامحدود)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = used,
                    onValueChange = { used = it },
                    label = { Text("حجم مصرف‌شده (گیگ)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("کانفیگ‌های این کاربر", fontWeight = FontWeight.Bold)

                if (configs.isEmpty()) {
                    Text(
                        "ابتدا از تب کانفیگ‌ها یک کانفیگ بسازید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (assignedLoading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            "در حال دریافت کانفیگ‌های کاربر...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!assignedReady) {
                    TextButton(onClick = onLoadAssigned) { Text("دریافت لیست ناموفق بود؛ تلاش مجدد") }
                }

                configs.forEach { config ->
                    val checked = config.id in assignedIds
                    val enabled = assignedReady && config.id !in busyIds

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onToggleAssigned(config.id, !checked) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = enabled,
                            onCheckedChange = { onToggleAssigned(config.id, it) },
                        )
                        Text(
                            text = if (config.active) config.name else "${config.name} (غیرفعال)",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(password, start, expiry, total, used) }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}
