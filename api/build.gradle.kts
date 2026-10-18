plugins {
    id("mibsdk.car-library")
}

evaluationDependsOn(":compat")

mibsdk {
    // Retrotranslator's -backport mapping can introduce mibsdk.compat.* references; LinkCheck resolves them here.
    carClasspath.from(project(":compat").tasks.named("carJar"))
}
