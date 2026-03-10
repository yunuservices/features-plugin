plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.2"
}

group = "io.yunuservices"
version = "1.0.0"

extra["paperApiVersion"] = "1.21.11-R0.1-20260310.030221-86"
extra["velocityApiVersion"] = "3.5.0-20260308.200400-12"
extra["velocityBrigadierVersion"] = "1.0.0-20210613.082804-10"
extra["mineSkinClientVersion"] = "3.2.1-20251007.124020-2"

dependencies {
    implementation(project(":paper"))
    implementation(project(":velocity"))
}

allprojects {
    apply(plugin = "java")
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.inventivetalent.org/repository/public/")
        maven("https://repo.extendedclip.com/releases/")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-Xlint:deprecation")
    }
}

tasks {
    jar {
        enabled = false
    }
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("features")
        archiveVersion.set(project.version.toString())
        archiveFileName.set("features-${project.version}.jar")
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
        relocate("com.fasterxml.jackson", "io.yunuservices.features.libs.jackson")
        relocate("org.yaml.snakeyaml", "io.yunuservices.features.libs.snakeyaml")
        relocate("org.mineskin", "io.yunuservices.features.libs.mineskin")
        relocate("com.google.gson", "io.yunuservices.features.libs.gson")
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
}
