package com.leeotts.cicero

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.leeotts.cicero.ai.oauth.OAuthResult

/**
 * A no-display trampoline for the OpenRouter redirect.
 *
 * A separate activity rather than a second filter on [MainActivity], for two
 * reasons: MainActivity is launchMode="standard" and never reads its intent, and
 * routing through it would flash the whole UI in the middle of the flow.
 *
 * It also keeps the Meta AI deep link untouched. That filter is scheme-only on
 * "cicero://" and matches everything on that scheme, so the OAuth redirect uses
 * a reverse-DNS scheme of its own instead.
 */
class OAuthCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Absent when the user denied, or backed out through the error page.
        OAuthResult.deliver(intent?.data?.getQueryParameter("code"))

        // CLEAR_TOP pops the Custom Tab, which lives in MainActivity's task, and
        // brings Settings back to the front where the result is shown.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
