package com.v2ray.ang.panel

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : BaseComponentActivity() {

    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        LoginEvent.OpenMain -> startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        LoginEvent.OpenAdmin -> startActivity(Intent(this@LoginActivity, AdminActivity::class.java))
                    }

                    finish()
                }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            LoginScreen(
                state = state,
                onUserLogin = viewModel::userLogin,
                onAdminLogin = viewModel::adminLogin,
            )
        }
    }
}
