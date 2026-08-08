pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provision the JDK 25 toolchain (compilation) on hosts that don't
    // have it — e.g. JitPack (JDK 21), CI, fresh clones. Bytecode is still
    // emitted for JVM 21 via tasks.withType<JavaCompile> { options.release }.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "asset4j"

include("assetapi")
include("assetcli")
include("ttmapgen")
