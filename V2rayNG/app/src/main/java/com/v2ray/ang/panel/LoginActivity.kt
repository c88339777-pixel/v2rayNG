package com.v2ray.ang.panel

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("panel", MODE_PRIVATE)
    }

    private var loading = false

    private fun loadToken(): String? {
        val secureToken = SecureTokenStore.get(this)

        if (secureToken != null) {
            return secureToken
        }

        val oldToken = prefs.getString("token", null)

        if (!oldToken.isNullOrBlank()) {
            return try {
                SecureTokenStore.save(this, oldToken)
                prefs.edit().remove("token").apply()
                oldToken
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.layoutDirection =
            View.LAYOUT_DIRECTION_RTL

        val u = EditText(this).apply {
            hint = "نام کاربری"
            setSingleLine()
        }

        val p = EditText(this).apply {
            hint = "رمز عبور"
            setSingleLine()
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val msg = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
        }

        val btn = Button(this).apply {
            text = "ورود و دریافت کانفیگ‌ها"
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 160, 60, 60)

                addView(
                    TextView(context).apply {
                        text = "VPN Hub"
                        textSize = 28f
                        gravity = Gravity.CENTER
                    }
                )

                addView(u)
                addView(p)
                addView(btn)
                addView(msg)
            }
        )

        fun setLoading(value: Boolean, text: String) {
            loading = value
            btn.isEnabled = !value
            btn.text = if (value) "لطفاً صبر کنید..." else
                "ورود و دریافت کانفیگ‌ها"
            msg.text = text
        }

        fun sync(token: String) {
            if (loading) return

            lifecycleScope.launch {
                setLoading(true, "در حال دریافت کانفیگ‌ها...")

                try {
                    val result = withContext(Dispatchers.IO) {
                        PanelApi.configs(token)
                    }

                    when {
                        result.ok && result.links.isNotEmpty() -> {

                            val imported = withContext(Dispatchers.IO) {
                                AngConfigManager.importBatchConfig(
                                    result.links.joinToString("\n"),
                                    "",
                                    true
                                )
                            }

                            if (imported.first <= 0) {
                                setLoading(
                                    false,
                                    "کانفیگ دریافت شد، اما وارد برنامه نشد. قالب کانفیگ‌ها را بررسی کنید."
                                )
                                return@launch
                            }

                            startActivity(
                                Intent(
                                    this@LoginActivity,
                                    MainActivity::class.java
                                )
                            )

                            finish()
                        }

                        result.ok -> {
                            setLoading(
                                false,
                                "هنوز کانفیگی به حساب شما اختصاص داده نشده است."
                            )
                        }

                        result.token == "expired" -> {
                            SecureTokenStore.clear(this@LoginActivity)
                            prefs.edit().remove("token").apply()

                            setLoading(
                                false,
                                "نشست شما تمام شده است؛ دوباره وارد شوید."
                            )
                        }

                        else -> {
                            setLoading(false, result.msg)
                        }
                    }

                } catch (_: Exception) {
                    setLoading(
                        false,
                        "خطای غیرمنتظره هنگام دریافت کانفیگ‌ها"
                    )
                }
            }
        }

        btn.setOnClickListener {
            if (loading) return@setOnClickListener

            lifecycleScope.launch {
                setLoading(true, "در حال ورود...")

                val result = withContext(Dispatchers.IO) {
                    PanelApi.login(
                        u.text.toString().trim(),
                        p.text.toString()
                    )
                }

                if (result.ok && !result.token.isNullOrBlank()) {
                    try {
                        SecureTokenStore.save(
                            this@LoginActivity,
                            result.token
                        )

                        sync(result.token)

                    } catch (_: Exception) {
                        setLoading(
                            false,
                            "خطا در ذخیره امن اطلاعات ورود"
                        )
                    }
                } else {
                    setLoading(false, result.msg)
                }
            }
        }

        val saved = loadToken()

        if (saved != null) {
            sync(saved)
        }
    }
}
