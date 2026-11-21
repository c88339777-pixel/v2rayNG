package com.v2ray.ang.tools

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity

class ToolsActivity : BaseComponentActivity() {

    private val viewModel: ToolsViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(state.message) {
            state.message?.let {
                toast(it)
                viewModel.consumeMessage()
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ToolsScreen(
                state = state,
                onOutlineChange = viewModel::setOutlineInput,
                onRefreshFree = viewModel::refreshFreeServers,
                onImportOutline = viewModel::importOutline,
                onCreateWarp = viewModel::createWarp,
                onScan = viewModel::scanCleanIps,
                onApplyIps = viewModel::applyCleanIps,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsScreen(
    state: ToolsState,
    onOutlineChange: (String) -> Unit,
    onRefreshFree: () -> Unit,
    onImportOutline: () -> Unit,
    onCreateWarp: () -> Unit,
    onScan: () -> Unit,
    onApplyIps: () -> Unit,
) {
    val idle = state.busy == null

    Scaffold(
        topBar = { TopAppBar(title = { Text("ابزارها") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ToolCard(
                title = "سرورهای رایگان",
                description = "سرورهای عمومی چندپروتکلی را از لیست‌های GitHub می‌گیرد، روی اینترنت شما تست می‌کند و فقط سالم‌ها را نگه می‌دارد. چند دقیقه طول می‌کشد."
            ) {
                Button(onClick = onRefreshFree, enabled = idle) { Text("به‌روزرسانی الان") }
                if (state.busy == "free") Spinner()
            }

            ToolCard(
                title = "کلید Outline",
                description = "لینک ss:// یا ssconf:// را بچسبانید. چند کلید را می‌شود با هم وارد کرد."
            ) {
                OutlinedTextField(
                    value = state.outlineInput,
                    onValueChange = onOutlineChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    enabled = idle,
                    placeholder = { Text("ss://...  یا  ssconf://...") },
                )
                Button(
                    onClick = onImportOutline,
                    enabled = idle && state.outlineInput.isNotBlank()
                ) { Text("وارد کردن") }
                if (state.busy == "outline") Spinner()
            }

            ToolCard(
                title = "WARP",
                description = "یک حساب رایگان Cloudflare WARP می‌سازد و به‌صورت WireGuard در گروه «WARP» می‌گذارد. هر بار فشار دهید یک حساب جدید اضافه می‌شود."
            ) {
                Button(onClick = onCreateWarp, enabled = idle) { Text("ساخت حساب WARP") }
                if (state.busy == "warp") Spinner()
            }

            ToolCard(
                title = "اسکنر آی‌پی تمیز",
                description = "آی‌پی‌های Cloudflare را روی اینترنت شما می‌آزماید. با دکمه‌ی دوم، از کانفیگ‌های TLS پنل شما نسخه‌هایی با بهترین آی‌پی‌ها می‌سازد (کانفیگ‌های اصلی دست نمی‌خورند)."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onScan, enabled = idle) { Text("اسکن") }
                    OutlinedButton(
                        onClick = onApplyIps,
                        enabled = idle && state.scanResults.isNotEmpty()
                    ) { Text("ساخت کانفیگ با بهترین آی‌پی‌ها") }
                }
                state.scanProgress?.let { (done, total) -> Text("در حال اسکن: $done از $total") }
                if (state.busy == "scan" || state.busy == "apply") Spinner()
                state.scanResults.forEach { r ->
                    Text("${r.ip}   ${r.latencyMs} ms   ${r.colo}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToolCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun Spinner() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
    }
}
