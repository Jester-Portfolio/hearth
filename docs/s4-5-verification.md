# S4-5 — Paper·PostgreSQL 통합과 소규모 안정성 검증

- 확인일: **2026-10-26 (Asia/Seoul)**.
- 시작 기준: `dd6436dd8250e9d58d5e9d5c274c8bf0b0d083af`. 깨끗한 main·스테이징을 확인했고 `git fetch origin` 후 origin/main과 일치했습니다. 결과 커밋은 이 문서의 Git 이력으로 확인합니다.
- 결과: **실제 Paper 두 JVM·신규 PostgreSQL 한 개의 통합 시험 통과. S4 전체는 진행 중**입니다. 실제 플레이어·운영 배포·전체 게임 기능의 성공을 뜻하지 않습니다.
- 근거: [S4-1](s4-1-verification.md)~[S4-4](s4-4-verification.md), [ADR-0001](decisions/0001-story-world-multiworld.md)·[0002](decisions/0002-stack-and-world-policies.md)·[0003](decisions/0003-inventory-performance-and-recovery.md)·[0012](decisions/0012-progress-storage-world-lifecycle-and-content.md). 기존 저장 모듈과 월드 재시작 시험을 재사용하며 새로운 게임 정책이나 가입·탈퇴 기능을 추가하지 않았습니다.

## 1. 구현 범위와 활성화 경계

- [DatabaseVerification](../src/main/java/io/github/jesterportfolio/hearth/verification/DatabaseVerification.java): 콘솔 시험 전용 Paper–저장 모듈 어댑터입니다. `verification-enabled`와 `db-verification-enabled`는 기본 false입니다. 둘 다 true이고 전용 JVM 플래그·일치하는 실제 작업 경로·소유자·격리 표식·새 시험 DB 식별이 맞을 때만 활성화됩니다.
- [HearthPlugin](../src/main/java/io/github/jesterportfolio/hearth/HearthPlugin.java): 기존 격리 게이트에 S4-5를 추가하고 `hearthverify db <시험 단계>` 경로만 연결했습니다. 임의 DB URL·계정·SQL을 콘솔에서 받지 않으며 DB endpoint의 포트와 자기 data 경로의 postmaster 식별을 대조합니다. 일반 부팅에서 임의 PostgreSQL에 자동 연결하지 않습니다. 기본 비활성 설정은 정적 검사했으며 별도의 일반 모드 Paper JVM은 실행하지 않았습니다.
- [기존 저장 모듈](../src/main/java/io/github/jesterportfolio/hearth/persistence/): worker·풀·revision CAS·복합 개인 키·오류 전달을 유지했습니다. `SchemaMigration`의 기존 버전 경로에 필수 테이블/열 조회 검사를 추가했습니다. 버전 행이 정상이어도 테이블이 없으면 실패하며 자동 재생성하지 않습니다. 모든 카탈로그 타입·제약의 완전한 drift 검사는 아닙니다. V001 SQL 자체는 변경하지 않았습니다.
- [DB 보조 도구](../src/integrationTest/java/io/github/jesterportfolio/hearth/persistence/S45DatabaseFixture.java): 기존 S4-4의 Zonky 기반 실행 구성을 재사용해 신규 DB만 준비합니다. 고정된 잠금·쓰기 실패·스키마 불일치 주입과 정상 DB 종료를 제공합니다. 운영용 실행기·마이그레이션 서비스가 아닙니다.
- [실행 도구](../scripts/verify-s4-5.py): 기존 S4-2의 가용 RAM/포트 검사와 S4-4의 핵심 해시·소유 DB 정리 검사를 재사용합니다. Gradle `stageS45Runtime`은 보조 클래스/시험 의존성을 복사하며 DB를 실행하지 않습니다.

PostgreSQL 모듈은 계속 `story_run_id`와 `(story_run_id, player_uuid)`만 소유권으로 사용합니다. 시험 컨텍스트는 서버 내부에서 생성하며 네트워크 입력·실제 로그인·멤버십과 연결하지 않았습니다. 원래 월드 생성·저장·복원 Java 코드는 변경하지 않았습니다.

