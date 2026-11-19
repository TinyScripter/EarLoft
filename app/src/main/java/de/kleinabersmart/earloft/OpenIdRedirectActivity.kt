package de.kleinabersmart.earloft

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import de.kleinabersmart.earloft.ui.spoken.OpenIdRedirects

/**
 * Receives the redirect `earloft://oauth?code=…&state=…` from browsers without Auth Tab (concept 12.11) and returns to
 * [MainActivity]; `CLEAR_TOP` closes the Custom Tab sitting on top of it. The form waits on
 * [OpenIdRedirects].
 */
class OpenIdRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.dataString?.let(OpenIdRedirects::deliver)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
