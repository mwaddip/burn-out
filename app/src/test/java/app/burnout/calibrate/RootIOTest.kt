package app.burnout.calibrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootIOTest {

    @Test
    fun buildCommitCommand_chainsAllSteps() {
        val cmd = RootIO.buildCommitCommand(
            stagingDir = "/data/data/app.burnout/files/staging",
            reloadEpoch = 1719876543L,
        )
        assertTrue("must mkdir target", cmd.contains("mkdir -p /data/burnout"))
        assertTrue("must chcon dir", cmd.contains("chcon u:object_r:burnout_data_file:s0 /data/burnout &&"))
        assertTrue("must cp mask", cmd.contains("cp /data/data/app.burnout/files/staging/mask.png /data/burnout/mask.png"))
        assertTrue("must cp zones", cmd.contains("cp /data/data/app.burnout/files/staging/zones.json /data/burnout/zones.json"))
        assertTrue("must chmod 644", cmd.contains("chmod 644 /data/burnout/mask.png /data/burnout/zones.json"))
        assertTrue("must chcon files", cmd.contains("chcon u:object_r:burnout_data_file:s0 /data/burnout/mask.png /data/burnout/zones.json"))
        assertTrue("must setprop reload", cmd.contains("setprop sys.burnout.reload 1719876543"))
        // Steps must be chained with &&, no ; — fail-fast on any step.
        // 6 && separators after adding the dir chcon: mkdir → chcon-dir → cp → cp → chmod → chcon-files → setprop.
        assertEquals(
            "6 chained && separators expected",
            6,
            "&&".toRegex().findAll(cmd).count()
        )
    }

    @Test
    fun parseExitCode_classifies_denied() {
        assertEquals(RootIO.Outcome.DENIED, RootIO.classify(13, ""))
    }

    @Test
    fun parseExitCode_classifies_failure() {
        assertEquals(RootIO.Outcome.FAILED, RootIO.classify(1, "cp: cannot stat 'mask.png': No such file"))
    }

    @Test
    fun parseExitCode_classifies_success() {
        assertEquals(RootIO.Outcome.OK, RootIO.classify(0, ""))
    }
}
