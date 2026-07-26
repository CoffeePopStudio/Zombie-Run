plugins {
    kotlin("jvm") version "2.3.20-Beta2"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "cn.oneachina"

version = run {
    val now = java.time.LocalDate.now()
    val year = now.year % 100
    val month = now.monthValue
    val count = try {
        ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootProject.projectDir)
            .start().inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "0" }
    val hash = try {
        ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
            .directory(rootProject.projectDir)
            .start().inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "unknown" }
    "$year.$month.$count-$hash"
}

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
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.zaxxer:HikariCP:7.0.2")
}

tasks.shadowJar {
    dependencies {
        include(dependency("org.jetbrains.kotlin:.*"))
        include(dependency("com.zaxxer:HikariCP:.*"))
    }
    mergeServiceFiles()
}

runPaper {
    folia.registerTask()
}

tasks {
    runServer {
        minecraftVersion("26.1.2")
        dependsOn("shadowJar")
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
