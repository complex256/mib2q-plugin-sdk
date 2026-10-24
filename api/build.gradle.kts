plugins {
    id("mibsdk.car-library")
}

evaluationDependsOn(":compat")

mibsdk {
    // Retrotranslator's -backport mapping can introduce mibsdk.compat.* references; LinkCheck resolves them here.
    carClasspath.from(project(":compat").tasks.named("carJar"))
}

// PermissionCatalogTest reads permissions.json from the project dir: declare it so editing only the json reruns it.
tasks.named<Test>("test") {
    inputs.file("permissions.json").withPropertyName("permissionCatalog")
}
