package keinabersmart.kleinaberlaut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.ui.KleinaberlautApp
import keinabersmart.kleinaberlaut.ui.permission.AudioPermissionGate
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var libraryRefresher: LibraryRefresher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KleinaberlautTheme {
                AudioPermissionGate(onGranted = libraryRefresher::refreshNow) {
                    KleinaberlautApp()
                }
            }
        }
    }
}
