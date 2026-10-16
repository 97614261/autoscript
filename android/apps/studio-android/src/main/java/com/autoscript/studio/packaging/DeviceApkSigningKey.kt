package com.autoscript.studio

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Key material belongs to this Studio installation; private bytes never leave AndroidKeyStore. */
internal object DeviceApkSigningKey {
    private const val ALIAS = "autoscript.device-runner.signing.v1"
    @Synchronized fun loadOrCreate(): ApkSigningIdentity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val expiry = Calendar.getInstance().apply { set(2100, Calendar.JANUARY, 1) }.time
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Autoscript Device Runner"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(946_684_800_000L)).setCertificateNotAfter(expiry)
                    .setUserAuthenticationRequired(false).build())
                generateKeyPair()
            }
        }
        return ApkSigningIdentity(store.getKey(ALIAS, null) as PrivateKey, store.getCertificate(ALIAS) as X509Certificate)
    }
}
