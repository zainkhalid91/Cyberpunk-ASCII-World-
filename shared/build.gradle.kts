import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "com.zain.neonascii.shared"
        compileSdk = 37
        // RuntimeShader / RenderEffect (the ASCII pass) need Android 13.
        minSdk = 33
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        androidResources { enable = true }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            implementation(libs.compose.resources)
        }
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.zain.neonascii.res"
}

compose.desktop {
    application {
        mainClass = "com.zain.neonascii.MainKt"
        jvmArgs += listOf(
            "-Dskiko.renderApi=DIRECT3D",
            "-Dskiko.vsync.enabled=true",
            "-Dskiko.directx.gpu.priority=discrete",
            "-Dskiko.gpu.priority=discrete",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "NeonAscii"
            packageVersion = "1.0.0"
        }
    }
}

// Renders the scene offscreen to PNGs (no window, no audio) for quick visual checks:
//   gradlew :shared:snapshot --no-configuration-cache
tasks.register<JavaExec>("snapshot") {
    val main = kotlin.jvm("desktop").compilations.getByName("main")
    dependsOn(main.compileAllTaskName)
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("com.zain.neonascii.SnapshotKt")
    args(rootProject.file("snapshots").absolutePath)
    // gradlew :shared:snapshot -Pperf : timing only, with a Flight Recorder profile in snapshots/perf.jfr
    // gradlew :shared:snapshot -Pdesktop : desktop shots only (the phone-size CPU render is slow)
    if (project.hasProperty("desktop")) args("desktop-only")
    if (project.hasProperty("perf")) {
        args("perf-only")
        jvmArgs("-XX:StartFlightRecording=filename=${rootProject.file("snapshots/perf.jfr").absolutePath},settings=profile")
    }
}
