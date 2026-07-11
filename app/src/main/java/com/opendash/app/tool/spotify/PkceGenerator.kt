package com.opendash.app.tool.spotify

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * RFC 7636 (PKCE) code verifier/challenge generation for the Spotify
 * OAuth Authorization Code flow. PKCE lets a native/mobile app
 * authenticate without embedding a client secret — Spotify's
 * recommended flow for apps like this one.
 */
object PkceGenerator {

    private val urlSafeNoPadding: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** A high-entropy random string, base64url-encoded, RFC 7636-compliant length (43-128 chars). */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return urlSafeNoPadding.encodeToString(bytes)
    }

    /** SHA-256("S256") transform of [verifier], base64url-encoded without padding. */
    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return urlSafeNoPadding.encodeToString(digest)
    }
}
