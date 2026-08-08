plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.gradleup.shadow") version "8.3.9" apply false
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
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
