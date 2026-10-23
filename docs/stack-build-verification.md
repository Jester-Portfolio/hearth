# Paper 26.2 최소 빌드 사전 확인 기록

- 확인일: 2026-10-23 (Asia/Seoul)
- 기준 SHA: `d7b80797d770b02af1b739e9a5259e2119d69ef6`
- 시작 상태: `main`, 작업 트리·staging 깨끗함. fetch 후 `origin/main...HEAD`는 `0 0`, 기존 미공개 커밋 없음.
- 결론: **공식 문서 호환성과 Stable API 다운로드는 확인. 실제 컴파일은 JDK·Gradle 부재로 미실행.** 빌드 성공 또는 스택 비호환으로 판정하지 않음.

## 확인 범위별 결과

| 범위 | 결과 | 한계 |
| --- | --- | --- |
| 공식 문서 | Paper의 Kotlin DSL·compileOnly·Java 25 설정, Gradle의 Java 25 지원 9.1.0 이상 확인 | 현재 호스트 빌드 증거가 아님 |
| Maven 메타데이터 | 26.2 Stable 버전 48개 확인, 확인 시 가장 높은 빌드 `26.2.build.134-stable` 선택 | `26.2-R0.1-SNAPSHOT`이나 동적 `+`로 대체하지 않음 |
| API 다운로드 | POM·module·JAR HTTP 200, 실제 내용 확인 | Gradle의 전체 전이 의존성 해결은 미실행 |
| 임시 프로젝트 | `/tmp/hearth-paper-build-18zrg9zn`에 최소 Java 플러그인·Kotlin DSL·plugin.yml 구성 | 구문 평가·컴파일·서버 로드는 미검증 |
| compileJava·jar | 미실행: 도구 사전 조건 미충족 | 실패한 컴파일 로그나 성공 출력은 없음 |
| 플러그인 산출물 | `build/libs/*.jar` 없음, JAR 내부 plugin.yml 포함 여부 미검증 | 내려받은 Paper API JAR은 생성한 플러그인 JAR이 아님 |

## 호스트 도구와 오류

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

## 임시 프로젝트와 재실행 기준

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

재개에 필요한 것은 **JDK 25(java·javac·jar 포함)**와 **Gradle 9.1.0 이상**입니다. 이번에는 임의 설치하지 않았으며 설치가 필요하면 별도 승인 범위를 정해야 합니다. toolchain 자동 다운로드는 꺼 두었고 resolver 플러그인을 넣지 않았습니다.

도구가 준비된 뒤 버전·경로를 확인하고 임시 프로젝트에서 다음 명령을 실행할 계획입니다. **아래 명령은 이번에 실행하지 않았습니다.**

```bash
GRADLE_USER_HOME=/tmp/hearth-paper-build-18zrg9zn/gradle-user-home gradle --no-daemon --console=plain compileJava jar
jar tf build/libs/hearth-paper-verification-0.0.1.jar
```

성공 조건은 실제 사용한 Gradle·Java compiler 버전 기록, compileJava·jar 성공, 결과 JAR 안의 `org/hearth/verification/VerificationPlugin.class`와 `plugin.yml` 확인, 메인 클래스·api-version 대조입니다. `compileOnly` Paper API가 출력에 묶이지 않았는지도 확인합니다. Gradle 의존성 해결·컴파일 오류가 발생하면 원인과 로그를 남기고 성공으로 표시하지 않습니다. 서버 실행·런타임 호환·게임 기능·성능은 이 검증의 성공 조건 밖입니다.

## 공식 문서와 프로젝트 결정

- [Paper 프로젝트 설정](https://docs.papermc.io/paper/dev/project-setup/): Kotlin DSL, compileOnly, Java 25 toolchain과 고정 Stable 좌표 형식. 현재 예시의 26.3을 이번 대상인 26.2 대신 사용하지 않았습니다.
- [Gradle Java 호환성 표](https://docs.gradle.org/current/userguide/compatibility.html): Java 25 toolchain 및 Gradle 실행 지원은 9.1.0부터입니다.
- [Paper plugin.yml](https://docs.papermc.io/paper/dev/plugin-yml/): resources 위치와 main·api-version 필드 기준. 작성만 했으며 패키징·로딩을 검증하지 않았습니다.
- [ADR-0002](decisions/0002-stack-and-world-policies.md)의 설계는 변경하지 않습니다. 이번 기록은 도구 준비 상태와 다운로드 확인의 부분 검증이며 구현 승인·운영 승인·빌드 성공을 뜻하지 않습니다.
