import java.time.Duration
import java.security.MessageDigest

plugins { java }

group = "io.github.jesterportfolio.hearth"
version = "0.1.0-SNAPSHOT"
val postgresJdbcVersion = "42.7.14"
val hikariVersion = "7.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    exclusiveContent {
        forRepository {
            ivy {
                name = "BetterHudPinned"
                url = uri("https://hangar.papermc.io/api/v1/projects/")
                patternLayout { artifact("[organisation]/[module]/versions/[revision]/PAPER/download") }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("toxicity188", "BetterHud") }
    }
}

val betterHudApi by configurations.creating
dependencies {
    add(betterHudApi.name, "toxicity188:BetterHud:2.1.0-SNAPSHOT-448")
    compileOnly(files(betterHudApi))
}
val verifyBetterHudApi by tasks.registering {
    inputs.files(betterHudApi)
    doLast {
        val bytes = betterHudApi.singleFile.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(sha == "dc837b15d6cc867ea6fec3fe05ef2390a6e0928c59788c797f2631f139cc818d") { "BetterHud official artifact hash mismatch" }
    }
}
tasks.named("compileJava") { dependsOn(verifyBetterHudApi) }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.19")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.4")
    compileOnly("com.google.code.gson:gson:2.14.0") // pinned Paper runtime provides Gson
    implementation("org.postgresql:postgresql:$postgresJdbcVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.14.0")
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
    from("resourcepack") { into("betterhud/merge") }
    from("resourcepack/assets/hearth/textures/gui/minimap_pixel.png") { into("betterhud/assets/hearth") }
    doLast {
        destinationDir.resolve("betterhud/merge-files.txt").writeText(
            fileTree("resourcepack").files.map { it.relativeTo(file("resourcepack")).invariantSeparatorsPath }.sorted().joinToString("\n", postfix="\n")
        )
    }
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
    include("**/PostgresIsolationTest.class")
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

tasks.register("stageS45Runtime") {
    description = "S4-5 DB 보조 프로세스의 클래스와 시험 라이브러리를 준비합니다. 서버를 실행하지 않습니다."
    dependsOn(tasks.named("integrationTestClasses"))
    doLast {
        val root = file(providers.gradleProperty("s45Workspace").get())
        require(root.canonicalFile.parentFile == file("/tmp") && root.name.startsWith("hearth-s4-5."))
        val target = root.resolve("resolved-jars")
        require(!target.exists()) { "기존 시험 경로 덮어쓰기 금지" }
        copy { from(configurations[integrationTest.runtimeClasspathConfigurationName]); into(target) }
        copy { from(integrationTest.output); into(root.resolve("fixture-classes")) }
    }
}

tasks.register<Test>("inventoryIntegrationTest") {
    description = "새 격리 PostgreSQL의 V001 보존·V002·인벤토리 키/CAS를 검사합니다."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    include("**/InventoryPostgresTest.class")
    maxHeapSize = "256m"
    jvmArgs("-XX:ActiveProcessorCount=2")
    timeout.set(Duration.ofSeconds(90))
    outputs.upToDateWhen { false }
    doFirst {
        val root = providers.gradleProperty("inventoryWorkspace").get()
        systemProperty("hearth.inventory.workspace", root)
        systemProperty("java.io.tmpdir", root)
    }
}

tasks.register("stageInventoryRuntime") {
    dependsOn(tasks.named("integrationTestClasses"))
    doLast {
        val root = file(providers.gradleProperty("inventoryWorkspace").get())
        require(root.canonicalFile.parentFile == file("/tmp") && root.name.startsWith("hearth-s4-6."))
        require(!root.resolve("resolved-jars").exists())
        copy { from(configurations[integrationTest.runtimeClasspathConfigurationName]); into(root.resolve("resolved-jars")) }
        copy { from(integrationTest.output); into(root.resolve("fixture-classes")) }
    }
}


tasks.register("stageLifecycleRuntime") {
    description = "S4-11 새 격리 시험 경로에 기존 DB 시험 의존성만 준비합니다."
    dependsOn(tasks.named("integrationTestClasses"))
    doLast {
        val root = file(providers.gradleProperty("lifecycleWorkspace").get())
        require(root.canonicalFile.parentFile == file("/tmp") && root.name.startsWith("hearth-s4-11."))
        require(!root.resolve("resolved-jars").exists())
        copy { from(configurations[integrationTest.runtimeClasspathConfigurationName]); into(root.resolve("resolved-jars")) }
        copy { from(integrationTest.output); into(root.resolve("fixture-classes")) }
    }
}
