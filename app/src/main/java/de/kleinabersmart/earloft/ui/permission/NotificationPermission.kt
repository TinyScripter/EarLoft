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

/** The app's notices (new content, downloads) need a permission from Android 13 on; the playback notification doesn't. */
object NotificationPermission {
    val isRequired: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isGranted(context: Context): Boolean = !isRequired ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * Requests notifications (setup wizard, concept 15). [onResult] receives `true` if they are allowed.
 * If the system no longer shows a dialog, the app's page in the system settings opens instead.
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
