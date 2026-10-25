# S4-1 최소 Hearth 플러그인·독립 월드 검증

- 작업일: 2026-10-25~26 (Asia/Seoul), 기록일 2026-10-26. 기준 커밋 `213194226c367d35429d5a23f04b1ac90709a3ae`. 시작 시 main·작업 트리/스테이징은 깨끗했고 fetch 후 origin/main과 일치했습니다.
- **S3 설계 완료 유지·S4 진행 중. S4-1 빌드/파일 및 최소 런타임 smoke 시험 통과**입니다. 최초 작업은 EULA 응답 대기로 중단했고, 2026-10-26 사용자 명시 동의 후 `fb58dbb613fc7a64b94b49e5af7ef936c8d2eb9f`에서 재개했습니다. 재개 시 main·작업 트리/스테이징은 깨끗했고 fetch 후 origin/main과 일치했습니다. `eula=true`는 새 Hearth 격리 시험 서버에만 적용했습니다. 실제 플레이어 이벤트·전체 기능·부하 검증 완료는 아닙니다.
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
| `TemplateStoreTest` | 합성 파일의 복사·A/B 독립·기존 Paper UUID 메타데이터 보존·해시 불일치/겹침/링크/소스 없음 거부와 원본/다른 사본 보존. 실제 Minecraft region 구문 시험은 아님 |
| [실행 도구](../scripts/verify-s4-1.py)·[아티팩트 목록](../verification/s4-1-artifacts.json) | 공식 해시 검증·새 격리 서버 경로 생성·명시 EULA·로컬 포트·자원/시간 제한·콘솔 자동 시험·종료/로그/자원 기록. 서버를 자동 상시 운영하거나 기존 경로를 재사용하지 않음 |

