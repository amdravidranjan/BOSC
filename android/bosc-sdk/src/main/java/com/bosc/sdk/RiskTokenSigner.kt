package com.bosc.sdk

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Signs each risk decision: HMAC-SHA256 over {score, level, nonce, timestamp} with a key that
 * never leaves the Android Keystore. This shows the shape of the idea only: a real deployment
 * relies on Play Integrity and verifies the token server-side (the server cannot verify a
 * device-local HMAC key without a key-exchange step).
 */
internal class RiskTokenSigner(private val alias: String) {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    fun sign(score: Float, level: RiskLevel): String = try {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val payload = """{"score":${"%.4f".format(java.util.Locale.US, score)},"level":"$level","nonce":"${b64(nonce)}","ts":${System.currentTimeMillis()}}"""
        val mac = Mac.getInstance("HmacSHA256").apply { init(key()) }.doFinal(payload.toByteArray())
        b64(payload.toByteArray()) + "." + b64(mac)
    } catch (e: Exception) {
        "unsigned:" + e.javaClass.simpleName
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
