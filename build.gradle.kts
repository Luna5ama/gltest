import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.2.0-Beta1"

    id("dev.luna5ama.jar-optimizer")
    id("dev.luna5ama.ktgen").version("1.0.0") apply false
    id("dev.luna5ama.kmogus-struct-codegen")
}

allprojects {
    group = "org.example"
    version = "1.0-SNAPSHOT"

    apply {
        plugin("kotlin")
    }

    repositories {
        mavenLocal()
        maven("https://maven.luna5ama.dev")
        mavenCentral()
    }
}

dependencies {
    val kmogusVersion: String by project
    testImplementation(kotlin("test"))

    implementation(platform("org.lwjgl:lwjgl-bom:3.3.6"))

    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-opengl")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-opengl", classifier = "natives-windows")

    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    implementation("org.joml", "joml", "1.10.5")

    implementation("it.unimi.dsi:fastutil:8.5.12")
    implementation("it.unimi.dsi:dsiutils:2.7.3")
    implementation("dev.fastmc:fastmc-common:1.1-SNAPSHOT")

    implementation("dev.luna5ama:gl-wrapper-lwjgl-3:1.1.0")
    implementation("dev.luna5ama:gl-wrapper-core:1.1.0")

    implementation("dev.luna5ama:kmogus-core:$kmogusVersion")
    implementation("dev.luna5ama:kmogus-joml:$kmogusVersion")
    implementation("org.lz4:lz4-java:1.8.0")

    implementation("dev.luna5ama:glc2vk-capture")
}

tasks {
    jar {
        manifest {
            attributes["Main-Class"] = "dev.luna5ama.gltest.MainKt"
        }
    }

    val fatJar by creating(Jar::class) {
        group = "build"

        from(jar.get().archiveFile.map { zipTree(it) })
        from(configurations.runtimeClasspath.get().elements.map { set ->
            set.map {
                if (it.asFile.isDirectory) it else zipTree(
                    it
                )
            }
        })

        manifest {
            attributes["Main-Class"] = "dev.luna5ama.gltest.MainKt"
        }

        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        archiveClassifier.set("fatjar")
    }

    val optimizeFatJar = jarOptimizer.register(
        fatJar,
        "dev.luna5ama", "dev.fastmc", "org.lwjgl"
    )

    artifacts {
        archives(optimizeFatJar)
    }
}

allprojects {
    tasks {
        test {
            useJUnitPlatform()
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_23)
            freeCompilerArgs = listOf("-Xir-inliner=true")
        }
    }
}