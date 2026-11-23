package de.kleinabersmart.earloft.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.material3.TextButton
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.datasource.local.AudioPermission
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.designsystem.R as DesignR
import de.kleinabersmart.earloft.ui.components.startOrHint

/**
 * Shows [content] only once music may be read. [onGranted] is called on every change
 * to "granted" – also when the permission was granted in the system settings.
 * [onSkip]: continue without local music (disables it in the settings). With [enabled] `false`
 * (local music off) [content] always appears – in the same place, so navigation and state
 * are kept when toggling.
 */
@Composable
fun AudioPermissionGate(
    enabled: Boolean,
    onGranted: () -> Unit,
    onSkip: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(AudioPermission.isGranted(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val request = remember { RequestState() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        requested = true
        val audioGranted = result[AudioPermission.name] == true
        granted = audioGranted
        // The system shows no dialog anymore only if it answered at once, as in [rememberPermissionRequester]: a dialog closed
        // with Back or beside it also leaves the rationale flag `false` (review2 J63).
        val rationaleAfter = context.findActivity()?.shouldShowRequestPermissionRationale(AudioPermission.name)
        val answeredAtOnce = SystemClock.elapsedRealtime() - request.launchedAt < NO_DIALOG_MS
        permanentlyDenied = !audioGranted && !request.rationaleBefore && rationaleAfter == false && answeredAtOnce
    }

    LifecycleResumeEffect(Unit) {
        granted = AudioPermission.isGranted(context)
        onPauseOrDispose { }
    }

    // Only on change: otherwise every new activity (rotation, light/dark, language) would run a full music scan.
    var wasGranted by rememberSaveable { mutableStateOf(granted) }
    LaunchedEffect(granted) {
        if (granted && !wasGranted) onGranted()
        wasGranted = granted
    }

    if (granted || !enabled) {
        content()
    } else {
        PermissionRationale(
            permanentlyDenied = requested && permanentlyDenied,
            onRequest = {
                request.rationaleBefore = context.findActivity()?.shouldShowRequestPermissionRationale(AudioPermission.name) == true
                request.launchedAt = SystemClock.elapsedRealtime()
                launcher.launch(requestedPermissions())
            },
            onOpenSettings = { context.openAppSettings() },
            onSkip = onSkip,
        )
    }
}

/**
 * Requests the music permission outside the gate (e.g. when enabling local music).
 * [onResult] receives `true` if it is granted. If the system no longer shows a dialog, the app's
 * page in the system settings opens instead.
 */
@Composable
fun rememberAudioPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    return rememberPermissionRequester(AudioPermission.name, { AudioPermission.isGranted(context) }, onResult)
}

/**
 * Requests [permission]; if the system shows no dialog any more (permanently denied), the app's page in the system settings
 * opens instead. That is the case only if the rationale flag was `false` before and after and the answer came at once: a
 * dialog closed with Back or by tapping beside it, and a second, deliberate "Don't allow", also end with `false` and
 * must not jump to the settings (review G21).
 */
@Composable
internal fun rememberPermissionRequester(permission: String, isGranted: () -> Boolean, onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val request = remember { RequestState() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val rationaleAfter = context.findActivity()?.shouldShowRequestPermissionRationale(permission)
        val answeredAtOnce = SystemClock.elapsedRealtime() - request.launchedAt < NO_DIALOG_MS
        if (!granted && !request.rationaleBefore && rationaleAfter == false && answeredAtOnce) context.openAppSettings()
        onResult(granted)
    }
    return {
        if (isGranted()) {
            onResult(true)
        } else {
            request.rationaleBefore = context.findActivity()?.shouldShowRequestPermissionRationale(permission) == true
            request.launchedAt = SystemClock.elapsedRealtime()
            launcher.launch(permission)
        }
    }
}

private class RequestState {
    var rationaleBefore = false
    var launchedAt = 0L
}

/** Without a dialog the system answers within a few frames; a person needs longer. */
private const val NO_DIALOG_MS = 500L

@Composable
private fun PermissionRationale(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
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
        TextButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.permission_skip))
        }
    }
}

private fun requestedPermissions(): Array<String> = buildList {
    add(AudioPermission.name)
    // The playback notification is exempt from this, but the app's notices aren't.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    // So that existing and newly set up home network servers are reachable right away.
    if (LocalNetworkPermission.isRequired) add(LocalNetworkPermission.name)
}.toTypedArray()

internal fun Context.openAppSettings() = startOrHint(this) {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(showBackground = true)
@Composable
private fun PermissionRationalePreview() {
    EarLoftTheme {
        PermissionRationale(permanentlyDenied = false, onRequest = {}, onOpenSettings = {}, onSkip = {})
    }
}
