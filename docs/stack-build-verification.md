# Paper 26.2 최소 빌드 검증 기록

- 확인일: 2026-10-23 (Asia/Seoul)
- 기준 SHA: `d7b80797d770b02af1b739e9a5259e2119d69ef6`
- 시작 상태: `main`, 작업 트리·staging 깨끗함. fetch 후 `origin/main...HEAD`는 `0 0`, 기존 미공개 커밋 없음.
- 이전 사전 확인 결론: Stable API 다운로드까지 확인했으나 JDK·Gradle 부재로 컴파일은 미실행이었음. 아래에 해당 이력을 보존합니다.
- 최신 재개 기준 SHA: `1231166724d0e26aee38c9c5f87807f841bd2cb3`.
- 최신 결론: **Temurin JDK 25.0.5+8-LTS + Gradle 9.1.0 + Paper API 26.2.build.134-stable 조합의 실제 의존성 해결·Java 컴파일·JAR 패키징 성공.** Minecraft 서버 로딩·게임 기능·런타임 호환성은 미검증입니다.

## 2026-10-23 실제 빌드 재개 결과

시작 시 `main` 작업 트리·staging은 깨끗했고 origin fetch 후 이력 차이는 `0 0`이었습니다. 사용자 승인에 따라 JDK는 홈의 전용 디렉터리에, Gradle은 /tmp에 압축 해제했습니다. root·시스템 패키지·기본 Java·셸 초기화 파일·전역 환경 변수·기존 서비스 설정은 변경하지 않았습니다. JAVA_HOME과 GRADLE_USER_HOME은 실행 명령에만 지정했습니다.

### 공식 배포본과 체크섬

