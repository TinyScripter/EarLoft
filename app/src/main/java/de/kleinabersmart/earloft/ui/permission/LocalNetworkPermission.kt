package de.kleinabersmart.earloft.ui.permission

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
 * From Android 17 (API 37) on, an app targeting `targetSdk` 37 may reach devices on the home network (e.g. a Navidrome at
 * `http://192.168.x.x`) only with this runtime permission. Before that it doesn't exist.
 */
object LocalNetworkPermission {
    const val name = "android.permission.ACCESS_LOCAL_NETWORK"

    val isRequired: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun isGranted(context: Context): Boolean =
        !isRequired || ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}

/**
 * Returns a function that requests the home network permission before [block] if needed. [block] runs afterwards in any case
 * (even after a denial) so the connection error is shown as usual.
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
