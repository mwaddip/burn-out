package app.burnout

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Application entry point. Observes ProcessLifecycleOwner to set sys.burnout.suspend
 * while the app is foreground, clearing it on background — so the SF BurnoutFilter
 * doesn't double-correct the user's tuning canvas (Mark mode test patterns or
 * Calibrate's GL preview).
 *
 * Spec §2.4: "App sets sys.burnout.suspend=1 on ProcessLifecycleOwner foreground
 * (any activity in the app coming to front), clears =0 on background."
 *
 * In dev builds (debug-keyed), the app lacks platform signature, so a direct
 * SystemProperties.set hits a sepolicy denial (untrusted_app domain can't write
 * to property_service). We shell out via `su -c` (Magisk root). Magisk prompts
 * the user the first time; subsequent calls are silent.
 *
 * Phase 6's Soong build + seapp_contexts wiring will let the platform-signed APK
 * call SystemProperties directly without needing root.
 *
 * Failure modes:
 * - No Magisk root (su exits non-zero) -> silent log warning, prop stays unset,
 *   SF correction continues. Mark UX gets the live correction filter applied —
 *   user sees the corrected pattern instead of the raw test pattern, but the
 *   overall flow still works.
 */
class BurnoutApp : Application() {

    override fun onCreate() {
        super.onCreate()

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
        Thread {
            // Try direct SystemProperties.set first — works on platform-signed ROM-bundled
            // builds where seapp_contexts routes the app into burnout_app domain with
            // set_prop(burnout_prop). Throws on Gradle debug builds where the app runs
            // in untrusted_app and SELinux denies the property write.
            if (trySetPropDirect("sys.burnout.suspend", value)) {
                Log.i(TAG, "sys.burnout.suspend=$value (direct)")
                return@Thread
            }
            // Fallback: su shell. Magisk grants root after the user-prompt the first time;
            // subsequent calls are silent.
            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "setprop sys.burnout.suspend $value")
                )
                val exitCode = proc.waitFor()
                if (exitCode != 0) {
                    val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                    Log.w(TAG, "su setprop sys.burnout.suspend=$value exit=$exitCode err=$stderr")
                } else {
                    Log.i(TAG, "sys.burnout.suspend=$value (su fallback)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "su setprop sys.burnout.suspend=$value threw", t)
            }
        }.start()
    }

    private fun trySetPropDirect(name: String, value: String): Boolean {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("set", String::class.java, String::class.java)
            m.invoke(null, name, value)
            true
        } catch (t: Throwable) {
            // Expected on Gradle debug builds (sepolicy denial wraps as RuntimeException).
            Log.d(TAG, "direct set $name=$value failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "BurnoutApp"
    }
}
