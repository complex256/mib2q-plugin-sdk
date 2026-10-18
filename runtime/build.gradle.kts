plugins {
    id("mibsdk.car-library")
}

evaluationDependsOn(":compat")
evaluationDependsOn(":api")
evaluationDependsOn(":platform")

dependencies {
    api(project(":api"))
    api(project(":platform"))
}

mibsdk {
    // LinkCheck resolves mibsdk.api.*, mibsdk.platform.* and any Retrotranslator-introduced mibsdk.compat.* references here.
    carClasspath.from(project(":api").tasks.named("carJar"))
    carClasspath.from(project(":compat").tasks.named("carJar"))
    carClasspath.from(project(":platform").tasks.named("carJar"))
}
