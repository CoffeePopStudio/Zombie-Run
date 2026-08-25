import java.time.LocalDate

plugins {
    kotlin("jvm") version "2.3.20-Beta2"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "cn.oneachina"

version = run {
    fun exec(cmd: List<String>) = try {
        ProcessBuilder(cmd).directory(rootProject.projectDir)
            .start().inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "" }
    val today = LocalDate.now()
    val year = today.year.toString().substring(2)
    val month = today.monthValue.toString()
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
    maven("https://repo.onarandombox.com/content/groups/public/")
    maven ("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("me.zombie_striker:QualityArmory:2.1.3")
    compileOnly("com.onarandombox.multiversecore:multiverse-core:4.3.14")
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.+")
}

tasks.shadowJar {
    archiveBaseName.set("zombie-run-v2")
    archiveClassifier.set("")
    dependencies {
        include(dependency("org.jetbrains.kotlin:.*"))
        include(dependency("com.zaxxer:HikariCP:.*"))
        include(dependency("org.xerial:sqlite-jdbc:.*"))
    }
    mergeServiceFiles()
}

tasks.jar {
    archiveBaseName.set("zombie-run-v2")
    archiveClassifier.set("thin")
}

runPaper {
    folia.registerTask()
}

tasks {
    runServer {
        minecraftVersion("26.1.2")
        dependsOn("shadowJar")
        downloadPlugins {
            modrinth("qualityarmory", "fdVKuHYp")
        }
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
