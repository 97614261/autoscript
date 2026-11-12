package com.autoscript.studio

import com.google.gson.JsonObject

internal data class StudioAccountSession(
    val origin: String,
    val accountId: Long,
    val userName: String,
    val token: String,
    val backupKey: String,
    val expiresAt: Long,
    val allowHttp: Boolean = false,
) {
    override fun toString(): String = "StudioAccountSession(accountId=$accountId, userName=$userName)"

    fun validate() {
        val site = StudioUpdateSite.parse(origin)
        require(site.encrypted || allowHttp) { "CLOUD_HTTPS_REQUIRED" }
        require(accountId > 0 && userName.length in 2..64 && Regex("[a-f0-9]{64}").matches(token) &&
            ServerBackupCrypto.validKey(backupKey) && expiresAt > 0) { "CLOUD_SESSION_INVALID" }
    }
}

internal fun studioAccountSession(origin: String, allowHttp: Boolean, data: JsonObject): StudioAccountSession {
    val account = data.getAsJsonObject("account")
    return StudioAccountSession(origin, account["accountId"].asLong, account["userName"].asString,
        data["token"].asString, data["backupKey"].asString, data["expiresAt"].asLong, allowHttp).also { it.validate() }
}
