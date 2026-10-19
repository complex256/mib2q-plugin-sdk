package sample.bikerack

import mibsdk.api.IgnitionChanged
import mibsdk.api.MibPlugin
import mibsdk.api.OverrideConflictException
import mibsdk.api.OverrideScope
import mibsdk.api.ParkingSoundChanged
import mibsdk.api.PluginContext
import mibsdk.api.SoundDirections
import mibsdk.api.Subscription

/**
 * Silences the rear parking-sensor tones while a bike rack is fitted. The toggle is storage key `active`.
 * The runtime's override ledger restores the prior value (on ignition off, disable or quarantine), so this
 * plugin never writes the old value back itself.
 */
class BikeRackPlugin : MibPlugin() {

    private var waiting: Subscription? = null

    override fun onEnable(ctx: PluginContext) {
        val storage = ctx.storage()
        // Ignition off: the ledger restores the sound; the rack must be re-armed deliberately next time.
        ctx.events().on(IgnitionChanged::class.java) { e ->
            if (!e.isOn) storage.put(ACTIVE, "false")
        }
        if (storage.get(ACTIVE, "false") != "true") return

        val current = ctx.vehicle().parking().soundDirections()
        if (current != null) {
            mute(ctx, current)
        } else {
            ctx.log().info("waiting for parking value")
            waiting = ctx.events().on(ParkingSoundChanged::class.java) { e ->
                waiting?.cancel()
                waiting = null
                mute(ctx, e.value())
            }
        }
    }

    private fun mute(ctx: PluginContext, current: SoundDirections) {
        try {
            ctx.vehicle().parking().overrideSoundDirections(current.withRear(false), OverrideScope.UNTIL_IGNITION_OFF)
            ctx.log().info("rear parking tones muted")
        } catch (e: OverrideConflictException) {
            ctx.log().warn("parking sounds already overridden: ${e.message}")
        }
    }

    private companion object {
        const val ACTIVE = "active"
    }
}
