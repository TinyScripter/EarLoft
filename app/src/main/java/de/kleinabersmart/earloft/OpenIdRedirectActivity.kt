package de.kleinabersmart.earloft

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import de.kleinabersmart.earloft.ui.spoken.OpenIdRedirects

/**
 * Nimmt die Rückleitung `earloft://oauth?code=…&state=…` von Browsern ohne Auth Tab an (Konzept 12.11) und kehrt zur
 * [MainActivity] zurück; `CLEAR_TOP` schließt dabei den Custom Tab, der über ihr liegt. Das Formular wartet auf
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
