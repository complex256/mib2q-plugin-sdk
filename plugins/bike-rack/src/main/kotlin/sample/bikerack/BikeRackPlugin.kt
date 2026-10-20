package sample.bikerack

import mibsdk.api.IgnitionChanged
import mibsdk.api.MibPlugin
import mibsdk.api.OverrideConflictException
import mibsdk.api.OverrideScope
import mibsdk.api.ParkingSoundChanged
import mibsdk.api.PluginContext
import mibsdk.api.SoundDirections
import mibsdk.api.Storage
import mibsdk.api.Subscription
import mibsdk.api.VehicleOverride

/**
 * Silences the rear parking-sensor tones while a bike rack is fitted. The toggle is storage key `active`.
 * The runtime's override ledger restores the prior value (on ignition off, disable or quarantine), so this
 * plugin never writes the old value back itself.
 *
 * The arm state lives in memory ([armed], read from storage once in `onEnable`); every mute decision reads the
 * flag, never storage. Ignition off disarms in memory first, then removes the key. It removes it rather than
 * writing "false": a removal always works, while "false" is one byte longer than "true" and, at the storage cap
 * (the estimate includes a fixed per-entry header), the put would throw and leave the rack armed.
 * A KL15 blip re-arms the rack only within [REARM_WINDOW_MS] of the ignition off, measured on the runtime Clock.
 *
 * Residual: the disarm reaches disk within the storage debounce (2 s). A power cut inside that window re-mutes
 * on the next boot, where the runtime's override indicator shows it.
 */
class BikeRackPlugin : MibPlugin() {

    @Volatile private var armed = false
    @Volatile private var waiting: Subscription? = null
    @Volatile private var held: VehicleOverride? = null
    @Volatile private var window: Subscription? = null
    @Volatile private var windowToken: Any? = null

    override fun onEnable(ctx: PluginContext) {
        val storage = ctx.storage()
        armed = storage.get(ACTIVE, "false") == "true"
        ctx.events().on(IgnitionChanged::class.java) { e ->
            if (!e.isOn) ignitionOff(ctx, storage) else ignitionOn(ctx, storage)
        }
        if (armed) muteWhenKnown(ctx)
    }

    private fun ignitionOff(ctx: PluginContext, storage: Storage) {
        if (!armed) return
        armed = false
        waiting?.cancel()
        waiting = null
        held?.release()
        held = null
        try {
            storage.remove(ACTIVE)
        } catch (e: Exception) {
            ctx.log().warn("could not save the disarm: ${e.message}; the rack stays disarmed for this run")
        }
        val token = Any()
        windowToken = token
        window = ctx.scheduler().after(REARM_WINDOW_MS) {
            if (windowToken === token) {
                windowToken = null
                window = null
                ctx.log().info("re-arm window closed")
            }
        }
    }

    private fun ignitionOn(ctx: PluginContext, storage: Storage) {
        if (armed || windowToken == null) return
        windowToken = null
        window?.cancel()
        window = null
        try {
            storage.put(ACTIVE, "true")
        } catch (e: Exception) {
            ctx.log().warn("could not re-arm: ${e.message}; staying disarmed")
            return
        }
        armed = true
        muteWhenKnown(ctx)
    }

    private fun muteWhenKnown(ctx: PluginContext) {
        val current = ctx.vehicle().parking().soundDirections()
        if (current != null) {
            mute(ctx, current)
        } else {
            ctx.log().info("waiting for parking value")
            waiting = ctx.events().on(ParkingSoundChanged::class.java) { e ->
                waiting?.cancel()
                waiting = null
                if (armed) mute(ctx, e.value())
            }
        }
    }

    private fun mute(ctx: PluginContext, current: SoundDirections) {
        try {
            held = ctx.vehicle().parking().overrideSoundDirections(current.withRear(false), OverrideScope.UNTIL_IGNITION_OFF)
            ctx.log().info("rear parking tones muted")
        } catch (e: OverrideConflictException) {
            ctx.log().warn("parking sounds already overridden: ${e.message}")
        }
    }

    private companion object {
        const val ACTIVE = "active"
        const val REARM_WINDOW_MS = 5000L
    }
}