Paper 26.2의 [key 기반 WorldCreator](https://jd.papermc.io/paper/26.2/org/bukkit/WorldCreator.html)와 [level directory](https://jd.papermc.io/paper/26.2/org/bukkit/Server.html)를 기준으로 새 물리 식별자는 Paper가 생성하도록 했습니다. 실제 `getWorldPath()`와 예상 dimensions 경로가 다르면 시험을 중단하며 조용히 다른 경로에 복사하지 않습니다. 이번 실행에서 `server/world/dimensions/hearth_verify/{template_v1,a,b}`와 독립 UUID를 확인했습니다. 26.2는 `uid.dat` 대신 `data/paper/metadata.dat`에 UUID를 저장합니다([고정 Paper 소스](https://github.com/PaperMC/Paper/blob/3e24be0/paper-server/src/main/java/io/papermc/paper/world/saveddata/PaperWorldMetadata.java)). 준비 표식/메타데이터/필수 region 없이는 로드를 거부하고 로드 후 UUID/key도 비교합니다. marker/키 경로는 합성 시험 한정이고 일반 스토리 템플릿 manifest 표준을 확정하지 않습니다.

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

최종 Gradle 검사 종료 코드 0: `compileJava`, `jar`, `test`, `check` 성공. JUnit 시험 1개·실패/오류/skip 0. JAR은 `build/libs/Hearth-0.1.0-SNAPSHOT.jar`이며 메인 클래스 major 69·plugin.yml 포함, Paper/WG/WE 클래스 미포함을 확인했습니다. 재개 후 최종 JAR SHA-256은 `35865d106dfeeb0f72716ffb3ccbb7600285c172a62f7eaa67c6c5cef245b174`입니다. 이전 빌드 해시는 `fb58dbb`에 보존됩니다. Python 구문·도움말 검사는 통과했고, 최초 작업에서 `--accept-eula` 없는 호출은 종료 코드 2로 거부·서버 디렉터리 미생성을 확인했습니다.

초기 오류도 구분합니다. 시스템 Java 명령 부재로 Gradle을 바로 실행하지 못했고, sandbox의 native cache/로컬 소켓·DNS 제약은 명령별 JDK/격리 캐시와 승인된 외부 실행으로 해결했습니다. 초기 `check`는 일반 JavaExec 검사와 Gradle 기본 test 발견 설정이 달라 실패했고, JUnit Platform 설정으로 수정한 후 실제 `test/check`를 통과했습니다. 검사 비활성화나 보안 Hook 우회는 하지 않았습니다.

## 런타임 시도와 수정 이력

이전 로그·월드를 덮어쓰지 않고 매 시도 새 `/tmp/hearth-s4-1.*` 경로를 만들었습니다. 모든 시도의 `eula=true`는 사용자 동의 후 Hearth 시험 경로에만 생성했습니다.

| 시도 경로 | 실제 결과 | 수정·대응 |
| --- | --- | --- |
| `/tmp/hearth-s4-1.RSHqyT` | 플러그인 3개 로딩 성공, 콘솔 명령 거부로 smoke 미완료. JVM exit 143 | `default: false` 권한 때문에 콘솔에서도 명령이 숨겨짐. 해당 시험 JVM만 SIGTERM 종료, 저장/종료 로그 보존. 권한을 `op`로 수정하되 실행기 내부 ConsoleCommandSender·격리 게이트는 유지. harness는 이후 같은 거부를 감지하면 즉시 stop하도록 수정 |
| `/tmp/hearth-s4-1.ggm_t22k` | 생성/공간 복사 후 재로드 사전 검사 실패. harness exit 1, JVM 정상 stop/exit 0 | 구형 `uid.dat` 가정을 실제 26.2 메타데이터 경로로 수정. 파일 시험에서도 해당 메타데이터가 복사로 덮어써지지 않음을 확인 |
| `/tmp/hearth-s4-1.2vk2kre3` | A/B 블록 분리·중복 할당 거부·WG manager 분리 통과 후 BUILD 판정 오류. harness exit 1, JVM stop/exit 0 | `BUILD`에는 non-null 주체가 필요함. [WG RegionAssociable](https://github.com/EngineHub/WorldGuard/blob/7.0.19/worldguard-core/src/main/java/com/sk89q/worldguard/protection/association/RegionAssociable.java)와 [Association](https://github.com/EngineHub/WorldGuard/blob/7.0.19/worldguard-core/src/main/java/com/sk89q/worldguard/domains/Association.java)에 따른 명시적 합성 NON_MEMBER 주체 사용. 실제 플레이어 보호 검증으로 확대하지 않음 |
| `/tmp/hearth-s4-1.tqzurv7b` | `HEARTH_S4_RESULT PASS`, harness/JVM exit 0, 약 21.09초 후 정상 stop | 아래 전체 합성 smoke 경로 통과. 기존 실패 시도는 성공으로 다시 표시하지 않음 |

최종 실행 명령:

```sh
python3 scripts/verify-s4-1.py --workspace /tmp/hearth-s4-1.tqzurv7b --java /home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/java --plugin build/libs/Hearth-0.1.0-SNAPSHOT.jar --accept-eula
```

최종 로그의 PASS는 템플릿 저장/언로드, 식별자 분리, 같은 공간 복사, STONE 초기 내용, A GOLD_BLOCK 변경 후 B STONE 유지, 중복 생성 거부, WG manager 분리·같은 probe ID의 A deny/B allow, WG 저장 완료, A/B 저장·언로드·재로드 후 블록/UUID 보존, WG 디스크 재조회 후 flags 유지, 오류 후 타 월드 보존, 템플릿 전후 해시 일치입니다. 14개 PASS 출력 중 flags 확인은 저장 전후 두 번입니다. WG 파일도 `plugins/WorldGuard/worlds/hearth_verify_a/regions.yml`과 `hearth_verify_b/regions.yml`로 분리됐습니다. Hearth 직접 블록 변경이 WG를 자동 준수한다고 가정하지 않습니다.

[요약 증거 JSON](../verification/s4-1-runtime-result.json)에 정확 버전·JAR/로그 해시·식별자·시도별 결과·관측 자원을 기록했습니다. 전체 console.log, run-result.json, resource-samples.json, smoke-result.txt와 월드는 각 격리 경로에 보존하며 Git에는 넣지 않습니다. `/tmp` 자료의 장기 보존은 보장하지 않습니다. Paper bootstrap이 추가 Mojang/라이브러리를 다운로드했으며 이 전이 파일 전부의 별도 공식 해시 목록을 생성한 것은 아닙니다.

최종 로그에는 비대화형 터미널, JOML Unsafe deprecation, 고정 Paper 빌드의 최신 버전 대비 지연, 자동 저장 설정 중 플러그인 수동 저장의 성능 경고가 있었습니다. 이를 숨기거나 버전을 임의 상향하지 않았습니다. 최종 smoke의 ERROR/실패 출력은 없으나 수동 save 비용·일반 플레이 자동 저장 조정·향후 Java 호환성은 후속 확인 대상입니다.

## 수용 기준별 상태

| 항목 | 상태 | 근거·남은 확인 |
| --- | --- | --- |
| Hearth 정상 빌드/JAR | 통과 | Java 25·Gradle 9.1.0 compileJava/jar/test/check, class/resource/compileOnly 패키징 확인 |
| Paper에서 Hearth/WG/WE 로딩 | 최소 조합 통과 | Paper 26.2-134, WG 7.0.19, WE 7.4.4 활성화·정상 종료. 다른 버전·WE 편집 기능은 미시험 |
| 단일 프로세스 A/B 생성·물리 ID 분리 | 합성 시험 통과 | 서로 다른 story_run_id/key/UUID/path. 동일 synthetic-v1 공간 복사/초기 블록 확인. DB 매핑·엔티티 복제는 미시험 |
| A 변경의 B 비간섭 | 블록·파일 시험 통과 | A GOLD_BLOCK/B STONE, 원본 공간 체크섬 유지. 플레이어·엔티티·NPC·전체 데이터 격리는 미시험 |
| 저장/언로드/재로드 후 유지 | 같은 프로세스 smoke 통과 | unload 성공·Bukkit 조회 제거, reload 뒤 UUID/key·A/B 블록 보존. 프로세스 재시작/재접속/IDLE는 미시험 |
| WG 월드별 구역 분리·저장 | 구역 API smoke 통과 | 별도 manager/YAML, 같은 probe ID의 반대 BUILD flags, 저장/언로드/재로드/명시적 디스크 load 후 유지. 실제 플레이어 이벤트·전체 W1~W13은 미시험 |
| 실패 시 원본/타 월드 보존 | 파일 거부·중복 할당 통과 | 원본/B 해시 및 블록 유지, 기존 키 덮어쓰기 거부. 저장 실패/디스크 장애 주입·장애 중 타 월드 계속 동작은 미시험 |
| 자원·누수 | 짧은 관측 완료 / 부하 미검증 | 최대 표본 RSS 921.09MiB, CPU 약 41.52초. 실제 플레이어/동시 이용량·TPS/MSPT·힙/GC·반복 언로드 누수는 미시험 |

## 자원 예산·종료 확인

재개 전 RAM 총 15,442MiB·가용 13,087MiB·swap 사용 0, 부하 0.00/0.01/0.02, /tmp 가용 약 63GiB였습니다. 첫 작업의 사전 가용 12,824MiB·빌드 후 13,027MiB 관측은 `fb58dbb`에 보존합니다.

실행 조건: 사용자 소유 새 server 디렉터리·127.0.0.1:25585·whitelist/online-mode·RCON/query 꺼짐. JVM Xms256MiB/Xmx1536MiB·ActiveProcessorCount=2·nice+10, 최대 240초와 종료 유예. 시작/실행 중 가용 메모리 6GiB 이상·시작 디스크 4GiB 이상, RSS 2.5GiB 초과/시간 초과 시 해당 시험 프로세스만 종료합니다. ActiveProcessorCount는 JVM 가용 CPU 힌트이며 OS의 하드 CPU quota가 아닙니다. RSS 제한은 표본 감시입니다. 기존 서비스는 조작하지 않습니다.

최종 실행은 182표본·21.09초, 최대 RSS 943,200KiB(921.09MiB), 최소 호스트 가용 12,164.29MiB, 표본 CPU 누적 약 41.52초였습니다. CPU는 여러 스레드의 합이므로 벽시계 시간보다 큽니다. 시작 준비 로그는 10.132초입니다. 짧은 무접속 생성/저장 시험으로 수용량이나 기존 서비스 무영향을 보장하지 않습니다. 종료 후 가용 13,064MiB·swap 0, 시험 JVM 잔존 없음·25585 리스닝 없음 확인. 상시 운영하지 않습니다.

## 남은 범위

이번 최소 시험은 합성 공간·콘솔/WG API 경로입니다. 실제 플레이어 입장/이동·블록 이벤트 보호, 엔티티 UUID 처리/일반 콘텐츠 템플릿, NPC/퀘스트/DB 격리는 미구현·미검증입니다. 일반화된 수명주기·프로세스 재시작 복원·IDLE 언로드·참조 회수/부하·전체 보호 시험·부분 실패 주입과 Multiverse 비교도 후속입니다. 최소 로딩·블록 보존 성공을 운영용 진행 관리자·완전한 복제/자동 복구·전체 S4 완료로 간주하지 않습니다.

## 문서·산출물 검사

2026-10-26: 추적 Markdown 36개·상대 링크 351개(앵커 2개 포함)·표 87개 검사 오류 0. 실행 요약/JAR/보존 로그 해시·공식 배포물 해시·JUnit XML을 대조했습니다. Python 구문 검사·`git diff --check`·staged diff 검사 통과, Gitleaks staged 검사에서 유출 없음. 전체 월드/배포 JAR/원본 로그는 스테이징하지 않습니다. Git 보안 Hook을 유지하며 commit/push 결과는 Git 이력과 작업 보고로 확인합니다.

## 후속 S4-2 기록

2026-10-26 [S4-2](s4-2-verification.md)에서 새 JVM의 최소 월드/구역 복원과 자연 피스톤 이벤트를 확인했습니다. 이 문서의 미실행은 S4-1 당시 범위이며 후속 결과와 구분합니다. 기존 S4-1 원본 로그/결과는 보존했고 새 JAR의 S4-1 smoke도 회귀 통과했습니다. 실제 플레이어 행동·전체 수명주기/부하/DB는 계속 미검증입니다.
