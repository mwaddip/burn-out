package app.burnout

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.topjohnwu.superuser.Shell

/**
 * Application entry point. Observes ProcessLifecycleOwner to set sys.burnout.suspend
 * while the app is foreground, clearing it on background — so the SF BurnoutFilter
 * doesn't double-correct the user's tuning canvas (Mark mode test patterns or
 * Calibrate's GL preview).
 *
 * Spec §2.4: "App sets sys.burnout.suspend=1 on ProcessLifecycleOwner foreground
 * (any activity in the app coming to front), clears =0 on background. This means
 * navigating Mark <-> Calibrate within the app stays suspended (no screen flash);
 * leaving the app entirely re-enables."
 *
 * In dev builds (debug-keyed), the app lacks platform signature, so a direct
 * SystemProperties.set hits a sepolicy denial. We shell out via libsu (Magisk root)
 * as a fallback. Phase 6's Soong build + seapp_contexts wiring will let the
 * platform-signed APK call SystemProperties directly without needing root.
 *
 * Failure modes:
 * - No root + no platform signature -> silent log warning, prop stays unset, SF
 *   correction continues (slightly wrong UX in Mark, no harm in Calibrate).
 * - libsu init slow on first call -> first foreground transition may take ~100ms
 *   to flip the prop; subsequent transitions are instant.
 */
class BurnoutApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Tune libsu: short timeout, redirect stderr.
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(5)
        )

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                setSuspendProp("1")
            }

            override fun onStop(owner: LifecycleOwner) {
                setSuspendProp("0")
            }
        })
    }

    private fun setSuspendProp(value: String) {
        try {
            val result = Shell.cmd("setprop sys.burnout.suspend $value").exec()
            if (!result.isSuccess) {
                Log.w(TAG, "setprop sys.burnout.suspend=$value failed: ${result.err}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setprop sys.burnout.suspend=$value threw", t)
        }
    }

    companion object {
        private const val TAG = "BurnoutApp"
    }
}
