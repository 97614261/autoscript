package com.autoscript.studio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Installation-local configuration; neither project exports nor Android backups contain the key. */
internal interface AiSettingsRepository {
    fun read(): AiProvider
    fun save(value: AiProvider)
}

internal class AiSettingsStore(context: Context, namespace: String = "studio-ai") : AiSettingsRepository {
    init { require(namespace == "studio-ai" || namespace.matches(Regex("studio-ai-qa-[a-z0-9-]{1,48}"))) }
    private val preferences = context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val alias = "$namespace-settings-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    override fun read(): AiProvider {
        val saved = preferences.getString("configuration", null) ?: return AiProvider()
        require(saved.length <= 12_000)
        val bytes = Base64.decode(saved, Base64.NO_WRAP)
        require(bytes.size in 29..8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JsonParser.parseString(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)).asJsonObject
        return AiProvider(json["baseUrl"].asString, json["apiKey"].asString, json["model"].asString, json["completionTokens"].asBoolean)
    }

    override fun save(value: AiProvider) {
        value.validate()
        val json = JsonObject().apply {
            addProperty("baseUrl", value.baseUrl.trim()); addProperty("apiKey", value.apiKey)
            addProperty("model", value.model.trim()); addProperty("completionTokens", value.completionTokens)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == 12)
        val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("configuration", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "AI 配置未能保存，请重试"
        }
    }
}
