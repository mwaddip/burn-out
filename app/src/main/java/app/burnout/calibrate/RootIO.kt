package app.burnout.calibrate

import android.util.Log
import java.io.File
import java.util.Locale

object RootIO {
    private const val TAG = "RootIO"
    private const val DATA_DIR = "/data/burnout"
    private const val MASK_NAME = "mask.png"
    private const val ZONES_NAME = "zones.json"

    enum class Outcome { OK, DENIED, FAILED }

    data class Result(val outcome: Outcome, val exitCode: Int, val stderr: String)

    fun buildCommitCommand(stagingDir: String, reloadEpoch: Long): String {
        val maskSrc  = "$stagingDir/$MASK_NAME"
        val zonesSrc = "$stagingDir/$ZONES_NAME"
        val maskDst  = "$DATA_DIR/$MASK_NAME"
        val zonesDst = "$DATA_DIR/$ZONES_NAME"
        // Dir chcon is idempotent vs. the boot-time surfaceflinger.rc mkdir; on a real burn-out ROM
        // the dir already has the burnout_data_file label and this is a no-op. On the dev path
        // (app-side fallback mkdir from inherited /data label), this fixes the dir's label so
        // SF's `dir search` rule applies.
        return "mkdir -p $DATA_DIR && " +
                "chcon u:object_r:burnout_data_file:s0 $DATA_DIR && " +
                "cp $maskSrc $maskDst && " +
                "cp $zonesSrc $zonesDst && " +
                "chmod 644 $maskDst $zonesDst && " +
                "chcon u:object_r:burnout_data_file:s0 $maskDst $zonesDst && " +
                "setprop sys.burnout.reload $reloadEpoch"
    }

    fun classify(exitCode: Int, stderr: String): Outcome = when (exitCode) {
        0 -> Outcome.OK
        13 -> Outcome.DENIED
        else -> Outcome.FAILED
    }

    fun commitToDataBurnout(stagingDir: File, maskBytes: ByteArray, zonesJson: String): Result {
        if (!stagingDir.exists()) stagingDir.mkdirs()
        File(stagingDir, MASK_NAME).writeBytes(maskBytes)
        File(stagingDir, ZONES_NAME).writeText(zonesJson, Charsets.UTF_8)

        val epoch = System.currentTimeMillis() / 1000L

        // Try direct file IO first — works on platform-signed ROM-bundled APK where
        // sharedUserId="android.uid.system" lets the app write to /data/burnout/ (DAC),
        // and burnout_app SELinux domain permits create_file_perms (MAC).
        val direct = tryCommitDirect(maskBytes, zonesJson, epoch)
        if (direct.outcome == Outcome.OK) return direct

        // Fallback: chained su shell. Magisk grants root after first user-prompt;
        // subsequent calls are silent.
        val cmd = buildCommitCommand(stagingDir.absolutePath, epoch)
        Log.i(TAG, "su -c \"%s\"".format(Locale.US, cmd))
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val stderr = proc.errorStream.bufferedReader().readText()
        val exit = proc.waitFor()
        val outcome = classify(exit, stderr)
        if (outcome != Outcome.OK) Log.e(TAG, "commit failed exit=$exit stderr=$stderr")
        return Result(outcome, exit, stderr)
    }

    private fun tryCommitDirect(maskBytes: ByteArray, zonesJson: String, reloadEpoch: Long): Result {
        return try {
            val dir = File(DATA_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                return Result(Outcome.FAILED, -1, "mkdir $DATA_DIR failed")
            }
            File(dir, MASK_NAME).writeBytes(maskBytes)
            File(dir, ZONES_NAME).writeText(zonesJson, Charsets.UTF_8)
            // SystemProperties.set via reflection — see BurnoutApp.trySetPropDirect for the same pattern.
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("set", String::class.java, String::class.java)
            m.invoke(null, "sys.burnout.reload", reloadEpoch.toString())
            Log.i(TAG, "commit direct: wrote /data/burnout/{mask.png,zones.json}, set reload=$reloadEpoch")
            Result(Outcome.OK, 0, "")
        } catch (t: Throwable) {
            // Expected on Gradle debug builds: SecurityException or IOException("Permission denied").
            Log.d(TAG, "direct commit failed: ${t.javaClass.simpleName}: ${t.message}")
            Result(Outcome.FAILED, -1, t.message ?: t.javaClass.simpleName)
        }
    }
}
