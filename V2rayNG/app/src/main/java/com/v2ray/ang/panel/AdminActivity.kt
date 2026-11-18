package com.v2ray.ang.panel

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity

class AdminActivity : BaseComponentActivity() {

    private val viewModel: AdminViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(state.sessionExpired) {
            if (state.sessionExpired) {
                toast("نشست مدیر تمام شده است؛ دوباره وارد شوید.")
                openLogin()
            }
        }

        LaunchedEffect(state.message) {
            state.message?.let {
                toast(it)
                viewModel.consumeMessage()
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            AdminScreen(
                state = state,
                onRefresh = viewModel::refreshAll,
                onOpenVpn = { startActivity(Intent(this, MainActivity::class.java)) },
                onLogout = {
                    viewModel.logout()
                    openLogin()
                },
                onSaveConfig = viewModel::saveConfig,
                onToggleConfig = viewModel::setConfigActive,
                onDeleteConfig = viewModel::deleteConfig,
                onCreateUser = viewModel::createUser,
                onToggleUser = viewModel::setUserActive,
                onDeleteUser = viewModel::deleteUser,
                onSaveUser = viewModel::saveUser,
                onLoadAssigned = viewModel::loadAssigned,
                onSetAssigned = viewModel::setAssigned,
            )
        }
    }

    private fun openLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }
}
