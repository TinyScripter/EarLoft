package de.kleinabersmart.earloft.core.datasource.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Die Berechtigung, die zum Lesen der lokalen Musik nötig ist. */
object AudioPermission {

    val name: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
}
