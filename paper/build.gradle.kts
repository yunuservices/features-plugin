plugins {
    java
    id("com.gradleup.shadow") version "9.0.2"
}

dependencies {
    implementation(project(":core"))
    implementation("org.incendo:cloud-paper:2.0.0-beta.10")
    implementation("org.incendo:cloud-annotations:2.0.0")
    implementation("org.jspecify:jspecify:1.0.0")

    compileOnly("io.papermc.paper:paper-api:${rootProject.extra["paperApiVersion"]}")
    compileOnly("com.mojang:brigadier:1.3.10")
    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("io.github.miniplaceholders:miniplaceholders-api:3.1.0")
    annotationProcessor("org.incendo:cloud-annotations:2.0.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:${rootProject.extra["paperApiVersion"]}")
    testImplementation("io.github.miniplaceholders:miniplaceholders-api:3.1.0")
}

tasks {
    processResources {
        filesMatching("paper-plugin.yml") {
            expand("version" to rootProject.version)
        }
    }
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("features-paper")
        archiveVersion.set(project.version.toString())
        archiveFileName.set("features-paper-${project.version}.jar")
        relocate("com.fasterxml.jackson", "io.yunuservices.features.libs.jackson")
        relocate("org.yaml.snakeyaml", "io.yunuservices.features.libs.snakeyaml")
        relocate("org.mineskin", "io.yunuservices.features.libs.mineskin")
        relocate("com.google.gson", "io.yunuservices.features.libs.gson")
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
    test {
        useJUnitPlatform()
    }
}
