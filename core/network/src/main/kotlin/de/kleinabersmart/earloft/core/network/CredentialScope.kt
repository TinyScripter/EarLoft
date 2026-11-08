package de.kleinabersmart.earloft.core.network

import okhttp3.HttpUrl

/**
 * Darf eine Anfrage an diese URL die Zugangsdaten des Servers unter [baseUrl] tragen? Folgt OkHttp einer Weiterleitung,
 * behält die neue Anfrage das Request-Tag mit den Zugangsdaten. Auf einen anderen Host oder Port (z. B. die Anmeldeseite
 * eines vorgeschalteten Proxys) dürfen sie nicht mit, ebenso wenig von HTTPS auf HTTP. HTTP → HTTPS auf demselben Host
 * schon, auch wenn sich dabei der Port ändert.
 */
fun HttpUrl.acceptsCredentialsOf(baseUrl: HttpUrl): Boolean =
    host == baseUrl.host && if (isHttps == baseUrl.isHttps) port == baseUrl.port else isHttps
