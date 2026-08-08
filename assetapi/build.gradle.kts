plugins {
    kotlin("jvm")
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Emit JVM 21 bytecode (class-file version 65) while compiling with the
// installed JDK, so the jar runs on any JVM 21+ (incl. automod's Zulu 25).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // UnityFS compression seams: open-source Java/Kotlin libs (plan §3). LZ4 block/stream
    // decompression and XZ/LZMA are the baseline; brotli added if the corpus needs it.
    implementation("org.lz4:lz4-java:1.8.0")
    implementation("org.tukaani:xz:1.10")

    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "asset4j"
            pom {
                name.set("asset4j")
                description.set("Kotlin/JVM port of the AssetsTools.NET Unity asset library")
                url.set("https://github.com/jpabscale/asset4j")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
    // Consumed via JitPack (https://jitpack.io), which builds the repo on demand:
    //   //> using repository https://jitpack.io
    //   //> using dep com.github.jpabscale:asset4j:<tag-or-sha>
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "4g"
}
