package keinabersmart.kleinaberlaut.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.datasource.local.AudioPermission
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/**
 * Zeigt [content] erst, wenn die Musik gelesen werden darf. [onGranted] wird bei jedem Wechsel
 * auf „erteilt“ aufgerufen – auch wenn die Berechtigung in den Systemeinstellungen erteilt wurde.
 */
@Composable
fun AudioPermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(AudioPermission.isGranted(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        requested = true
        val audioGranted = result[AudioPermission.name] == true
        granted = audioGranted
        // Nach einer Ablehnung ohne Begründungsdialog zeigt das System keinen Dialog mehr.
        permanentlyDenied = !audioGranted && context.findActivity()?.shouldShowRequestPermissionRationale(AudioPermission.name) == false
    }

    LifecycleResumeEffect(Unit) {
        granted = AudioPermission.isGranted(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(granted) {
        if (granted) onGranted()
    }

    if (granted) {
        content()
    } else {
        PermissionRationale(
            permanentlyDenied = requested && permanentlyDenied,
            onRequest = { launcher.launch(requestedPermissions()) },
            onOpenSettings = { context.openAppSettings() },
        )
    }
}

@Composable
private fun PermissionRationale(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) = Surface(modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_music_note),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(if (permanentlyDenied) R.string.permission_denied_text else R.string.permission_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.permission_open_settings)) }
        } else {
            Button(onClick = onRequest) { Text(stringResource(R.string.permission_grant)) }
        }
    }
}

private fun requestedPermissions(): Array<String> = buildList {
    add(AudioPermission.name)
    // Die Wiedergabe-Benachrichtigung ist davon ausgenommen, Hinweise der App aber nicht.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(showBackground = true)
@Composable
private fun PermissionRationalePreview() {
    KleinaberlautTheme {
        PermissionRationale(permanentlyDenied = false, onRequest = {}, onOpenSettings = {})
    }
}
