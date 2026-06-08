plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
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

    implementation(project(":akiba_mod_utils"))

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

    // Ktor server supports
    implementation(platform("io.ktor:ktor-bom:3.4.2"))
    implementation("io.ktor:ktor-server:3.4.2")
    implementation("io.ktor:ktor-server-netty:3.4.2")
    implementation("io.ktor:ktor-server-websockets:3.4.2")
    implementation("io.ktor:ktor-server-cors:3.4.2")
    implementation("io.ktor:ktor-server-compression:3.4.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.2")

    // Ktor client supports (for internal use)
    implementation("io.ktor:ktor-client-core:3.4.2")
    implementation("io.ktor:ktor-client-cio:3.4.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.2")
    implementation("io.ktor:ktor-client-websockets:3.4.2")
    implementation("io.ktor:ktor-serialization-jackson-jvm:3.4.2")

    // Langchain4j core + providers
    implementation("dev.langchain4j:langchain4j:1.15.0")
    implementation("dev.langchain4j:langchain4j-open-ai:1.15.0")
    implementation("dev.langchain4j:langchain4j-anthropic:1.15.0")
    implementation("dev.langchain4j:langchain4j-google-ai-gemini:1.15.0")
    implementation("dev.langchain4j:langchain4j-mistral-ai:1.15.0")
    implementation("dev.langchain4j:langchain4j-ollama:1.15.0")
    implementation("dev.langchain4j:langchain4j-azure-open-ai:1.15.0")

    // JWT for authentication
    implementation("io.jsonwebtoken:jjwt:0.12.6")
    implementation("org.mindrot:jbcrypt:0.4")

    // Kotlin compiler for runtime script compilation. Pinned to the same
    // version as the project's `kotlin("jvm")` plugin so that runtime-compiled
    // scripts can read the metadata of the framework's own classes (and of
    // transitive deps such as Ktor compiled with the same Kotlin).
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")

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
    into("akiba-${version}/configs/") {
        from("src/main/resources/configs/log4j2.xml")
        from("src/main/resources/configs/ghidra_log.xml")
    }
    into("akiba-${version}/scripts") {
        from("src/main/scripts/match_progress_monitor.py")
        from("src/main/scripts/task_stage_monitor.py")
        from("src/main/scripts/start_monitor.sh")
        from("src/main/scripts/entry_checker_dynamic.py")
        from("src/main/scripts/starter.py")
        from("src/main/scripts/clear_cache.sh")
    }
    into("akiba-${version}/scripts/util") {
        from("src/main/scripts/util/colorama_extension.py")
    }
}

tasks.distTar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "8g"
    // Ghidra 12.x installs a global serial filter factory during application
    // initialization, but only the very first such installation per JVM is
    // allowed. Gradle's test executor pre-installs its own factory, which
    // makes Ghidra fail with "Cannot replace filter factory: ...". Pinning
    // Ghidra's factory via this system property defers initialization until
    // first use and avoids the conflict (see GhidraSerialFilterFactory docs).
    systemProperty(
        "jdk.serialFilterFactory",
        "ghidra.framework.remote.GhidraSerialFilterFactory"
    )
    // Use the same log4j2 configuration as the application so AkibaModule's
    // logger setup (which looks up the "Console" appender) finds it.
    systemProperty("log4j.configurationFile", "configs/log4j2.xml")
    systemProperty("log4j.skipJansi", "true")
}

kotlin {
    jvmToolchain(21)
}