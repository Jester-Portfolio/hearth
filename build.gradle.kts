import java.time.Duration
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
    compileOnly("dev.aurelium:auraskills-api-bukkit:2.4.0")
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

fun approvedTestBase(): File {
    val base=file("/home/server/hearth-tmp")
    val marker=base.resolve(".hearth-test-base").toPath()
    require(base.toPath()==base.toPath().toRealPath() && base.isDirectory)
    require((Files.getAttribute(Path.of("/proc/self"),"unix:uid") as Number).toInt()!=0)
    require(Files.getOwner(base.toPath())==Files.getOwner(Path.of("/proc/self")))
    require(Files.getPosixFilePermissions(base.toPath())==PosixFilePermissions.fromString("rwx------"))
    require(!Files.isSymbolicLink(marker) && Files.isRegularFile(marker) && Files.getOwner(marker)==Files.getOwner(base.toPath()) && Files.readString(marker)=="Hearth isolated test base v1\n")
    return base
}
fun requireTestRoot(root: File,prefix: String) {
    require(root.absoluteFile==root.canonicalFile && root.isDirectory && root.parentFile==approvedTestBase())
    require(root.name.matches(Regex(Regex.escape(prefix)+"[A-Za-z0-9_-]{1,80}")))
    require(Files.getOwner(root.toPath())==Files.getOwner(Path.of("/proc/self")))
}
tasks.test {
    useJUnitPlatform()
    doFirst {
        val temp=approvedTestBase().resolve("gradle-tmp").toPath()
        if(!Files.exists(temp)) Files.createDirectory(temp,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        require(temp==temp.toRealPath() && Files.getOwner(temp)==Files.getOwner(Path.of("/proc/self")))
        systemProperty("java.io.tmpdir",temp.toString())
    }
}

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
            ?: error("-Ps44Workspace=/home/server/hearth-tmp/hearth-s4-4.* 새 격리 경로가 필요합니다.")
        requireTestRoot(file(root),"hearth-s4-4.")
        systemProperty("hearth.s44.workspace", root)
        systemProperty("java.io.tmpdir", root)
    }
}

tasks.register("stageS44Runtime") {
    description = "네이티브 바이너리 실행 전 해시 검사용 시험 의존성을 격리 경로에 복사합니다."
    doLast {
        val root = file(providers.gradleProperty("s44Workspace").get())
        requireTestRoot(root,"hearth-s4-4.")
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
        requireTestRoot(root,"hearth-s4-5.")
        val target = root.resolve("resolved-jars")
        require(!target.exists()) { "기존 시험 경로 덮어쓰기 금지" }
        copy { from(configurations[integrationTest.runtimeClasspathConfigurationName]); into(target) }
        copy { from(integrationTest.output); into(root.resolve("fixture-classes")) }
        copy { from(sourceSets.main.get().output.classesDirs); include("io/github/jesterportfolio/hearth/verification/IsolatedPaths.class"); into(root.resolve("fixture-classes")) }
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
        requireTestRoot(file(root),"hearth-s4-6.")
        systemProperty("hearth.inventory.workspace", root)
        systemProperty("java.io.tmpdir", root)
    }
}

tasks.register("stageInventoryRuntime") {
    dependsOn(tasks.named("integrationTestClasses"))
    doLast {
        val root = file(providers.gradleProperty("inventoryWorkspace").get())
        requireTestRoot(root,"hearth-s4-6.")
        require(!root.resolve("resolved-jars").exists())
        copy { from(configurations[integrationTest.runtimeClasspathConfigurationName]); into(root.resolve("resolved-jars")) }
        copy { from(integrationTest.output); into(root.resolve("fixture-classes")) }
        copy { from(sourceSets.main.get().output.classesDirs); include("io/github/jesterportfolio/hearth/verification/IsolatedPaths.class"); into(root.resolve("fixture-classes")) }
    }
}


tasks.register("stageLifecycleRuntime") {
    description = "S4-11 새 격리 시험 경로에 기존 DB 시험 의존성만 준비합니다."
    dependsOn(tasks.named("integrationTestClasses"))
    doLast {
        val root = file(providers.gradleProperty("lifecycleWorkspace").get())
        requireTestRoot(root,"hearth-s4-11.")
        require(!root.resolve("resolved-jars").exists())
        copy { from(configurations[integrationTest.runtimeClasspathConfigurationName]); into(root.resolve("resolved-jars")) }
        copy { from(integrationTest.output); into(root.resolve("fixture-classes")) }
        copy { from(sourceSets.main.get().output.classesDirs); include("io/github/jesterportfolio/hearth/verification/IsolatedPaths.class"); into(root.resolve("fixture-classes")) }
    }
}

