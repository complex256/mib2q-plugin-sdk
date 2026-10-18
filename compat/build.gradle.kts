plugins {
    id("mibsdk.car-library")
}

mibsdk {
    // Shims are plain Java 1.4 source: JDK 8 javac -source 1.4 -target 1.4, already class-file v48.
    legacyJava14 = true
}
