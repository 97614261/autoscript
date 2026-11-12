package com.autoscript.studio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Separate installation-local session; passwords are never persisted. */
internal class StudioAccountStore(context: Context, namespace: String = "studio-account") {
    companion object {
        private val changes = MutableStateFlow(0L)
        val revision = changes.asStateFlow()
    }
    private val production = namespace == "studio-account"
    init { require(namespace.matches(Regex("studio-account(?:-qa-[a-z0-9-]{1,64})?"))) }
    private val prefs = context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val alias = "$namespace-session-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun read(): StudioAccountSession? {
        val encoded = prefs.getString("session", null) ?: return null
        require(encoded.length <= 12000)
        val saved = Base64.decode(encoded, Base64.NO_WRAP)
        require(saved.size in 29..8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, saved.copyOfRange(0, 12)))
        val value = JsonParser.parseString(cipher.doFinal(saved.copyOfRange(12, saved.size)).toString(Charsets.UTF_8)).asJsonObject
        return StudioAccountSession(value["origin"].asString, value["accountId"].asLong, value["userName"].asString,
            value["token"].asString, value["backupKey"].asString, value["expiresAt"].asLong, value["allowHttp"].asBoolean).also { it.validate() }
    }

    fun save(session: StudioAccountSession) {
        session.validate()
        val value = JsonObject().apply {
            addProperty("origin", session.origin); addProperty("accountId", session.accountId); addProperty("userName", session.userName)
            addProperty("token", session.token); addProperty("backupKey", session.backupKey)
            addProperty("expiresAt", session.expiresAt); addProperty("allowHttp", session.allowHttp)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == 12)
        check(prefs.edit().putString("session", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putString("origin", session.origin).putString("lastUserName", session.userName).commit()) { "账号未能保存，请重试" }
        if (production) changes.update { it + 1 }
    }

    fun lastOrigin(): String = prefs.getString("origin", "").orEmpty()
    fun lastUserName(): String = prefs.getString("lastUserName", "").orEmpty()
    fun clear() {
        check(prefs.edit().remove("session").commit()) { "退出信息未能保存，请重试" }
        if (production) changes.update { it + 1 }
    }
}