// 오프라인 검토만 실행합니다. 서버/DB/HTTP 호출이나 기존 파일 수정은 하지 않습니다.
tasks.register<JavaExec>("operatorConfigAudit") {
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().output + configurations.compileClasspath.get() + configurations.runtimeClasspath.get()
    mainClass.set("io.github.jesterportfolio.hearth.administration.OperatorConfigAudit")
    maxHeapSize = "256m"
    jvmArgs("-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=/home/server/hearth-tmp/gradle-tmp")
    doFirst {
        args(providers.gradleProperty("auditRoot").get(),providers.gradleProperty("auditOutput").get(),providers.gradleProperty("auditServerJar").get())
        providers.gradleProperty("auditInclude").orNull?.split(",")?.filter { it.isNotBlank() }?.let { args(it) }
    }
}

// 한 배포 JAR 안의 선택형 Velocity 진입점. Paper에서는 이 클래스/의존성을 초기화하지 않습니다.
val velocity by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
}
dependencies { add(velocity.compileOnlyConfigurationName,"com.velocitypowered:velocity-api:4.3.1") }
tasks.named<ProcessResources>(velocity.processResourcesTaskName) {
    inputs.property("version",project.version)
    filesMatching("velocity-plugin.json") { expand(mapOf("version" to project.version)) }
}
tasks.jar { dependsOn(tasks.named(velocity.classesTaskName));from(velocity.output) }

tasks.register("stageNetworkRuntime") {
    dependsOn(tasks.named("integrationTestClasses"))
    doLast {
        val root=file(providers.gradleProperty("networkWorkspace").get())
        requireTestRoot(root,"hearth-network-run.")
        val target=root.resolve("resolved-jars")
        require(!target.exists())
        copy { from(integrationTest.output); into(root.resolve("fixture-classes")) }
        copy { from(configurations[integrationTest.runtimeClasspathConfigurationName]); into(target) }
    }
}

tasks.register("stageNativeEconomyRuntime") {
    dependsOn(tasks.named("integrationTestClasses"))
    doLast {
        val root=file(providers.gradleProperty("nativeEconomyWorkspace").get())
        requireTestRoot(root,"hearth-native-economy-run.")
        val target=root.resolve("resolved-jars")
        require(!target.exists())
        copy { from(integrationTest.output); into(root.resolve("fixture-classes")) }
        copy { from(configurations[integrationTest.runtimeClasspathConfigurationName]); into(target) }
    }
}

// 기술 검증 전용. Aura 코드/모델은 정식 Hearth JAR과 운영 의존성에 포함하지 않습니다.
val auraModel by sourceSets.creating
sourceSets.test { compileClasspath += auraModel.output; runtimeClasspath += auraModel.output }
tasks.named("compileTestJava") { dependsOn(tasks.named(auraModel.compileJavaTaskName)) }
val auraProbe by sourceSets.creating {
    compileClasspath += sourceSets.main.get().compileClasspath + auraModel.output
    runtimeClasspath += output + compileClasspath
}
val auraArtifact = providers.gradleProperty("auraSkillsJar")
if (auraArtifact.isPresent) dependencies.add(auraProbe.compileOnlyConfigurationName, files(auraArtifact.get()))
val verifyAuraArtifact by tasks.registering {
    doLast {
        require(auraArtifact.isPresent) { "-PauraSkillsJar=<official pinned AuraSkills 2.4.0 JAR> is required" }
        val path = file(auraArtifact.get())
        require(MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) } == "de54cbd2e33d65e8b1704751ae4121ed2f5b466ba89c63a1aadf3a3e629d6a40") { "AuraSkills artifact mismatch" }
    }
}
tasks.named(auraProbe.compileJavaTaskName) { dependsOn(verifyAuraArtifact, tasks.named(auraModel.compileJavaTaskName)) }
tasks.register<Jar>("auraProbeJar") {
    dependsOn(tasks.named(auraProbe.classesTaskName))
    archiveClassifier.set("aura-probe")
    from(auraProbe.output, auraModel.output)
}

// 정지 서버의 검토된 정책 이전. 운영 실행에는 별도 승인이 필요합니다.
tasks.register<JavaExec>("nativePolicyTool") {
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().output + configurations.compileClasspath.get() + configurations.runtimeClasspath.get()
    mainClass.set("io.github.jesterportfolio.hearth.administration.NativePolicyTool")
    maxHeapSize = "256m"
    jvmArgs("-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=/home/server/hearth-tmp/gradle-tmp")
    doFirst { args(providers.gradleProperty("policyAction").get(),providers.gradleProperty("policyPaper").get(),providers.gradleProperty("policyKind").get(),providers.gradleProperty("policyCandidate").get(),providers.gradleProperty("policyPlan").get(),providers.gradleProperty("policyOffline").get()) }
}
