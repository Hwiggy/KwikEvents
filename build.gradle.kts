import java.util.Properties

plugins {
    kotlin("jvm") version "1.7.21"
    `maven-publish`
}

val properties = Properties().also {
    val localFile = rootProject.file("local.properties")
    if (!localFile.exists()) return@also
    it.load(localFile.reader())
}

group = "me.hwiggy"
version = "1.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    implementation("com.google.guava:guava:31.1-jre")
}


tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }
}

publishing {
    repositories {
        maven {
            val baseUrl = "https://nexus.mcdevs.us/repository"
            if (project.version.toString().endsWith("-SNAPSHOT")) {
                setUrl("${baseUrl}/mcdevs-snapshots/")
                mavenContent { snapshotsOnly() }
            } else {
                setUrl("${baseUrl}/mcdevs-releases/")
                mavenContent { releasesOnly() }
            }
            credentials {
                username = properties["NEXUS_USERNAME"] as String?
                password = properties["NEXUS_PASSWORD"] as String?
            }
        }
    }
    publications {
        create<MavenPublication>("assembly") {
            from(components["java"])
        }
    }
}