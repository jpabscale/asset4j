plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.gradleup.shadow") version "8.3.9" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

// Version comes from the git tag (e.g. "33ef77e", "33ef77e.1") so JitPack, which
// resolves versions by git ref, publishes the same version string. Falls back to
// the short commit sha, then "dev", when no tag is present.
val assetVersion: String = run {
    val tag = providers.exec {
        commandLine("git", "describe", "--tags", "--exact-match", "--abbrev=0")
        isIgnoreExitValue = true
    }.standardOutput.asText.getOrElse("").trim()
    if (tag.isNotEmpty()) tag.removePrefix("v")
    else {
        val sha = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.getOrElse("").trim()
        if (sha.isNotEmpty()) sha else "dev"
    }
}

allprojects {
    group = "com.github.jpabscale"
    version = assetVersion

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // detekt 1.23.x cannot parse JVM-25-targeted sources; pin its analysis to 21
    // (module bytecode is JVM_21 anyway)
    // Workaround: detekt 1.23.8 bundles Kotlin 1.9 which cannot parse java.version "25.0.x"
    val original_java_version = System.getProperty("java.version")
    val original_spec_version = System.getProperty("java.specification.version")
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
        doFirst {
            System.setProperty("java.version", "21.0.0")
            System.setProperty("java.specification.version", "21")
        }
        doLast {
            if (original_java_version != null) System.setProperty("java.version", original_java_version) else System.clearProperty("java.version")
            if (original_spec_version != null) System.setProperty("java.specification.version", original_spec_version) else System.clearProperty("java.specification.version")
        }
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt.yml"))
        buildUponDefaultConfig = false
        parallel = true
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.named("check") {
            dependsOn("detekt")
        }
    }
}
