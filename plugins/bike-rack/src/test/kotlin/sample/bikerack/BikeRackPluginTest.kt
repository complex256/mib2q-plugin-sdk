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

    private fun start(active: Boolean?) {
        base = File(tmp, "mibsdk")
        File(base, "plugins").mkdirs()
        File(System.getProperty("mibsdk.pluginJar")).copyTo(File(base, "plugins/bike-rack.jar"))
        File(base, "sdk.properties").writeText(
            "plugin.start.delay.ms=0\nheap.low.bytes=0\nlog.file=${File(tmp, "mibsdk.log").path}\n")
        File(base, "grants.properties").writeText("bike-rack=vehicle.read,vehicle.parking.write\n")
        if (active != null) {
            File(base, "data").mkdirs()
            File(base, "data/bike-rack.properties").writeText("active=$active\n")
        }
        rt = SdkRuntime(RuntimeConfig.load(base), platform, now, HookTable(64), RingLog(500)).also {
            it.start()
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

    private fun storedActive(): String? {
        rt!!.stop() // flushes storage
        val p = Properties()
        File(base, "data/bike-rack.properties").inputStream().use { p.load(it) }
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
        assertEquals("false", storedActive())
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
}
