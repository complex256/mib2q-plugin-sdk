plugins {
    id("mibsdk.car-library")
}

evaluationDependsOn(":compat")
evaluationDependsOn(":api")

dependencies {
    api(project(":api"))
}

mibsdk {
    // LinkCheck resolves mibsdk.api.* and any Retrotranslator-introduced mibsdk.compat.* references here.
    carClasspath.from(project(":api").tasks.named("carJar"))
    carClasspath.from(project(":compat").tasks.named("carJar"))
}
