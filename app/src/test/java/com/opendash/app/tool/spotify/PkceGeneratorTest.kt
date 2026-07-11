package com.opendash.app.tool.spotify

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PkceGeneratorTest {

    @Test
    fun `code verifier is url-safe and long enough`() {
        val verifier = PkceGenerator.generateCodeVerifier()

        assertThat(verifier.length).isAtLeast(43) // RFC 7636 minimum
        assertThat(verifier).matches("[A-Za-z0-9\\-_]+")
    }

    @Test
    fun `two code verifiers are different`() {
        val a = PkceGenerator.generateCodeVerifier()
        val b = PkceGenerator.generateCodeVerifier()

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `code challenge is deterministic for a given verifier`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        val challenge1 = PkceGenerator.generateCodeChallenge(verifier)
        val challenge2 = PkceGenerator.generateCodeChallenge(verifier)

        assertThat(challenge1).isEqualTo(challenge2)
    }

    @Test
    fun `code challenge matches known RFC 7636 test vector`() {
        // RFC 7636 Appendix B test vector.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val challenge = PkceGenerator.generateCodeChallenge(verifier)

        assertThat(challenge).isEqualTo(expectedChallenge)
    }

    @Test
    fun `code challenge has no padding characters`() {
        val challenge = PkceGenerator.generateCodeChallenge(PkceGenerator.generateCodeVerifier())

        assertThat(challenge).doesNotContain("=")
    }
}
