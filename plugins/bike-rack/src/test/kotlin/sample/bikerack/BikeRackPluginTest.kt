package sample.bikerack

import java.io.File
import java.util.Properties
import mibsdk.adapter.fake.FakePlatform
import mibsdk.api.Features
import mibsdk.api.SoundDirections
import mibsdk.platform.HookTable
import mibsdk.platform.ParkingIo
import mibsdk.platform.PlatformAdapter
import mibsdk.runtime.Clock
import mibsdk.runtime.PluginState
import mibsdk.runtime.RingLog
import mibsdk.runtime.RuntimeConfig
import mibsdk.runtime.SdkRuntime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BikeRackPluginTest {

    @TempDir
    lateinit var tmp: File

    private val f1r1l1x1 = SoundDirections(true, true, true, true)
    private val f1r0l1x1 = SoundDirections(true, false, true, true)

    private val now = object : Clock {
        @Volatile var ms = 0L
        override fun uptimeMs() = ms
    }
    private val fake = FakePlatform("HMI_TEST", setOf(Features.IGNITION, Features.PARKING, Features.HOOK_PARKING))

    /** The fake (its ECU starts at F1R1L1X1), except that the ECU's parking value can be hidden until "first read". */
    @Volatile private var ecuKnown = true
    private val platform = object : PlatformAdapter by fake {
        override fun parking() = object : ParkingIo {
            override fun current() = if (ecuKnown) fake.parking().current() else null
            override fun apply(value: SoundDirections) = fake.parking().apply(value)
        }
    }

    private var rt: SdkRuntime? = null
    private lateinit var base: File

    @AfterEach
    fun tearDown() {
        rt?.stop()
    }

    private fun start(active: Boolean?, extraProps: String = "", startDelayMs: Long = 0) {
        base = File(tmp, "mibsdk")
        File(base, "plugins").mkdirs()
        File(System.getProperty("mibsdk.pluginJar")).copyTo(File(base, "plugins/bike-rack.jar"))
        File(base, "sdk.properties").writeText(
            "plugin.start.delay.ms=$startDelayMs\nheap.low.bytes=0\nlog.file=${File(tmp, "mibsdk.log").path}\n$extraProps")
        File(base, "grants.properties").writeText("bike-rack=vehicle.read,vehicle.parking.write\n")
        if (active != null) {
            File(FakePlatform.roots(tmp).persist(), "data").mkdirs()
            File(FakePlatform.roots(tmp).persist(), "data/bike-rack.properties").writeText("active=$active\n")
        }
        rt = SdkRuntime(RuntimeConfig.load(FakePlatform.roots(tmp)), platform, now, HookTable(64), RingLog(500)).also {
            it.start()
            if (startDelayMs > 0) return@also
            assertEquals(PluginState.ENABLED, it.statuses().single().state(), it.log().snapshot().toString())
        }
    }

    private fun awaitApplied(size: Int, last: SoundDirections) {
        for (i in 0 until 500) {
            if (fake.appliedLog().size >= size) break
            Thread.sleep(10)
        }
        assertEquals(size, fake.appliedLog().size, "${fake.appliedLog()}\n${rt?.log()?.snapshot()}")
        assertEquals(last, fake.appliedLog()[size - 1])
    }

    private fun assertNoMoreThan(size: Int) {
        Thread.sleep(200)
        assertEquals(size, fake.appliedLog().size, fake.appliedLog().toString())
    }

    private fun awaitWindowClosed(count: Int) {
        for (i in 0 until 500) {
            if (windowClosedCount() >= count) break
            Thread.sleep(10)
        }
        assertEquals(count, windowClosedCount(), rt?.log()?.snapshot().toString())
    }

    private fun windowClosedCount() = rt!!.log().snapshot().count { it.toString().contains("re-arm window closed") }

    private fun storedActive(): String? {
        rt!!.stop() // flushes storage
        val p = Properties()
        File(FakePlatform.roots(tmp).persist(), "data/bike-rack.properties").inputStream().use { p.load(it) }
        return p.getProperty("active")
    }

    @Test
    fun activeOnEnableMutesRearOnly() {
        start(true)
        awaitApplied(1, f1r0l1x1)
    }

    @Test
    fun inactiveDoesNothing() {
        start(false)
        assertNoMoreThan(0)
        assertTrue(rt!!.activeOverrides().isEmpty())
    }

    @Test
    fun ignitionOffRestoresAndClearsToggle() {
        start(true)
        awaitApplied(1, f1r0l1x1)
        fake.setIgnition(false)
        awaitApplied(2, f1r1l1x1)
        assertEquals(null, storedActive())
    }

    @Test
    fun waitsForFirstValueWhenUnknown() {
        ecuKnown = false
        start(true)
        assertNoMoreThan(0)
        ecuKnown = true
        fake.ecuSetSoundDirections(f1r1l1x1)
        awaitApplied(1, f1r0l1x1)
    }

    @Test
    fun ignitionOffWhileWaitingNeverMutes() {
        ecuKnown = false
        start(true)
        fake.setIgnition(false)
        ecuKnown = true
        fake.ecuSetSoundDirections(f1r1l1x1)
        assertNoMoreThan(0)
        assertTrue(rt!!.activeOverrides().isEmpty())
        assertEquals(null, storedActive())
    }

    @Test
    fun ecuFlipsBackGetsReapplied() {
        start(true)
        awaitApplied(1, f1r0l1x1)
        fake.ecuSetSoundDirections(f1r1l1x1)
        now.ms += 1000
        awaitApplied(2, f1r0l1x1)
    }

    @Test
    fun disablingPluginRestores() {
        start(true)
        awaitApplied(1, f1r0l1x1)
        rt!!.stop()
        awaitApplied(2, f1r1l1x1)
    }

    @Test
    fun ignitionOffWhileWaitingAtTheStorageCapNeverMutes() {
        ecuKnown = false
        // entryBytes("active","true") estimates 12; "false" would be 13 and throw
        start(true, "storage.plugin.max.bytes=12\n")
        fake.setIgnition(false)
        ecuKnown = true
        fake.ecuSetSoundDirections(f1r1l1x1)
        assertNoMoreThan(0)
        assertTrue(rt!!.activeOverrides().isEmpty())
        assertEquals(null, storedActive())
    }

    @Test
    fun armedWhileParkedMutesAgainAtIgnitionOn() {
        start(true, startDelayMs = 30000)
        fake.setIgnition(false) // KL15 is off before the plugin loads (enable or reload while parked)
        now.ms += 30000
        awaitApplied(1, f1r0l1x1) // muted while off: the ledger ends that override at ignition on
        fake.setIgnition(true)
        // the ledger ends the off-mute; the armed rack takes the mute again for this drive (the restore write
        // may or may not happen first, depending on which runs first)
        awaitHeld { it.size == 1 && !it[0].restorePending() && fake.ecuSoundDirections() == f1r0l1x1 }
        assertNoMoreThan(fake.appliedLog().size)
        assertEquals(f1r0l1x1, fake.ecuSoundDirections())
        assertEquals("bike-rack", rt!!.activeOverrides().single().pluginId())

        fake.setIgnition(false) // the drive ends: the rear tones come back
        awaitHeld { it.isEmpty() && fake.ecuSoundDirections() == f1r1l1x1 }
        assertNoMoreThan(fake.appliedLog().size)
        assertEquals(f1r1l1x1, fake.ecuSoundDirections())
        assertEquals(null, storedActive())
    }

    private fun awaitHeld(done: (List<mibsdk.runtime.ActiveOverride>) -> Boolean) {
        for (i in 0 until 500) {
            if (done(rt!!.activeOverrides())) return
            Thread.sleep(10)
        }
        throw AssertionError("${rt!!.activeOverrides()} ecu=${fake.ecuSoundDirections()} " +
            "applied=${fake.appliedLog()}\n${rt!!.log().snapshot()}")
    }

    @Test
    fun ignitionBlipWithinFiveSecondsReArmsAndMutesAgain() {
        start(true)
        awaitApplied(1, f1r0l1x1)
        fake.setIgnition(false)
        awaitApplied(2, f1r1l1x1)
        now.ms += 3000
        fake.setIgnition(true)
        awaitApplied(3, f1r0l1x1)
        assertEquals("true", storedActive())
    }

    @Test
    fun ignitionOnAfterTheWindowStaysDisarmed() {
        start(true)
        awaitApplied(1, f1r0l1x1)
        fake.setIgnition(false)
        awaitApplied(2, f1r1l1x1)
        now.ms += 5000
        awaitWindowClosed(1)
        fake.setIgnition(true)
        assertNoMoreThan(2)
        assertEquals(null, storedActive())
    }

    @Test
    fun bouncingIgnitionOffEndsDisarmedAndUnmuted() {
        start(true)
        awaitApplied(1, f1r0l1x1)
        fake.setIgnition(false)
        awaitApplied(2, f1r1l1x1)
        now.ms += 1000
        fake.setIgnition(true)
        awaitApplied(3, f1r0l1x1)
        fake.setIgnition(false)
        awaitApplied(4, f1r1l1x1)
        now.ms += 1000
        fake.setIgnition(false)
        now.ms += 5000
        awaitWindowClosed(1)
        fake.setIgnition(true)
        assertNoMoreThan(4)
        assertEquals(null, storedActive())
    }
}
