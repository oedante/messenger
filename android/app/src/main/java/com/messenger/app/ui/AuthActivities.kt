package com.messenger.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.messenger.app.data.SessionManager
import com.messenger.app.databinding.ActivityLoginBinding
import com.messenger.app.databinding.ActivityRegisterBinding
import com.messenger.app.models.AuthRequest
import com.messenger.app.network.RetrofitClient
import com.messenger.app.network.WsManager
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var b: ActivityLoginBinding
    private lateinit var s: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater); setContentView(b.root)
        s = SessionManager(this)
        b.btnLogin.setOnClickListener { login() }
        b.tvRegister.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
    }

    private fun login() {
        val u = b.etUsername.text.toString().trim()
        val p = b.etPassword.text.toString()
        if (u.isEmpty() || p.isEmpty()) { toast("Заполните поля"); return }
        setLoading(true)
        lifecycleScope.launch {
            runCatching { RetrofitClient.api.login(AuthRequest(u, p, deviceName = "android")) }
                .onSuccess { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body()!!
                        s.token = body.token; s.userId = body.userId; s.username = u
                        RetrofitClient.setToken(body.token)
                        WsManager.connect(body.token)
                        startActivity(Intent(this@LoginActivity, RoomListActivity::class.java))
                        finish()
                    } else toast("Неверный логин или пароль")
                }
                .onFailure { toast("Ошибка сети: ${it.message}") }
            setLoading(false)
        }
    }

    private fun setLoading(v: Boolean) {
        b.btnLogin.isEnabled = !v
        b.progressBar.visibility = if (v) View.VISIBLE else View.GONE
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

class RegisterActivity : AppCompatActivity() {
    private lateinit var b: ActivityRegisterBinding
    private lateinit var s: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRegisterBinding.inflate(layoutInflater); setContentView(b.root)
        s = SessionManager(this)
        b.btnRegister.setOnClickListener { register() }
        b.tvLogin.setOnClickListener { finish() }
    }

    private fun register() {
        val u = b.etUsername.text.toString().trim()
        val p = b.etPassword.text.toString()
        val c = b.etConfirmPassword.text.toString()
        val n = b.etDisplayName.text.toString().trim()
        when {
            u.length < 3 -> { b.etUsername.error = "Мин 3 символа"; return }
            p.length < 6 -> { b.etPassword.error = "Мин 6 символов"; return }
            p != c       -> { b.etConfirmPassword.error = "Пароли не совпадают"; return }
        }
        setLoading(true)
        lifecycleScope.launch {
            runCatching { RetrofitClient.api.register(AuthRequest(u, p, n.ifEmpty { null }, "android")) }
                .onSuccess { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body()!!
                        s.token = body.token; s.userId = body.userId; s.username = u
                        RetrofitClient.setToken(body.token); WsManager.connect(body.token)
                        startActivity(Intent(this@RegisterActivity, RoomListActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                    } else toast("Имя занято")
                }
                .onFailure { toast("Ошибка: ${it.message}") }
            setLoading(false)
        }
    }

    private fun setLoading(v: Boolean) {
        b.btnRegister.isEnabled = !v
        b.progressBar.visibility = if (v) View.VISIBLE else View.GONE
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
