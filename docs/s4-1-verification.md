# S4-1 최소 Hearth 플러그인·독립 월드 검증

- 작업일: 2026-10-25~26 (Asia/Seoul), 기록일 2026-10-26. 기준 커밋 `213194226c367d35429d5a23f04b1ac90709a3ae`. 시작 시 main·작업 트리/스테이징은 깨끗했고 fetch 후 origin/main과 일치했습니다.
- **S3 설계 완료 유지·S4 진행 중. S4-1은 빌드/파일 시험 완료, 서버 시험 미실행**입니다. 서버 실행 전 EULA 동의를 요청했으며 아직 응답을 받지 않았습니다. 미확인 동의를 추정하거나 `eula=true`로 우회하지 않았습니다.
- 기존 게임 데이터/월드·비공개 스토리 저장소에 접근하지 않았습니다. Docker/Zabbix/Cloudflare/SSH·전역 Java·시스템 패키지/서비스 설정 변경은 없습니다. PostgreSQL/NPC/퀘스트/AI/경제 구현은 포함하지 않습니다.

## 구현 범위와 선택 근거

[플러그인 검토](plugin-suitability-review.md)의 Paper API 방안을 **S4-1 비교용 어댑터**로 구현했습니다. Multiverse-Core는 의존하지 않으며 최종 월드 관리 구현 선택을 의미하지 않습니다. WorldGuard 기본 보호 채택은 ADR-0014 그대로입니다.

| 파일/책임 | 구현된 범위·제한 |
| --- | --- |
| 루트 Gradle Kotlin DSL·Java 소스/리소스 | Java 25 toolchain·고정 compileOnly API, 단일 Hearth JAR. JDK 자동 다운로드 금지·Gradle worker 2/빌드 heap 384MiB. Gradle 9.1.0 외부 설치 사용, wrapper는 추가하지 않음 |
| `HearthPlugin` | 최소 활성화·기본 비활성 시험 기능. 설정+JVM 경로+격리 표식+콘솔 명령을 모두 요구. 일반 플레이어 명령/가입 관리 기능 없음 |
| `WorldBackend` / `PaperWorldBackend` | 논리 story_run_id와 물리 key/UUID/path 분리, 신규 할당·저장/언로드·재로드 경로. Paper 안전한 서버 스레드·틱 경계 검사, 기존 키/데이터 덮어쓰기 거부·언로드 결과 확인. 최소 시험용 매핑이며 생산용 DB/세션 복원/유휴 스케줄러 미구현 |
| `TemplateStore` | 언로드된 합성 v1 템플릿의 region/entities/poi만 실제 복사. 키/UUID·playerdata를 복제하지 않음. 스트리밍 체크섬·링크/겹침/불완전 입력 거부. 실패 대상은 보존하고 후속 로드를 진행하지 않음 |
| `SmokeVerification` | 준비→A/B 각각 식별자 할당/언로드→동일 공간 복제→로드/한쪽 블록 변경→WG 동일 구역 ID의 다른 flags→구역 저장→월드 저장/언로드/재로드→구역 디스크 재조회→원본 보존 검사 흐름. 파일/WG blocking I/O는 별도 worker, 서버 스레드에서 future 대기 없음 |
| `TemplateStoreTest` | 합성 파일의 복사·A/B 독립·기존 UUID 파일 보존·해시 불일치/겹침/링크/소스 없음 거부와 원본/다른 사본 보존. 실제 Minecraft region 구문 시험은 아님 |
| [실행 도구](../scripts/verify-s4-1.py)·[아티팩트 목록](../verification/s4-1-artifacts.json) | 공식 해시 검증·새 격리 서버 경로 생성·명시 EULA·로컬 포트·자원/시간 제한·콘솔 자동 시험·종료/로그/자원 기록. 서버를 자동 상시 운영하거나 기존 경로를 재사용하지 않음 |

