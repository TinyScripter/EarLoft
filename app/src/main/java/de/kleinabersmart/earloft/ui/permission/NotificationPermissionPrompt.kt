package de.kleinabersmart.earloft.ui.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fragt beim ersten Start einmal nach den Benachrichtigungen (ab Android 13), nur mit dem Dialog des Systems. Der Merker
 * liegt in `noBackupFilesDir`, ein neues Gerät fragt also wieder. Wer schon einmal abgelehnt hat (Installationen von
 * vor dem Merker), wird nicht erneut gefragt.
 */
@Composable
fun AskForNotificationsOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val firstTime = withContext(Dispatchers.IO) { File(context.noBackupFilesDir, MARKER).createNewFile() }
        if (!firstTime) return@LaunchedEffect
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val refusedBefore = context.findActivity()?.shouldShowRequestPermissionRationale(permission) == true
        if (!granted && !refusedBefore) launcher.launch(permission)
    }
}

private const val MARKER = "notifications_asked"
