@file:Suppress("SpellCheckingInspection")

plugins {
    kotlin("jvm") version "1.8.0"
    kotlin("plugin.serialization") version "1.8.0"
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "jp.juggler.subwaytooter.appServerV2"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    val exposedVersion = "0.41.1"
    val ktorVersion = "2.2.2"

    implementation("ch.qos.logback:logback-classic:1.4.5")
    implementation("com.google.firebase:firebase-admin:8.2.0")
    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("commons-codec:commons-codec:1.15")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-double-receive:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    implementation("org.postgresql:postgresql:42.5.1")
    implementation("com.impossibl.pgjdbc-ng:pgjdbc-ng:0.8.9")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("MainKt")
}

tasks.jar {
    manifest {
        // 警告よけ
        // > WARNING: sun.reflect.Reflection.getCallerClass is not supported. This will impact performance.
        attributes["Multi-Release"] = "true"
    }
}
