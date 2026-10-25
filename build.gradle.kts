plugins { java }

group = "io.github.jesterportfolio.hearth"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.19")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.4")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar {
    archiveBaseName.set("Hearth")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test { useJUnitPlatform() }
