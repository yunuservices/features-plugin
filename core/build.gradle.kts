plugins {
    `java-library`
}

dependencies {
    api("net.kyori:adventure-api:4.26.1")
    api("net.kyori:adventure-text-minimessage:4.26.1")
    implementation("net.kyori:adventure-text-serializer-gson:4.26.1")
    api("com.fasterxml.jackson.core:jackson-databind:2.21.1")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.1")
    api("org.mineskin:java-client-jsoup:${rootProject.extra["mineSkinClientVersion"]}")
    api("org.mineskin:java-client:${rootProject.extra["mineSkinClientVersion"]}")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.google.guava:guava:33.5.0-jre")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
