rootProject.name = "gltest"

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven("https://maven.luna5ama.dev")
    }

    val kmogusVersion: String by settings

    plugins {
        id("dev.luna5ama.jar-optimizer") version "1.2-SNAPSHOT"
        id("dev.luna5ama.kmogus-struct-codegen") version kmogusVersion
    }
}

includeBuild("../gl-wrapper") {
    dependencySubstitution {
        substitute(module("dev.luna5ama:gl-wrapper-core")).using(project(":shared"))
        substitute(module("dev.luna5ama:gl-wrapper-lwjgl-3")).using(project(":lwjgl-3"))
    }
}