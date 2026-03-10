plugins {
    java
    id("com.gradleup.shadow") version "9.0.2"
}

dependencies {
    implementation(project(":core"))

    compileOnly("com.velocitypowered:velocity-api:${rootProject.extra["velocityApiVersion"]}")
    compileOnly("com.velocitypowered:velocity-brigadier:${rootProject.extra["velocityBrigadierVersion"]}")
    compileOnly("com.google.inject:guice:7.0.0")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    annotationProcessor("com.velocitypowered:velocity-api:${rootProject.extra["velocityApiVersion"]}")
    annotationProcessor("com.google.code.gson:gson:2.13.2")
    annotationProcessor("com.google.guava:guava:33.5.0-jre")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.velocitypowered:velocity-api:${rootProject.extra["velocityApiVersion"]}")
    testRuntimeOnly("com.velocitypowered:velocity-brigadier:${rootProject.extra["velocityBrigadierVersion"]}")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("features-velocity")
        archiveVersion.set(project.version.toString())
        archiveFileName.set("features-velocity-${project.version}.jar")
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
