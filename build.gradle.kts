plugins {
    kotlin("jvm") version "2.3.20-Beta2"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "cn.oneachina"

version = run {
    fun exec(cmd: List<String>) = try {
        ProcessBuilder(cmd).directory(rootProject.projectDir)
            .start().inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "" }
    val year = exec(listOf("powershell", "-c", "(Get-Date).Year.toString().substring(2)"))
    val month = exec(listOf("powershell", "-c", "(Get-Date).Month"))
    val count = exec(listOf("git", "rev-list", "--count", "HEAD")).ifEmpty { "0" }
    val hash = exec(listOf("git", "rev-parse", "--short=7", "HEAD")).ifEmpty { "unknown" }
    "$year.$month.$count-$hash"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://repo.codemc.io/repository/maven-public/")
    maven ("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("me.zombie_striker:QualityArmory:2.1.3")
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
