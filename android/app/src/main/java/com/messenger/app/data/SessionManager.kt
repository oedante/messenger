package com.messenger.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionManager(context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(context, "msg_session", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    var token: String?   get() = prefs.getString("token", null);    set(v) = prefs.edit().putString("token", v).apply()
    var userId: Int      get() = prefs.getInt("user_id", -1);       set(v) = prefs.edit().putInt("user_id", v).apply()
    var username: String? get() = prefs.getString("username", null); set(v) = prefs.edit().putString("username", v).apply()
    val isLoggedIn get() = token != null
    fun clear() = prefs.edit().clear().apply()
}
