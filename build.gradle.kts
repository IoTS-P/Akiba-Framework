plugins {
    application
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "org.iotsplab"

repositories {
    mavenCentral()
    flatDir(
        mapOf(
            "dirs" to listOf("libs")
        )
    )
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.fusesource.jansi:jansi:2.4.3")

    // Jackson supports
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.2"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Kotlin Coroutine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")

    // Command arguments parsing
    implementation("info.picocli:picocli:4.7.7")

    // PostgreSQL supports
    implementation("org.postgresql:postgresql:42.7.10")

    // Log4J supports
    implementation("org.apache.logging.log4j:log4j-api:2.25.4")
    implementation("org.apache.logging.log4j:log4j-core:2.25.4")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    // Reflection
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.20")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jline:jline:4.0.10")

    // Ktor client supports
    implementation(platform("io.ktor:ktor-bom:3.4.2"))
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-client-websockets")
    implementation("io.ktor:ktor-serialization-jackson-jvm")

    // For debug
    // implementation(fileTree("modules"))
}

application {
    applicationName = "akiba"
    applicationDefaultJvmArgs = listOf(
        "-Dlog4j.configurationFile=configs/log4j2.xml", "-Dlog4j.skipJansi=false", "-Xss100m")
    mainClass.set("org.iotsplab.akiba.Main")
}

tasks.distZip {
    into("akiba-$version/configs/") {
        from("src/main/resources/configs/log4j2.xml")
        from("src/main/resources/configs/ghidra_log.xml")
    }
    into("akiba-$version/scripts") {
        from("src/main/scripts/match_progress_monitor.py")
        from("src/main/scripts/task_stage_monitor.py")
        from("src/main/scripts/start_monitor.sh")
        from("src/main/scripts/entry_checker_dynamic.py")
        from("src/main/scripts/starter.py")
        from("src/main/scripts/clear_cache.sh")
    }
    into("akiba-$version/scripts/util") {
        from("src/main/scripts/util/colorama_extension.py")
    }
}

tasks.distTar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "8g"
}

kotlin {
    jvmToolchain(21)
}