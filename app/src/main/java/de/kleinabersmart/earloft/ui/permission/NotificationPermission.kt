package de.kleinabersmart.earloft.ui.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Hinweise der App (Neues, Downloads) brauchen ab Android 13 eine Berechtigung; die Wiedergabe-Benachrichtigung nicht. */
object NotificationPermission {
    val isRequired: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isGranted(context: Context): Boolean = !isRequired ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * Fragt die Benachrichtigungen an (Einrichtungsassistent, Konzept 15). [onResult] erhält `true`, wenn sie erlaubt sind.
 * Zeigt das System keinen Dialog mehr, öffnet sich stattdessen die App-Seite der Systemeinstellungen.
 */
@Composable
fun rememberNotificationPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false) {
            context.openAppSettings()
        }
        onResult(granted)
    }
    return {
        if (NotificationPermission.isGranted(context)) onResult(true) else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
