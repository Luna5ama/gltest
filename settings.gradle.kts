rootProject.name = "gltest"

pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.luna5ama.dev")
        gradlePluginPortal()
    }

    val kmogusVersion: String by settings

    plugins {
        id("dev.luna5ama.jar-optimizer") version "1.2-SNAPSHOT"
        id("dev.luna5ama.kmogus-struct-codegen") version kmogusVersion
    }
}

includeBuild("../gl-wrapper") {
    dependencySubstitution {
        substitute(module("dev.luna5ama:gl-wrapper-base")).using(project(":base"))
        substitute(module("dev.luna5ama:gl-wrapper-core")).using(project(":shared"))
        substitute(module("dev.luna5ama:gl-wrapper-lwjgl-3")).using(project(":lwjgl-3"))
    }
}

includeBuild("../glc2vk") {
    dependencySubstitution {
//        substitute(module("dev.luna5ama:glc2vk-common")).using(project(":common"))
        substitute(module("dev.luna5ama:glc2vk-capture")).using(project(":capture"))
        substitute(module("dev.luna5ama:glc2vk-replay")).using(project(":replay"))
    }
}