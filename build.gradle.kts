plugins {
    kotlin("jvm") version "2.3.0"
    id("io.github.goooler.shadow") version "8.1.8"
    id("java")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

group = "org.lewapnoob"
version = "0.1"
val MainClass = "org.lewapnoob.launcher.MainKt"

tasks {
    shadowJar {
        archiveFileName.set("Launcher.jar")
        mergeServiceFiles()
        manifest {
            attributes["Main-Class"] = MainClass
        }
    }
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = MainClass
    }
}

tasks.jar {
    archiveBaseName.set("Launcher_RAW")
    archiveVersion.set("")
    archiveClassifier.set("")
}