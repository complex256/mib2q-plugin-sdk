package spikek

// Throwaway spike: idiomatic Kotlin that must survive backporting to J9 2.3 / Foundation 1.1.
enum class Gear { P, R, N, D }
data class Signal(val name: String, val value: Int)
sealed class Event { data class GearChanged(val g: Gear) : Event(); object Ignition : Event() }
fun String.shout() = uppercase() + "!"

object HelloK {
    var passed = 0; var failed = 0
    fun check(label: String, expected: Any?, body: () -> Any?) {
        try {
            val got = body()
            if (got == expected) { passed++; println("PASS $label") } else { failed++; println("FAIL $label got=$got want=$expected") }
        } catch (t: Throwable) { failed++; println("FAIL $label threw $t") }
    }

    @JvmStatic fun main(args: Array<String>) {
        println("spikek.HelloK on ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        check("lambda+map/filter", 20) { listOf(1, 2, 3, 4).filter { it % 2 == 0 }.map { it * it }.sum() }
        check("data class", "Signal(name=speed, value=88)") { Signal("speed", 88).toString() }
        check("when on sealed", "gear R") {
            val e: Event = Event.GearChanged(Gear.R)
            when (e) { is Event.GearChanged -> "gear ${e.g}"; Event.Ignition -> "ign" }
        }
        check("extension fn", "CAR!") { "car".shout() }
        check("null safety", 0) { val s: String? = null; s?.length ?: 0 }
        check("mutableMap getOrPut", 7L) { val m = mutableMapOf<String, Long>(); m.getOrPut("a") { 5L }; m["a"] = m["a"]!! + 2; m["a"] }
        check("sortedBy + joinToString", "car,media,tuner") { listOf("tuner", "car", "media").sortedBy { it }.joinToString(",") }
        check("thread lambda", 42) { var x = 0; val t = Thread { x = 42 }; t.start(); t.join(); x }
        println("RESULT passed=$passed failed=$failed")
    }
}
