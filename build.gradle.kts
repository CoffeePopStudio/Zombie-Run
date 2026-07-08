plugins {
    kotlin("jvm") version "2.3.20-Beta2"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "cn.oneachina"
version = "26.1.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven ("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("me.clip:placeholderapi:2.12.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.zaxxer:HikariCP:7.0.2")
}

tasks {
    runServer {
        minecraftVersion("26.1.2")
    }
}

val targetJavaVersion = 25
kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