Paper 26.2의 [key 기반 WorldCreator](https://jd.papermc.io/paper/26.2/org/bukkit/WorldCreator.html)와 [level directory](https://jd.papermc.io/paper/26.2/org/bukkit/Server.html)를 기준으로 새 물리 식별자는 Paper가 생성하도록 했습니다. 실제 `getWorldPath()`와 예상 dimensions 경로가 다르면 시험을 중단하며 조용히 다른 경로에 복사하지 않습니다. 원본·A/B 식별자/공간 데이터와 WG 설정의 분리를 런타임에서 확인해야 합니다. 복제 준비 표식/UUID/필수 region이 없으면 재로드로 빈 월드를 생성하지 않습니다. marker/키 경로는 합성 시험 한정이고 일반 스토리 템플릿 manifest 표준을 확정하지 않습니다.

## 버전·다운로드 무결성

| 항목 | 실제 확인 |
| --- | --- |
| JDK | 기존 사용자 전용 Temurin 25.0.5+8-LTS, Java class major 69 |
| Gradle | 기존 9.1.0, 명령별 JAVA_HOME과 `/tmp/hearth-s4-1.RSHqyT/gradle-home` 사용 |
| 컴파일 API | `io.papermc.paper:paper-api:26.2.build.134-stable`, WG Bukkit 7.0.19, WE Bukkit 7.4.4; API는 JAR에 미포함 |
| Paper 시험 배포 | 26.2 build 134 STABLE. 공식 다운로드 API SHA-256 `3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b` 일치. 조회 시 135도 있었으나 S2 고정 API와 같은 134로 고정 |
| WorldGuard | 7.0.19+2400-f395a16. 공식 Modrinth SHA-512 일치. JAR 내부 `depend: [WorldEdit]`, `api-version: 26.2` 확인 |
| WorldEdit | 7.4.4+7546-f9e033f. 공식 Modrinth SHA-512 일치. Bukkit용·STARTUP·Vault soft dependency 확인 |

WG/WE의 공식 SHA-512 전체 값과 출처는 아티팩트 목록에 보존합니다. 추가 계산 SHA-256은 각각 `396df065f1cf38bab9878a0f53a776d5a085ebbf3c41813938b73f95d4eaca03`, `44c97ee6c1df9afa127df3c5a2c6a7108f826fb44ab7b255a7ec4250feb89b9d`입니다. 이 추가 값은 별도 공식 SHA-256 발행을 주장하지 않습니다. 빌드 의존성은 공식 HTTPS Maven에서 받았으며 별도 Gradle 의존성 검증 메타데이터는 이번에 생성하지 않았습니다. JDK/Gradle의 이전 공식 체크섬 검증 이력은 [S2 기록](stack-build-verification.md)에 있습니다. 배포물·월드·캐시·전체 로그는 Git에 넣지 않습니다.

출처: [Paper 다운로드 서비스](https://docs.papermc.io/misc/downloads-service/), [Paper 134 메타데이터](https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/134), [WG 공식 배포 메타데이터](https://api.modrinth.com/v2/version/TtfwTyi6), [WE 공식 배포 메타데이터](https://api.modrinth.com/v2/version/qNuPcliz), [WG 의존성 안내](https://worldguard.enginehub.org/en/latest/install/). 배포 존재/해시 일치는 실제 서버 호환성 성공이 아닙니다.

## 실제 명령과 결과

```sh
env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-s4-1.RSHqyT/gradle-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --console=plain check jar
env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-s4-1.RSHqyT/gradle-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --offline --console=plain check jar
python3 -m py_compile scripts/verify-s4-1.py
python3 scripts/verify-s4-1.py --help
```

최종 Gradle 검사 종료 코드 0: `compileJava`, `jar`, `test`, `check` 성공. JUnit 시험 1개·실패/오류/skip 0. JAR은 `build/libs/Hearth-0.1.0-SNAPSHOT.jar`이며 메인 클래스 major 69·plugin.yml 포함, Paper/WG/WE 클래스 미포함을 확인했습니다. 최종 JAR SHA-256은 `e2597e0f6a4d140fe7469d36cfe1b0469e790f34a36ae79f6421def1f2389985`입니다. Python 구문·도움말 검사는 통과했고, `--accept-eula` 없는 호출은 종료 코드 2로 거부·서버 디렉터리 미생성을 확인했습니다.

초기 오류도 구분합니다. 시스템 Java 명령 부재로 Gradle을 바로 실행하지 못했고, sandbox의 native cache/로컬 소켓·DNS 제약은 명령별 JDK/격리 캐시와 승인된 외부 실행으로 해결했습니다. 초기 `check`는 일반 JavaExec 검사와 Gradle 기본 test 발견 설정이 달라 실패했고, JUnit Platform 설정으로 수정한 후 실제 `test/check`를 통과했습니다. 검사 비활성화나 보안 Hook 우회는 하지 않았습니다.

## 수용 기준별 상태

| 항목 | 상태 | 근거·다음 확인 |
| --- | --- | --- |
| Hearth 정상 빌드/JAR | 통과 | Java 25·Gradle 9.1.0 실제 compileJava/jar/test/check, class/resource/compileOnly 패키징 확인 |
| Paper에서 Hearth/WG/WE 로딩 | 미실행 | EULA 응답 대기. API 컴파일/메타데이터 확인만으로 통과하지 않음 |
| 단일 프로세스 A/B 생성·물리 ID 분리 | 미실행 | smoke 흐름/식별 검사 구현. 실제 26.2 차원 경로·UUID·청크 생성 확인 필요 |
| A 변경의 B 비간섭 | 파일 단위 통과 / 게임 월드 미실행 | 합성 사본 변경은 원본/B에 영향 없음. 실제 블록/엔티티 시험은 런타임 필요 |
| 저장/언로드/재로드 후 유지 | 미실행 | API 경로 구현, 실제 writer/언로드 결과·변경/UUID 유지 관측 필요 |
| WG 월드별 구역 분리·저장 | 미실행 | 같은 probe ID에 A deny/B allow 및 manager/디스크 재조회 계획. 플레이어 이벤트·전체 W1~W13은 별도 미실행 |
| 실패 시 원본/타 월드 보존 | 파일 단위 통과 / 런타임 미실행 | 오류 입력의 원본/B 보존 확인. 실제 중복 생성 거부·저장 실패/IO 장애 주입·다른 월드 계속 동작은 후속 |
| 자원·누수 | 사전 관측만 | CPU/RAM 여유 확인. Paper 미실행으로 RSS/CPU/MSPT/TPS·반복 언로드 누수 자료 없음 |

## 자원 예산·실행 조건과 재개

사전 호스트 관측: RAM 총 15,442MiB·가용 12,824MiB·swap 사용 0, 부하 0.00/0.00/0.00. 빌드 후 관측: 가용 13,027MiB·swap 0, 부하 0.45/0.25/0.12. /tmp 가용 약 63GiB이며 TCP 리스닝 읽기 검사에서 25585는 비어 있었습니다. 이 값은 기존 서비스의 성능 무영향이나 동시 플레이 수용량 보장이 아닙니다. Paper 자원 실측은 없습니다.

준비된 실행 도구의 조건은 사용자 소유 `/tmp/hearth-s4-1.*` 하위 **새 server 디렉터리**·127.0.0.1:25585·whitelist/online-mode·RCON/query 꺼짐입니다. JVM Xms256MiB/Xmx1536MiB·ActiveProcessorCount=2·nice+10, 최대 실행 240초와 종료 유예를 둡니다. 시작/실행 중 가용 메모리 6GiB 이상·시작 디스크 4GiB 이상, RSS 2.5GiB 초과/시간 초과 시 해당 시험 프로세스만 종료합니다. console/result/resource JSON은 격리 경로에 남깁니다. 기존 서비스는 조작하지 않습니다.

실행 전 [Minecraft EULA](https://www.minecraft.net/en-us/eula) 동의가 필요하며 이번에 그 동의를 대신하지 않았습니다. 승인 응답 후 포트/자원을 재확인하고 아래 명령을 **별도 실행**할 수 있도록 준비했습니다. 아래는 미실행 명령 예시이며 실행 성공 기록이 아닙니다.

```sh
python3 scripts/verify-s4-1.py --workspace /tmp/hearth-s4-1.RSHqyT --java /home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/java --plugin build/libs/Hearth-0.1.0-SNAPSHOT.jar --accept-eula
```

동의 전에는 이 명령을 실행하지 않습니다. 실패 시 원본/로그를 보존하고 우회하지 않으며 /tmp 자료의 장기 보존은 보장하지 않습니다. 재개 시 아티팩트 해시·빌드·자원 조건도 다시 확인합니다. 생성된 시험 데이터를 자동 삭제하는 명령은 제공하지 않습니다.

## 남은 범위

실제 로딩/26.2 저장 경로·원본/두 월드·WG manager 저장/재로드·종료 결과가 첫 과제입니다. 현재는 합성 공간·콘솔/WG API 검사만 구현했으며 실제 플레이어 입장·이동·블록 이벤트 보호·NPC/퀘스트/DB 격리는 미구현·미검증입니다. 일반화된 수명주기·프로세스 재시작 복원·IDLE 언로드·참조 회수/부하·전체 보호 시험·부분 실패 주입과 Multiverse 비교도 후속입니다. 본 프로토타입을 운영용 진행 관리자나 완전한 복제/자동 복구 구현으로 간주하지 않습니다.
