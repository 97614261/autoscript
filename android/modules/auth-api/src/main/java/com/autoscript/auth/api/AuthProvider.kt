package com.autoscript.auth.api

import com.autoscript.core.model.LocalProfile

interface AuthProvider {
    fun currentProfile(): LocalProfile
}

interface EntitlementProvider {
    fun hasCapability(capability: String): Boolean
}
