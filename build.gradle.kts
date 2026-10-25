import java.time.Duration

plugins { java }

group = "io.github.jesterportfolio.hearth"
version = "0.1.0-SNAPSHOT"
val postgresJdbcVersion = "42.7.14"
val hikariVersion = "7.1.0"

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
    implementation("org.postgresql:postgresql:$postgresJdbcVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.properties(mapOf("version" to project.version, "postgresJdbcVersion" to postgresJdbcVersion, "hikariVersion" to hikariVersion))
    filesMatching("plugin.yml") { expand(inputs.properties) }
}

tasks.jar {
    archiveBaseName.set("Hearth")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test { useJUnitPlatform() }

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(integrationTest.implementationConfigurationName, "io.zonky.test:embedded-postgres:2.2.2") {
        exclude(group = "io.zonky.test.postgres")
    }
    add(integrationTest.runtimeOnlyConfigurationName, "io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64:18.3.0")
}
tasks.register<Test>("postgresIntegrationTest") {
    description = "새 사용자 소유 격리 PostgreSQL에서 저장·진행 격리를 검사합니다."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "256m"
    jvmArgs("-XX:ActiveProcessorCount=2")
    timeout.set(Duration.ofSeconds(90))
    outputs.upToDateWhen { false }
    doFirst {
        val root = providers.gradleProperty("s44Workspace").orNull
            ?: error("-Ps44Workspace=/tmp/hearth-s4-4.* 새 격리 경로가 필요합니다.")
        systemProperty("hearth.s44.workspace", root)
        systemProperty("java.io.tmpdir", root)
    }
}

tasks.register("stageS44Runtime") {
    description = "네이티브 바이너리 실행 전 해시 검사용 시험 의존성을 격리 경로에 복사합니다."
    doLast {
        val root = file(providers.gradleProperty("s44Workspace").get())
        require(root.canonicalPath.startsWith("/tmp/hearth-s4-4."))
        val target = root.resolve("resolved-jars")
        require(!target.exists()) { "기존 시험 의존성 경로 재사용 금지" }
        copy { from(configurations[integrationTest.runtimeClasspathConfigurationName]); into(target) }
    }
}
