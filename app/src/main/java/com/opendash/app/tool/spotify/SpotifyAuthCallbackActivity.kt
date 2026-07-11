package com.opendash.app.tool.spotify

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.opendash.app.MainActivity
import com.opendash.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives the redirect from Spotify's authorize page
 * (`opendash-spotify://callback?code=...&state=...`), completes the
 * PKCE token exchange, then returns to [MainActivity]. Registered
 * `android:exported="true"` (required for the system browser to be
 * able to launch it via the custom URI scheme) but does nothing with
 * the incoming data beyond parsing `code`/`state`/`error` — no
 * arbitrary deep-link surface.
 */
@AndroidEntryPoint
class SpotifyAuthCallbackActivity : ComponentActivity() {

    @Inject lateinit var authManager: SpotifyAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        val error = uri?.getQueryParameter("error")
        val code = uri?.getQueryParameter("code")
        val state = uri?.getQueryParameter("state")

        if (error != null) {
            Timber.w("Spotify authorization denied/failed: $error")
            toastAndFinish(getString(R.string.spotify_connect_failed))
            return
        }
        if (code == null || state == null) {
            Timber.w("Spotify callback missing code/state")
            toastAndFinish(getString(R.string.spotify_connect_failed))
            return
        }

        lifecycleScope.launch {
            val success = authManager.handleAuthorizationCode(code, state)
            toastAndFinish(
                getString(if (success) R.string.spotify_connect_success else R.string.spotify_connect_failed)
            )
        }
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        )
        finish()
    }
}