## 2. 실제 런타임 버전과 의존성

| 구성 | 실제 시험 값·확인 |
| --- | --- |
| Paper / Java / Gradle | Paper 26.2 build 134 / Temurin 25.0.5+8 / Gradle 9.1.0 |
| 보호 플러그인 | WorldGuard 7.0.19 / WorldEdit 7.4.4, 두 JVM 활성화·기존 구역/피스톤 회귀 통과 |
| 저장 라이브러리 | pgJDBC 42.7.14 / HikariCP 7.1.0, Paper libraries가 해석한 JAR 출처와 기존 검증 SHA-256 일치 |
| 전이 의존성 | Paper 로더가 checker-qual 3.55.1·slf4j-api 2.0.17도 해석함 |
| 실제 SLF4J 클래스 출처 | 서버가 제공한 slf4j-api **2.0.18**. 해석된 2.0.17과 실제 사용 출처를 구분 |
| 독립 DB | PostgreSQL 18.3, 프로세스 기동 1회. 운영 PostgreSQL 버전 채택이나 DB 재시작 검증이 아님 |

[Paper 공식 libraries 문서](https://docs.papermc.io/paper/dev/plugin-yml/#libraries)에 따른 선언을 그대로 사용했습니다. pgJDBC/HikariCP JAR을 플러그인에 넣거나 임의 클래스패스로 우회하지 않았습니다. 기존 Paper 부트스트랩 다운로드 캐시만 복사하고 pgJDBC는 없는 상태에서 첫 Paper를 시작했습니다. 로더의 다운로드/해석 로그와 클래스 `CodeSource`를 확인했습니다. [JDBC 공식 연결 문서](https://jdbc.postgresql.org/documentation/use/)를 참고하되 실제 연결은 시험 전용 `PGSimpleDataSource`를 사용했습니다.

두 JVM에서 클래스 누락·로더 충돌·링크 오류 없이 저장 기능이 실행됐습니다. Hikari 패키지의 `Implementation-Version`은 null이었으므로 이 값으로 버전을 추정하지 않았습니다. `HikariCP-7.1.0.jar` 출처·파일 해시로 확인했습니다. SLF4J 두 버전의 해석/위임이 이번 조합에서 동작했다는 범위이며 다른 버전·플러그인 전체에 대한 호환 보장은 아닙니다.

## 3. 시험 절차와 데이터 영속성

시험 경로는 신규 사용자 소유 `/tmp/hearth-s4-5.k9e6fjlb`입니다. 기존 S4-1~4 서버/DB/증거는 덮어쓰지 않았습니다. 고정 Paper·WG·WE 아티팩트는 기존 [공식 해시 기록](../verification/s4-1-artifacts.json), DB 시험 의존성은 [S4-4 체크섬 경계](s4-4-verification.md)에 따라 재대조했습니다. 캐시·라이브러리 복사는 실행 파일 재사용이며 기존 월드/설정을 가져온 것이 아닙니다.

1. 새 PostgreSQL DB `hearth_s4_5`를 만들고 loopback 임시 포트 **40159**로 시작했습니다. 최초 클러스터 생성 이후 재시작하지 않았습니다.
2. 첫 Paper에서 기존 `restart prepare`로 A/B 월드를 생성·저장했습니다. DB 모듈은 마이그레이션 전 요청을 거부한 뒤 V001과 진행/개인 상태를 저장했습니다.
3. 저장값과 revision 확인 후 DB 풀을 비동기 정리하고 Paper를 정상 종료했습니다.
4. 동일 DB를 유지한 채 새로운 Paper JVM에서 기존 월드와 DB를 조회했습니다. resume 경로에는 진행 생성 호출이 없고 누락 진행도 빈 행으로 대체하지 않았습니다.
5. 같은 두 번째 JVM에서 아래 대표 오류를 시험하고, 정상 풀 종료 및 연결 중단 처리를 확인했습니다. 마지막에 두 번째 Paper와 DB 보조 JVM을 종료했습니다.

| 상태 | 첫 JVM 저장 결과 | 두 번째 JVM 복원 결과 |
| --- | --- | --- |
| A 공유 / B 공유 | `shared-a` / `shared-b`, 각각 revision 1 | 동일 |
| P1의 A / P1의 B 개인 | `personal-a1` / `personal-b1`, 각각 revision 1 | 동일 |
| P2의 A 개인 | `personal-a2`, revision 1 | 동일 |
| 같은 A의 P1/P2 공유 조회 | 동일 공유 값 | 동일 공유 값 |

A/B 논리 ID는 각각 `00000000-0000-002a-0000-00000000000a` / `00000000-0000-002a-0000-00000000000b`입니다. 기존 재시작 매핑의 논리 진행과 맞는지 두 JVM에서 검사했습니다. 물리 월드 UUID는 각각 `b56c647b-c7c8-49d1-a326-c95487e0a2a8` / `e19265f9-080e-4fd2-9e4e-64654ac6ff7d`이며 키·블록·구역과 함께 보존됐습니다. 물리 UUID나 키로 개인 데이터 소유권을 선택하지 않았습니다.

## 4. 실제 시험과 단위 시험의 구분

| 항목 | 실행 범위·실제 결과 |
| --- | --- |
| 정상 로딩·라이브러리 해석·비동기 초기화 | 실제 Paper 두 JVM + PostgreSQL 통과 |
| 마이그레이션 전 요청 | 실제 Paper의 읽기 요청 `SCHEMA_NOT_READY`. 생성·공유/개인 저장 차단은 추가 단위 시험에서 JDBC 호출 0으로 확인. 사전 쓰기 요청의 Paper 런타임 시험은 별도 미실행 |
| 공유/개인 상태·revision의 Paper 재시작 영속성 | 실제 두 JVM·동일 DB에서 통과. PostgreSQL 프로세스 재시작은 미실행 |
| 오래된 revision·중복/동시 저장 | 실제 Paper + DB에서 같은 revision 두 요청 중 하나만 성공. 이전 revision 거부, A는 `winner`/revision 2, B 불변 |
| 잘못된 컨텍스트·없는 진행 | 실제 Paper에서 외부 발급 컨텍스트 `ACCESS_DENIED`, 없는 진행 `MISSING_STATE`. 자동 생성 없음 |
| DB 쓰기 실패 | 신규 시험 DB의 trigger로 `23514` 주입. 실패 기록·실패 future, 개인 값/revision 불변 |
| 스키마 불일치 | 시험 체크섬 변경 후 `SCHEMA_VERSION_MISMATCH`, 준비 완료 해제. 시험 도구가 원래 체크섬을 복원 |
| 필수 테이블 누락 | 개인 테이블 이름을 잠시 변경한 뒤 migrate가 `42P01`로 실패. 대체 테이블이 없음을 확인한 뒤 원래 이름으로 복원 |
| 대기열 초과 | 공유 테이블을 잠근 상태에서 읽기 40개 제출. worker 2·대기열 16 범위의 18개 처리, 나머지 22개 `QUEUE_FULL`. 잠금 해제 후 정상 요청 완료 |
| 종료 중 신규 요청·기존 작업 | 읽기 2개가 잠금을 기다리는 중 `closeAsync`, 새 요청 `CLOSED`. 잠금 해제 후 기존 읽기 완료·풀 종료 확인 |
| 실제 DB 연결 중단 | 접속된 별도 시험 풀이 있는 상태에서 이 DB만 정상 종료. 읽기 `57P01`·오류 기록, 풀 정리. 성공 오기록 없음 |
| 메인 스레드 안전성 | DB 결과를 큐에만 전달하고 Paper task가 메인 스레드에서 처리. 모든 관측 콜백 `main=true`, DB 잠금 대기 중 메인 tick 진행 |
| 무효화된 시험 세대 | 실제 DB 읽기 요청 뒤 서버 내부 세대 변경. 늦은 결과 `STALE_CONTEXT`로 적용 거부 |
| 장시간 연결 대기 자체의 비차단 | 기존 단위 시험의 차단 DataSource로 확인. 이번 런타임에서는 SQL 잠금 대기·연결 중단을 시험했으며 긴 연결 대기를 별도로 유도하지 않음 |
| 단위·파일 회귀 | Gradle build/test/check 통과. 기존 파일 시험 1개·저장 단위 시험 4개, 실패 0 |
| 기존 월드·WorldGuard 회귀 | 동일 두 Paper JVM에서 기존 restart prepare/resume·UUID/key/블록/구역 저장·자연 피스톤 경로 통과. 실제 플레이어 검증으로 대체하지 않음 |

revision 비교는 중복된 기대 버전의 잘못된 재적용을 막는 최소 저장 방식입니다. 거래 요청 ID 기반 정확히 한 번 실행이나 모든 장애 복구를 구현한 것은 아닙니다. 세대 검사는 **서버 내부 시험 컨텍스트의 결과 적용**을 검사한 것이며 실제 플레이어 세션 관리자가 아닙니다. 이미 완료된 DB 쓰기를 세대 변경으로 되돌리는 기능도 아닙니다.

정상 종료는 이번 콘솔 시험에서 **기존 요청 정리·풀 종료 완료 확인 후 stop**하는 경로를 검증했습니다. `onDisable`도 같은 비동기 종료를 요청하며 두 JVM 모두 종료 성공 표식을 확인했습니다. 메인 스레드에서 DB future를 동기 대기하지 않았습니다. 임의 시점 플러그인 강제 비활성화·지연된 종료/commit 불명 등 전체 장애 경계가 검증됐다는 뜻은 아닙니다.

## 5. 실행·자원 관측과 정리

```bash
env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-s4-1.RSHqyT/gradle-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --offline --console=plain build test check stageS45Runtime -Ps45Workspace=/tmp/hearth-s4-5.k9e6fjlb
python3 scripts/verify-s4-5.py --workspace /tmp/hearth-s4-5.k9e6fjlb --java /home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/java --plugin build/libs/Hearth-0.1.0-SNAPSHOT.jar --artifact-source /tmp/hearth-s4-2.vnd2kki4 --accept-eula
```

위 경로는 보존된 증거이므로 같은 경로에 재실행하면 안 됩니다. 최초 stage 준비 이후 관측 코드 보완에 따른 `build test check`도 다시 통과했습니다. PostgreSQL 별도 S4-4 통합 시험은 반복하지 않았습니다. 명시 승인된 Hearth 격리 EULA 범위만 사용했습니다.

- 전체 시험: **46.133초**, 정상 종료 3개 JVM, PostgreSQL 기동 1회. 작업 예산 430초와 정리 예산 50초를 포함한 480초 상한 안에서 종료했습니다.
- 순차 Paper 최대 2회, 힙 1536MiB, RSS 중단 기준 2.5GiB, ActiveProcessorCount=2·nice +10. 호스트 가용 RAM 6GiB·디스크 4GiB 사전 검사와 실행 중 RAM 관측을 적용했습니다. 한도를 넘겨 재시작하거나 확대하지 않았습니다.
- PostgreSQL은 S4-4와 같은 `shared_buffers=16MB`, `work_mem=1MB`, `max_connections=12`, `max_parallel_workers=0`, `fsync=on`, `synchronous_commit=on`입니다. 기본 시험 trust 인증·자기 Unix socket을 사용하며 외부 공개나 운영 인증 설정은 없습니다.

| 관측 대상 | 준비 완료까지 | 최대 표본 RSS | 관측 CPU 시간 | stop 후 종료까지 |
| --- | --- | --- | --- | --- |
| 첫 Paper PID 1001664 | 14.225초 | 1,194.50MiB | 47.81초 | 3.651초 |
| 두 번째 Paper PID 1001864 | 8.989초 | 946.60MiB | 37.69초 | 3.508초 |
| DB 보조 Java PID 1001600 | DB 시작 표식 확인, 별도 시간 미집계 | 214.99MiB | 2.35초 | 0.005초 |

CPU는 `/proc`의 user+system ticks를 마지막 표본까지 집계한 값이며 여러 스레드 합산이라 벽시계 시간보다 클 수 있습니다. 폴링 간격에 따른 오차가 있습니다. DB 보조 JVM 수치는 PostgreSQL postmaster·worker 전체의 RSS/CPU가 아닙니다. PostgreSQL 자체 자원 총량은 별도로 측정하지 않았습니다. 호스트 가용 RAM 최소 표본은 약 **11.336GiB**였습니다.

마이그레이션 초기화 관측 98ms, 주 저장·읽기 결과의 메인 스레드 전달은 주로 49ms였습니다. 잠긴 읽기 대기열은 647ms, 종료 시 대기 작업은 650ms 관측됐습니다. 이 값은 DB·worker·큐·메인 tick 전달을 포함하며 순수 SQL 실행 시간/운영 지연 보장이 아닙니다. TPS/MSPT·장시간 부하·누수는 측정하지 않았습니다.

두 Paper JVM과 DB 보조 JVM 모두 exit 0, PostgreSQL PID 1001649 종료, process group 잔존 없음과 `postmaster.pid` 제거를 확인했습니다. loopback 25585/40159의 재바인딩 검사도 통과했습니다. 추가 확인은 샌드박스의 소켓 제한으로 한 번 차단됐지만 승인된 실행 환경에서 재확인했습니다. 새 Paper/DB를 추가 기동한 것이 아닙니다. 데이터·로그·원시 자원 표본은 삭제하지 않고 시험 경로에 보존합니다.

## 6. 오류·경고·미실행과 후속 범위

- 실행 전 Gradle 경로 검사에서 `File.parent` 문자열과 File 비교 오류가 발생했습니다. `parentFile`로 수정 후 빌드·단위 시험을 통과했으며 이 실패 시점에는 서버/DB를 시작하지 않았습니다.
- 실제 시험 실패는 없었습니다. `23514`·`42P01`·`57P01`·revision/큐/종료/접근 오류는 의도적 실패 입력을 올바르게 거부한 결과입니다. 무시하거나 성공 응답으로 바꾸지 않았습니다.
- 비대화형 터미널·JOML Unsafe·고정 Paper 버전 업데이트·시험 수동 저장 경고를 보존했습니다. 의도적 DB 종료 시 Hikari의 `57P01` 연결 폐기 경고도 예상 결과입니다. 사용자가 지정한 버전을 임의 변경하지 않았습니다.
- 실제 인증 클라이언트와 정식 세션·인벤토리/30초 스케줄러·거래/보상·퀘스트/NPC/AI·IDLE·운영 배포는 제외했습니다. S4-3 실제 플레이어 시험은 계속 보류/미실행입니다.
- PostgreSQL 프로세스 재시작, 네트워크 단절 중 commit 결과 불명, 전체 스키마 drift/업그레이드, 과부하·종료 타임아웃·강제 비활성화, 장기 안정성은 미검증입니다. 해당 기능 범위를 정한 뒤 후속 시험합니다.
- 전체 인벤토리·보상/거래 저장 순서 구현과 성능 측정은 후속입니다. ADR-0003의 30초 dirty 비동기·성능 우선·관리자 수동 장애 대응을 유지하며 무손실 자동 복구를 보장하지 않습니다.

[요약 증거](../verification/s4-5-result.json)에 프로세스·값/revision·콜백/소요 시간·라이브러리 출처·로그 해시를 기록했습니다. 원시 로그/DB/바이너리·인증정보는 Git에 넣지 않습니다. 기존 Docker/Zabbix/Cloudflare/SSH·운영 DB·전역 Java·실제 게임 데이터와 비공개 스토리 저장소는 변경/접근하지 않았습니다.

## 7. 문서·정적 검사

변경 Markdown 10개·상대 링크 212개·표/제목/코드 블록 검사 오류 0건을 확인했습니다. 일본어/한자 문자 검색과 한국어 문맥 검토에서 혼입 0건입니다. Python 구문·`git diff --check`를 통과했고 실행 JAR 해시가 최종 빌드와 동일함을 재확인했습니다. 기본 DB 비활성 설정·논리 진행 소유권·Bukkit 비의존 저장 모듈·기존 월드 Java/V001 불변과 S2/S3 완료·S4 진행 중 경계도 검사했습니다. Gitleaks/커밋/푸시는 저장소 hook 정책을 따르며 실제 결과는 최종 작업 보고와 Git 이력에 남깁니다.
