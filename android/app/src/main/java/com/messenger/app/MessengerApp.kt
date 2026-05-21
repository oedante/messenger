package com.messenger.app

import android.app.Application
import android.util.Log
import com.messenger.app.crypto.KeyStoreManager
import com.messenger.app.data.SessionManager
import com.messenger.app.network.RetrofitClient
import com.messenger.app.network.WsManager
import com.messenger.app.security.SecurityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-класс:
 *  - Инициализирует безопасность при запуске
 *  - Восстанавливает сессию и E2E-ключи
 *  - Предупреждает о рутированном устройстве
 */
class MessengerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // ── Проверка безопасности среды ──────────────────────────────────────
        val report = SecurityManager.getSecurityReport(this)
        if (report.isRooted) {
            Log.w("MessengerApp", "WARNING: Device appears to be rooted!")
            // В продакшне можно показать предупреждение или заблокировать запуск:
            // showRootWarningAndExit()
        }
        if (report.isEmulator && !BuildConfig.DEBUG) {
            Log.w("MessengerApp", "WARNING: Running on emulator in release mode")
        }

        // ── Восстановление сессии ────────────────────────────────────────────
        val session = SessionManager(this)
        if (session.isLoggedIn) {
            RetrofitClient.setToken(session.token)
            WsManager.connect(session.token!!)
        }

        // ── Инициализация E2E ключей ─────────────────────────────────────────
        appScope.launch(Dispatchers.IO) {
            initE2EKeys(session)
        }
    }

    private suspend fun initE2EKeys(session: SessionManager) {
        if (!session.isLoggedIn) return
        val keyStore = KeyStoreManager(this)
        if (!keyStore.hasIdentityKey()) {
            // Генерируем пару ключей при первом запуске
            val publicKeyB64 = keyStore.generateAndSaveIdentityKey()
            // Загружаем публичный ключ на сервер
            try {
                RetrofitClient.api.uploadPublicKey(
                    com.messenger.app.models.PublicKeyRequest(publicKeyB64)
                )
                if (BuildConfig.DEBUG) Log.d("MessengerApp", "E2E public key uploaded")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("MessengerApp", "Failed to upload public key: ${e.message}")
            }
        }
    }
}
