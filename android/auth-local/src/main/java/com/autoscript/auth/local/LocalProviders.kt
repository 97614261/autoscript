package com.autoscript.auth.local

import com.autoscript.auth.api.AuthProvider
import com.autoscript.auth.api.EntitlementProvider
import com.autoscript.core.model.LocalProfile

class LocalAuthProvider : AuthProvider {
    override fun currentProfile(): LocalProfile = LocalProfile(
        id = "local",
        displayName = "本地用户",
        isRemoteAccount = false,
    )
}

class LocalEntitlementProvider : EntitlementProvider {
    override fun hasCapability(capability: String): Boolean = capability in LOCAL_CAPABILITIES

    private companion object {
        val LOCAL_CAPABILITIES = setOf("runtime.local", "automation.root")
    }
}
