package de.kleinabersmart.kleinaberlaut.ui.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Ab Android 17 (API 37) darf eine App, die dafür `targetSdk` 37 hat, Geräte im Heimnetz (z. B. einen Navidrome unter
 * `http://192.168.x.x`) nur mit dieser Laufzeitberechtigung erreichen. Davor gibt es sie nicht.
 */
object LocalNetworkPermission {
    const val name = "android.permission.ACCESS_LOCAL_NETWORK"

    val isRequired: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun isGranted(context: Context): Boolean =
        !isRequired || ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}

/**
 * Liefert eine Funktion, die vor [block] bei Bedarf die Heimnetz-Berechtigung anfragt. [block] läuft danach in jedem Fall
 * (auch nach einer Ablehnung), damit der Verbindungsfehler wie gewohnt angezeigt wird.
 */
@Composable
fun rememberLocalNetworkRequester(block: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentBlock by rememberUpdatedState(block)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { currentBlock() }
    return {
        if (LocalNetworkPermission.isGranted(context)) currentBlock() else launcher.launch(LocalNetworkPermission.name)
    }
}
