pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "mibsdk"

include("compat")
include("api")
include("platform")
include("runtime")
include("adapter-fake")
include("adapter-hqc")
include("dist")
include("plugins:hello", "plugins:crashy", "plugins:bike-rack", "plugins:hello-menu", "plugins:daemon-status",
    "plugins:inputs-demo")
include("spikes:hmi-ui")
