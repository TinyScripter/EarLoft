package de.kleinabersmart.earloft.ui.permission

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

/**
 * Zeigt [content] erst, wenn die Musik gelesen werden darf. [onGranted] wird bei jedem Wechsel
 * auf „erteilt“ aufgerufen – auch wenn die Berechtigung in den Systemeinstellungen erteilt wurde.
 * [onSkip]: ohne lokale Musik weiter (schaltet sie in den Einstellungen ab). Mit [enabled] `false`
 * (lokale Musik aus) erscheint [content] immer – an derselben Stelle, damit Navigation und Zustand
 * beim Umschalten erhalten bleiben.
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

    // Nur beim Wechsel: Jede neue Activity (Drehung, Hell/Dunkel, Sprache) liefe sonst einen vollen Scan der Musik.
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
            onRequest = { launcher.launch(requestedPermissions()) },
            onOpenSettings = { context.openAppSettings() },
            onSkip = onSkip,
        )
    }
}

/**
 * Fragt die Musik-Berechtigung außerhalb des Gates an (z. B. beim Einschalten der lokalen Musik).
 * [onResult] erhält `true`, wenn sie erteilt ist. Zeigt das System keinen Dialog mehr, öffnet sich
 * stattdessen die App-Seite der Systemeinstellungen.
 */
@Composable
fun rememberAudioPermissionRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && context.findActivity()?.shouldShowRequestPermissionRationale(AudioPermission.name) == false) {
            context.openAppSettings()
        }
        onResult(granted)
    }
    return {
        if (AudioPermission.isGranted(context)) onResult(true) else launcher.launch(AudioPermission.name)
    }
}

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
    // Die Wiedergabe-Benachrichtigung ist davon ausgenommen, Hinweise der App aber nicht.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    // Damit schon vorhandene und neu eingerichtete Heimnetz-Server gleich erreichbar sind.
    if (LocalNetworkPermission.isRequired) add(LocalNetworkPermission.name)
}.toTypedArray()

private fun Context.openAppSettings() {
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