| 도구 | 공식 배포본·체크섬 출처 | 확인한 SHA-256 |
| --- | --- | --- |
| Temurin JDK 25 Linux x64 HotSpot | [공식 배포본](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz), [공식 SHA-256](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz.sha256.txt) | `68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb` |
| Gradle 9.1.0 binary | [공식 배포본](https://services.gradle.org/distributions/gradle-9.1.0-bin.zip), [공식 SHA-256](https://services.gradle.org/distributions/gradle-9.1.0-bin.zip.sha256) | `a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806` |

배포본 모두 SHA-256 일치 후 압축을 해제했습니다. JDK 해시는 [공식 Adoptium API](https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse)의 선택 배포 항목 checksum과도 일치합니다. 다운로드·체크섬·설치 과정에 오류는 없었으며 우회하지 않았습니다.

- JDK: `/home/server/.local/share/hearth-verification/jdk-25.0.5+8`
- Gradle: `/tmp/hearth-gradle-9.1.0/gradle-9.1.0`
- 프로젝트·빌드 캐시: `/tmp/hearth-paper-build-18zrg9zn`, 그 아래 `gradle-user-home`
- 아카이브: `/tmp/hearth-temurin25.tar.gz`, `/tmp/hearth-gradle-9.1.0-bin.zip`

실제 도구 출력:

```text
openjdk version "25.0.5" 2026-10-20 LTS
OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
javac 25.0.5
jar 25.0.5
Gradle 9.1.0
Kotlin: 2.2.0
Launcher JVM: 25.0.5 (Eclipse Adoptium 25.0.5+8-LTS)
Daemon JVM: /home/server/.local/share/hearth-verification/jdk-25.0.5+8
```

### 실제 실행 명령과 결과

기존 /tmp 프로젝트가 남아 있어 아래 기록의 소스·Kotlin DSL·compileOnly 고정 버전을 그대로 재사용했습니다. toolchain 자동 다운로드는 계속 꺼 두었습니다. 작업 디렉터리는 `/tmp/hearth-paper-build-18zrg9zn`입니다.

```bash
env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-paper-build-18zrg9zn/gradle-user-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --console=plain --info dependencies --configuration compileClasspath compileJava jar > build-validation.log 2>&1
/home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/jar tf build/libs/hearth-paper-verification-0.0.1.jar
/home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/javap -verbose -classpath build/libs/hearth-paper-verification-0.0.1.jar org.hearth.verification.VerificationPlugin
```

모두 종료 코드 0. 로그는 `/tmp/hearth-paper-build-18zrg9zn/build-validation.log`에 있습니다. 비상주 설정에서도 Gradle이 빌드 동안 single-use daemon을 만들고 종료하는 정상 동작이 기록됐습니다. Minecraft 서버 실행과는 별개입니다.

```text
> Task :dependencies
> Task :compileJava
Compiling with toolchain '/home/server/.local/share/hearth-verification/jdk-25.0.5+8'.
Compiling with JDK Java compiler API.
> Task :processResources
> Task :classes
> Task :jar
BUILD SUCCESSFUL in 27s
4 actionable tasks: 4 executed
```

Gradle 로그에서 공식 Paper 저장소의 고정 API POM·module·JAR과 Maven Central·Paper 저장소의 전이 의존성 다운로드를 확인했습니다. 의존성 트리에 `io.papermc.paper:paper-api:26.2.build.134-stable`이 있고 FAILED 항목이 없었습니다. 과거 curl 다운로드와 달리 이번에는 Gradle compileClasspath 해결·컴파일까지 실행했습니다.

### 산출물 검사

- 경로: `/tmp/hearth-paper-build-18zrg9zn/build/libs/hearth-paper-verification-0.0.1.jar`
- 크기: **1,271 bytes**
- SHA-256: `44eaaf9dffc31f07cd045a62977bba9dd6cce257f71c96130ab5acfa8f30713e`
- ZIP CRC 검사 통과. 디렉터리 항목 외 파일은 다음 세 개뿐입니다.

```text
META-INF/MANIFEST.MF
org/hearth/verification/VerificationPlugin.class
plugin.yml
```

`javap`와 클래스 헤더 검사에서 `major version: 69`, `minor version: 0`을 확인했습니다. 메인 클래스는 `JavaPlugin`을 상속하며 plugin.yml의 main과 일치합니다. JAR 안 plugin.yml은 소스 파일과 바이트 단위로 같고 `api-version: '26.2'`입니다. `org/bukkit/`, `io/papermc/`, `net/kyori/` 클래스가 없고 전체 파일 목록도 예상 항목과 같아 API·전이 의존성이 불필요하게 묶이지 않았음을 확인했습니다.

| 확인 단계 | 최신 결과 | 남는 한계 |
| --- | --- | --- |
| 공식 문서 호환성 | 기존 공식 문서 기준 확인 유지 | 모든 API·게임 기능의 보장은 아님 |
| 공식 배포본·실행 도구 | 체크섬 통과, JDK 25.0.5와 Gradle 9.1.0 정상 실행 | 전역 설치·설정 없음 |
| Gradle 의존성 해결 | 고정 Paper API와 compileClasspath 해결 성공 | DB·AI·다른 플러그인 통합은 대상 밖 |
| 실제 컴파일 | Java 25 toolchain compileJava 성공, class major 69 확인 | 최소 API 사용 코드만 검증 |
| JAR 패키징 | jar 성공, 메인 클래스·plugin.yml·API 미포함 확인 | 서버 로딩·런타임 테스트 미실행 |

산출물·도구·전체 로그는 저장소에 추가하지 않았습니다. /tmp 파일의 장기 보존은 보장하지 않습니다. 실제 Hearth 게임 소스·배포 구성·운영 서비스는 변경하지 않았습니다. 설치한 사용자 전용 JDK는 명시 경로로만 사용하며 향후 제거가 필요해도 별도 확인 없이 기존 데이터를 삭제하지 않습니다.

## 이전 사전 확인 이력 (1231166)

아래 결과·오류·미실행 표시는 도구 준비 전의 과거 기록이며 최신 결과는 위 재개 기록을 따릅니다.

## 이전 확인 범위별 결과

| 범위 | 결과 | 한계 |
| --- | --- | --- |
| 공식 문서 | Paper의 Kotlin DSL·compileOnly·Java 25 설정, Gradle의 Java 25 지원 9.1.0 이상 확인 | 현재 호스트 빌드 증거가 아님 |
| Maven 메타데이터 | 26.2 Stable 버전 48개 확인, 확인 시 가장 높은 빌드 `26.2.build.134-stable` 선택 | `26.2-R0.1-SNAPSHOT`이나 동적 `+`로 대체하지 않음 |
| API 다운로드 | POM·module·JAR HTTP 200, 실제 내용 확인 | Gradle의 전체 전이 의존성 해결은 미실행 |
| 임시 프로젝트 | `/tmp/hearth-paper-build-18zrg9zn`에 최소 Java 플러그인·Kotlin DSL·plugin.yml 구성 | 구문 평가·컴파일·서버 로드는 미검증 |
| compileJava·jar | 미실행: 도구 사전 조건 미충족 | 실패한 컴파일 로그나 성공 출력은 없음 |
| 플러그인 산출물 | `build/libs/*.jar` 없음, JAR 내부 plugin.yml 포함 여부 미검증 | 내려받은 Paper API JAR은 생성한 플러그인 JAR이 아님 |

## 이전 호스트 도구와 오류

`command -v java javac gradle`은 경로를 반환하지 않았습니다. 개별 버전 명령은 각각 다음 오류로 실행되지 않았습니다(명령 없음, 종료 코드 127).

```text
java: command not found
javac: command not found
gradle: command not found
```

`rpm -qa '*openjdk*' '*gradle*'`에 일치 항목이 없었습니다. `/usr/lib/jvm`, `/opt/java`, `/opt/gradle`, 사용자 `.gradle` 및 SDKMAN Java·Gradle 경로도 없었습니다. JAVA_HOME·JDK_HOME·GRADLE_HOME은 미설정입니다. `/usr/local/bin`, `/usr/local/lib`, `/usr/local/share`, `/opt`, `/home/server`의 제한 깊이 탐색에서도 실행 파일을 찾지 못했습니다. 이는 PATH·RPM·조사 경로 기준이며 호스트 모든 저장소의 부재를 단정하지 않습니다.

프로젝트에는 기존 Gradle 빌드·Wrapper·게임 코드가 없었습니다. 확인한 Gitleaks는 `8.30.1`, `core.hooksPath=.githooks`이며 pre-commit·pre-push는 실행 권한이 있습니다. 이번 작업에서 JDK·Gradle·시스템 패키지·Wrapper 배포물을 설치하거나 다운로드하지 않았습니다.

## 공식 API 증거

[Paper 공식 Maven 메타데이터](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml)의 `lastUpdated`는 `20261022133449`입니다. 선택한 고정 좌표는 다음과 같습니다.

```text
io.papermc.paper:paper-api:26.2.build.134-stable
```

같은 공식 저장소의 고정 버전 파일을 내려받아 확인했습니다.

| 파일 | 내용 확인 |
| --- | --- |
| [POM](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/26.2.build.134-stable/paper-api-26.2.build.134-stable.pom) | groupId·artifactId·version이 선택 좌표와 일치 |
| [Gradle module](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/26.2.build.134-stable/paper-api-26.2.build.134-stable.module) | 상태 release, apiElements·runtimeElements JVM 버전 25 |
| [API JAR](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/26.2.build.134-stable/paper-api-26.2.build.134-stable.jar) | 2,889,195 bytes, ZIP CRC 정상, JavaPlugin.class 존재·class major 69 |

JAR SHA-256: `6eeffedb6867a9b8c3b0f8e16ad870e577f614ef8d44d8b34ae24a16afbcfe32`.
크기·SHA-256이 내려받은 module의 apiElements 파일 항목과 일치했습니다. 게시 파일과의 일관성 검사이며 독립 서명 검증은 아닙니다.

최초 sandbox 요청은 `curl: (6) Could not resolve host: repo.papermc.io`로 실패했습니다. 네트워크 권한 승인 후 같은 공식 HTTPS 경로에서 XML과 위 파일을 받았습니다. HTML 응답을 Maven 메타데이터로 오인하지 않도록 XML·JSON·ZIP을 파싱했습니다.

## 재사용한 임시 프로젝트 설정과 이전 재개 계획

임시 파일은 `/tmp`에만 두었고 저장소에 게임 소스·바이너리를 추가하지 않았습니다. 해당 경로는 임시 저장소이므로 보존을 보장하지 않습니다. 재현을 위해 설정과 최소 소스를 아래에 기록합니다.

`settings.gradle.kts`:

```kotlin
rootProject.name = "hearth-paper-verification"
```

`build.gradle.kts`:

```kotlin
plugins { java }
group = "org.hearth.verification"
version = "0.0.1"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}
```

`gradle.properties`:

```properties
org.gradle.java.installations.auto-download=false
org.gradle.daemon=false
```

`src/main/java/org/hearth/verification/VerificationPlugin.java`:

```java
package org.hearth.verification;
import org.bukkit.plugin.java.JavaPlugin;
public final class VerificationPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("Hearth build verification");
    }
}
```

`src/main/resources/plugin.yml`:

```yaml
name: HearthBuildVerification
version: '0.0.1'
main: org.hearth.verification.VerificationPlugin
api-version: '26.2'
```

이전 사전 확인에서는 **JDK 25(java·javac·jar 포함)**와 **Gradle 9.1.0 이상**이 없어 설치 승인을 기다렸습니다. 위 재개 작업은 사용자가 명시적으로 승인한 범위에서 도구를 준비했습니다. toolchain 자동 다운로드는 꺼 두었고 resolver 플러그인을 넣지 않았습니다.

도구가 준비된 뒤 버전·경로를 확인하고 임시 프로젝트에서 다음 명령을 실행할 계획입니다. **아래는 이전 미실행 계획이며 실제 재개 실행 명령·결과는 문서 상단에 별도 기록했습니다.**

```bash
GRADLE_USER_HOME=/tmp/hearth-paper-build-18zrg9zn/gradle-user-home gradle --no-daemon --console=plain compileJava jar
jar tf build/libs/hearth-paper-verification-0.0.1.jar
```

성공 조건은 실제 사용한 Gradle·Java compiler 버전 기록, compileJava·jar 성공, 결과 JAR 안의 `org/hearth/verification/VerificationPlugin.class`와 `plugin.yml` 확인, 메인 클래스·api-version 대조입니다. `compileOnly` Paper API가 출력에 묶이지 않았는지도 확인합니다. Gradle 의존성 해결·컴파일 오류가 발생하면 원인과 로그를 남기고 성공으로 표시하지 않습니다. 서버 실행·런타임 호환·게임 기능·성능은 이 검증의 성공 조건 밖입니다.

## 공식 문서와 프로젝트 결정

- [Paper 프로젝트 설정](https://docs.papermc.io/paper/dev/project-setup/): Kotlin DSL, compileOnly, Java 25 toolchain과 고정 Stable 좌표 형식. 현재 예시의 26.3을 이번 대상인 26.2 대신 사용하지 않았습니다.
- [Gradle Java 호환성 표](https://docs.gradle.org/current/userguide/compatibility.html): Java 25 toolchain 및 Gradle 실행 지원은 9.1.0부터입니다.
- [Paper plugin.yml](https://docs.papermc.io/paper/dev/plugin-yml/): resources 위치와 main·api-version 필드 기준. 이전에는 작성만 했으며, 최신 재개에서는 패키징을 확인했습니다. 서버 로딩은 여전히 미검증입니다.
- [ADR-0002](decisions/0002-stack-and-world-policies.md)의 설계는 변경하지 않습니다. 최신 재개 기록은 최소 플러그인 빌드 성공까지 확인하지만 전체 구현·운영 승인·런타임 검증을 뜻하지 않습니다.
