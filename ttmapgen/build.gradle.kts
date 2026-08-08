plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Emit JVM 21 bytecode (class-file version 65) while compiling with the
// installed JDK, so the jar runs on any JVM 21+.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("com.github.jpabscale.asset4j.ttmapgen.MainKt")
}

tasks.shadowJar {
    archiveFileName.set("ttmapgen.jar")
}

dependencies {
    implementation(project(":assetapi"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")

    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")
}
