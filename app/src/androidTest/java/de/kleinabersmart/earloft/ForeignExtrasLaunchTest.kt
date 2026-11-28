package de.kleinabersmart.earloft

import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Another app starts the exported [MainActivity] with a Parcelable of its own (review5 P39). This class lives in the test APK, so
 * the app's class loader does not know it, like a foreign app's class. From API 33 the extras used to reach the activity's view
 * models unchecked, and the first `hiltViewModel()` crashed the app.
 */
@RunWith(AndroidJUnit4::class)
class ForeignExtrasLaunchTest {

    @Test
    fun startWithAForeignParcelableOpensTheAppWithoutItsExtras() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .putExtra("foreign", Foreign())
            .putExtra("text", "readable")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // The view models are created with the first composition; give it time to run.
            instrumentation.waitForIdleSync()
            Thread.sleep(2_000)
            instrumentation.waitForIdleSync()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { assertNull(it.intent.extras) }
        }
    }

    /** Without a `CREATOR` and unknown to the app: it cannot be read back there. */
    private class Foreign : Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeString("payload")
    }
}
