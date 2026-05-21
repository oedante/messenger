package com.messenger.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.messenger.app.BuildConfig
import com.messenger.app.R
import com.messenger.app.data.SessionManager
import com.messenger.app.databinding.ActivityProfileBinding
import com.messenger.app.models.UpdateProfileRequest
import com.messenger.app.network.RetrofitClient
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class ProfileActivity : AppCompatActivity() {
    private lateinit var b: ActivityProfileBinding
    private lateinit var s: SessionManager
    private val PICK_IMG = 55

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProfileBinding.inflate(layoutInflater); setContentView(b.root)
        s = SessionManager(this)
        setSupportActionBar(b.toolbar); supportActionBar?.title = "Профиль"; supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.ivAvatar.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, PICK_IMG)
        }
        b.btnSave.setOnClickListener {
            lifecycleScope.launch {
                runCatching {
                    RetrofitClient.api.updateProfile(UpdateProfileRequest(
                        displayName = b.etDisplayName.text.toString().trim(),
                        bio         = b.etBio.text.toString().trim()))
                }.onSuccess { Toast.makeText(this@ProfileActivity, "Сохранено ✓", Toast.LENGTH_SHORT).show() }
                 .onFailure { Toast.makeText(this@ProfileActivity, "Ошибка", Toast.LENGTH_SHORT).show() }
            }
        }
        loadProfile()
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            runCatching { RetrofitClient.api.getMe() }
                .onSuccess { resp ->
                    resp.body()?.let { u ->
                        b.etUsername.setText(u.username)
                        b.etDisplayName.setText(u.displayName ?: "")
                        b.etBio.setText(u.bio ?: "")
                        if (u.avatar != null)
                            Glide.with(b.ivAvatar).load("${BuildConfig.SERVER_URL}/files/${u.avatar}").circleCrop().into(b.ivAvatar)
                        else
                            b.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
                    }
                }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMG && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                lifecycleScope.launch { uploadAvatar(uri) }
            }
        }
    }

    private suspend fun uploadAvatar(uri: Uri) {
        val tmp = File.createTempFile("avatar", ".jpg", cacheDir)
        contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
        val part = MultipartBody.Part.createFormData("file", tmp.name, tmp.asRequestBody("image/jpeg".toMediaTypeOrNull()))
        runCatching { RetrofitClient.api.uploadAvatar(part) }
            .onSuccess {
                Glide.with(b.ivAvatar).load(uri).circleCrop().into(b.ivAvatar)
                Toast.makeText(this, "Аватар обновлён", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
