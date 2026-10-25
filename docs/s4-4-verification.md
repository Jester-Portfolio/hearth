# S4-4 — PostgreSQL 진행별 데이터 격리와 저장 기반 검증

- 확인일: 2026-10-26 (Asia/Seoul).
- 시작 기준: `496d8c1b1c2fec9127d847b83a10a38edfeb8cdc`. 시작 시 main·작업 트리·스테이징이 깨끗했고 fetch 후 origin/main과 일치했습니다. 결과 커밋은 이 문서의 Git 이력에서 확인합니다.
- 상태: **최소 저장 모듈 구현·빌드·실제 격리 PostgreSQL 통합 시험 통과. S4 전체는 진행 중**입니다. Paper에서 DB 모듈을 활성화하거나 실제 플레이어 데이터를 저장한 결과가 아닙니다.
- 기준: [ADR-0001](decisions/0001-story-world-multiworld.md), [0002](decisions/0002-stack-and-world-policies.md), [0003](decisions/0003-inventory-performance-and-recovery.md), [0012](decisions/0012-progress-storage-world-lifecycle-and-content.md), [0013](decisions/0013-vote-boss-and-gathering-state-boundaries.md), [S3 설계](story-world-detailed-design.md). 이번 변경은 승인 정책의 최소 구현이며 새 가입·탈퇴·파티장 정책을 만들지 않습니다.

> 후속 [S4-5](s4-5-verification.md)에서 Paper libraries·실DB 연결·Paper 두 JVM 간 상태 복원과 대표 안정성을 시험했습니다. 아래 S4-4 당시의 미실행/구현 경계는 이력으로 보존하며 후속 결과와 구분합니다.

## 1. 기성 라이브러리 검토와 선택

| 후보·공식 근거 | 확인된 사실·선택 이유 | 남는 경계 |
| --- | --- | --- |
| [pgJDBC](https://jdbc.postgresql.org/download/) 42.7.14 | 공식 PostgreSQL JDBC 드라이버, Java 8 이상 계열. 배포 POM의 BSD-2-Clause 확인. 직접 프로토콜을 구현하지 않고 사용 | 이번 Java 25·PostgreSQL 18.3 조합의 SQL 시험만 통과. 운영 인증·TLS·다른 DB 버전은 미시험 |
| [HikariCP](https://github.com/brettwooldridge/HikariCP) 7.1.0 | 공개 DataSource 연동 API·연결 풀 재사용. 배포 POM의 Apache-2.0 확인. 자체 연결 풀 개발 대신 채택 | 동시 연결 2개·대기열 제한은 최소 시험 초기값이며 운영 성능 예산이 아님 |
| [Zonky embedded-postgres](https://github.com/zonkyio/embedded-postgres) 2.2.2 | 신규 사용자 소유 PostgreSQL 시험 프로세스만 관리. Apache-2.0. Linux amd64 네이티브 18.3.0만 명시적으로 선택 | 시험 전용 제3자 빌드이며 PostgreSQL 공식 바이너리 배포본이라는 뜻이 아님. 운영 버전·배포 방식으로 채택하지 않음 |

공식 [JDBC 연결 설정](https://jdbc.postgresql.org/documentation/use/)과 [PostgreSQL 제약](https://www.postgresql.org/docs/current/ddl-constraints.html)을 참고했습니다. 라이선스 고지·재배포 조건은 각 라이브러리의 원문을 유지해야 합니다. 이번 Hearth JAR에는 의존 JAR·네이티브 바이너리를 내장하지 않습니다. `plugin.yml`의 [Paper libraries](https://docs.papermc.io/paper/dev/plugin-yml/#libraries)에 pgJDBC/HikariCP 버전을 선언했습니다. **이번 변경 후 Paper의 라이브러리 다운로드·클래스로더 동작은 실행 미검증**입니다. Multiverse-Core는 도입하지 않았습니다.

## 2. 최소 구현과 소유권

- [저장 모듈](../src/main/java/io/github/jesterportfolio/hearth/persistence/)은 Bukkit 객체를 받지 않는 Java 계층입니다. `ProgressStore`는 모든 DB 작업·종료를 `CompletionStage`로 반환합니다. 기존 월드 생성·저장·복원 Java 코드는 변경하지 않았습니다.
- [V001 스키마](../src/main/resources/db/migration/V001__progress.sql)는 `hearth_progress.story_run`의 공유 상태와 `player_progress`의 개인 상태만 둡니다. 시험용 `state_value` 문자열은 최대 UTF-8 4096바이트이며 실제 퀘스트·인벤토리 스키마가 아닙니다.
- 공유 PK는 `story_run_id`, 개인 PK는 `(story_run_id, player_uuid)`입니다. 개인 행의 진행 FK는 공유 진행을 참조합니다. 물리 월드 UUID·`world_key`는 소유권 키도 API 입력도 아닙니다.
- `ServerProgressAccess.Context`는 private 생성자와 발급자 식별을 갖는 서버 내부 불변 객체입니다. 다른 발급자의 컨텍스트·존재하지 않는 진행을 거부합니다. 읽기·저장 API에 클라이언트가 임의 진행 ID를 넣는 경로는 없습니다.
- **컨텍스트 발급 함수 자체는 인증기나 세션 연결 검증기가 아닙니다.** 신뢰하는 서버 코드가 확인한 연결만 전달해야 하며 현재 명령·패킷·플레이어 세션과 연결하지 않았습니다. 후속 연동에서 접속 세션과 현재 진행 검증이 필요합니다. 이 내부 연결은 가입·탈퇴 기능이 아닙니다.
- 격리는 SQL의 진행 키·개인 복합 키와 서버 컨텍스트 범위에서 구현했습니다. DB 역할별 RLS나 서로 불신하는 DB 사용자의 보안 격리를 구현한 것은 아닙니다. 경제 데이터와 인벤토리 테이블은 추가하지 않았습니다.

### 스키마 버전 관리

`SchemaMigration`은 전용 `schema_version`에 버전 1과 SQL SHA-256을 저장합니다. DB 트랜잭션·advisory lock으로 협력하는 초기화 작업을 순서화하며 동일 버전 재실행은 허용합니다. 알 수 없는 버전·체크섬 변경·관리 이력 없는 기존 테이블은 초기화하거나 자동 채택하지 않습니다. 실패하면 롤백하고 모듈을 준비 완료로 표시하지 않습니다.

현재 구현은 V001 하나만 지원합니다. 이후 버전은 기존 V001을 수정하지 않고 후속 마이그레이션과 업그레이드 시험을 추가해야 합니다. 일반 SQL 전체를 처리하는 마이그레이션 엔진은 아니며, 실제 운영 DB의 자동 탐색·수정·재설정 기능도 없습니다.

### revision과 비동기 처리

1. 저장 요청은 현재 기대 revision과 불변 문자열을 넘깁니다.
2. DB worker가 `UPDATE ... WHERE revision=? RETURNING ...`으로 비교·변경하고 revision을 1 올립니다. 조건이 맞지 않거나 행이 없으면 실패합니다.
3. JDBC 자원이 정리된 뒤에만 정상 결과를 반환합니다. 오래된 요청을 재해석하거나 자동 재시도하지 않습니다.
4. SQL/연결/스키마 오류는 실패한 future와 작업·진행/개인 식별자·오류 코드로 전달합니다. 공개 오류에 SQL 원문·상태값·인증정보를 포함하지 않습니다. 오류 기록기는 비차단 처리를 해야 합니다.
5. worker 2개·대기열 16개·연결 풀 최대 2개로 제한하며 초과 요청은 거부합니다. 호출 스레드에서 SQL을 실행하는 대체 정책을 사용하지 않습니다. 연결 대기 1.5초, 검증 0.5초, SQL 제한 3초는 현재 최소 구성입니다. 정상 종료는 새 요청을 막고 기존 작업을 비동기로 정리합니다.

DB worker 완료 콜백에서 Bukkit API를 직접 호출하면 안 됩니다. 향후 Paper 연동 시 결과를 메인 스레드로 되돌려 세션·진행을 다시 확인해야 합니다. 호출부가 메인 스레드에서 `join/get`으로 기다리지 않아야 한다는 사용 계약도 유지합니다. 현재 플러그인 부팅에서 모듈을 생성하거나 DB에 연결하지 않습니다.

revision은 이 최소 상태의 오래된 저장 방지입니다. 전체 인벤토리·거래·보상 순서 조정기나 정확히 한 번 실행을 구현한 것이 아닙니다. commit 응답이 불명확하면 호출자는 성공을 추정하거나 무조건 재실행해서는 안 됩니다. 이 경우의 요청 ID 기반 확인은 후속 기능에서 구현·시험해야 합니다.

## 3. 격리 시험 환경과 무결성

- Temurin JDK **25.0.5+8**, Gradle **9.1.0**, 고정 Paper API **26.2.build.134-stable**로 컴파일했습니다.
- 호스트 PATH와 일반 PostgreSQL 전용 경로에서 실행 도구를 찾지 못했습니다. 시스템 패키지·Docker·운영 DB를 사용하지 않고 시험 전용 의존성을 사용자 Gradle 캐시에 받았습니다.
- 최초 실DB 시험: `/tmp/hearth-s4-4.aqfcjrpk`. 최종 시험: `/tmp/hearth-s4-4.ftzpohf3`. 둘 다 신규 사용자 소유 경로이며 기존 증거를 덮어쓰거나 DB 데이터를 삭제하지 않았습니다.
- [시험 도구](../scripts/verify-s4-4.py)는 신규 `/tmp/hearth-s4-4.*` 경로·소유자·표식·빈 data 경로를 확인합니다. 가용 RAM 4GiB·임시 디스크 2GiB 이상을 사전 요구합니다. Gradle JVM 최대 힙 384MiB/worker 2개, 시험 JVM 최대 힙 256MiB/CPU 힌트 2개, 개별 Gradle 작업 120초·통합 시험 작업 90초 제한입니다. 이는 프로세스 전체 RSS 하드 제한은 아닙니다.
- PostgreSQL은 `127.0.0.1`의 임시 포트와 해당 경로의 Unix socket만 사용했습니다. 최종 포트는 아래 요약 증거의 값입니다. 임시 로컬 시험의 trust 인증을 사용하며 외부 공개·운영 인증 설정·계정 비밀번호 저장은 없습니다. 이 인증은 운영 권장 구성이 아닙니다.
- PostgreSQL 18.3의 `shared_buffers=16MB`, `work_mem=1MB`, `max_connections=12`, `max_parallel_workers=0`, `fsync=on`, `synchronous_commit=on`을 사용했습니다. `initdb`의 준비 과정은 별도 기본값을 사용할 수 있습니다. 시험 JVM의 임시 디렉터리도 전용 경로로 지정해 공용 임시 DB 탐색/정리를 피했습니다.
- 종료 후 postmaster 종료·loopback 포트 재바인딩을 확인했습니다. 정상 종료 실패 시에도 도구는 자신이 만든 data 경로·UID·PID 명령행을 확인한 DB만 중지하도록 제한합니다. 기존 서비스·계정·데이터에는 접근하지 않습니다.

### 다운로드 체크섬

네 가지 핵심 아티팩트의 Maven Central 게시 체크섬을 HTTPS로 받아 대조했습니다. SHA-256 파일은 제공되지 않았습니다. pgJDBC·HikariCP·시험 관리 라이브러리는 게시 SHA-1과 일치했고 네이티브 바이너리 JAR은 게시 SHA-512와 일치했습니다. 별도 계산한 SHA-256은 [요약 증거](../verification/s4-4-result.json)와 시험 도구에 보존했습니다. 도구는 실행 전 복사본과 Gradle 캐시의 동일 이름 아티팩트를 다시 대조합니다. **게시 SHA-256 검증이나 모든 전이 의존성의 서명 검증을 완료했다는 뜻은 아닙니다.** SHA-1 대조는 강한 배포자 인증 보증으로 취급하지 않습니다.

## 4. 실행 명령과 실제 결과

```bash
env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-s4-1.RSHqyT/gradle-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --offline --console=plain build test check
python3 scripts/verify-s4-4.py --java-home /home/server/.local/share/hearth-verification/jdk-25.0.5+8 --gradle /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --gradle-home /tmp/hearth-s4-1.RSHqyT/gradle-home
```

첫 의존성 해결은 네트워크를 사용했습니다. 위 명령은 준비된 캐시를 쓰며 설치·자동 다운로드를 수행하지 않습니다. `build/test/check`는 실DB를 실행하지 않습니다. 시험 도구가 별도 `stageS44Runtime`·해시 확인·`postgresIntegrationTest`를 순서대로 수행합니다. 직접 통합 task를 실행하려면 동일한 새 경로/표식·무결성/자원 조건을 먼저 충족해야 합니다. 기존 data 경로 재사용은 거부합니다.

| 시험 | 실제 결과·구분 |
| --- | --- |
| Gradle build/test/check | 성공. 기존 템플릿 파일 시험 1개와 새 저장 단위 시험 3개, 실패 0 |
| 실제 PostgreSQL 통합 | JUnit 시험 1개 안의 14개 검사 통과. 단위 모형 DB로 대체하지 않음 |
| A 공유 상태 변경 → B | B 값과 revision 불변 |
| P1의 A 개인 상태 변경 → P1의 B / P2의 A | 두 개인 값과 revision 불변 |
| 같은 A의 P1/P2 공유 조회 | 동일 상태·revision 조회 |
| 중복 진행/개인 키 | 실제 SQLSTATE `23505`로 거부 |
| 없는 진행의 개인 행 / 없는 진행 조회·저장 / 다른 발급 컨텍스트 | FK `23503`, 누락 상태·revision 충돌·접근 거부 |
| 오래된 revision 저장 | 공유·개인 최신 값 덮어쓰기 거부 |
| 동시 같은 revision 저장 | 별도 풀의 두 요청 중 하나만 성공하고 revision 한 번 증가 |
| 실제 SQL 쓰기·읽기 실패 | 시험 전용 trigger `23514` 및 임시 테이블명 변경 `42P01`, 실패 future 반환. 쓰기 실패 전 값 유지 |
| 실제 연결 실패 | 시험 DB 정상 종료 후 새 풀의 migrate 실패·오류 기록. 성공으로 반환하지 않음 |
| 스키마 재실행·변조 | 같은 버전 재실행 성공. 변경된 체크섬 거부·준비 완료 해제. 원본 DB 자동 초기화 없음 |
| 저장 모듈 정상 종료 후 새 풀·연결 | 기존 공유/개인 상태·revision 복원. DB 서버 프로세스 재시작·충돌 복구 시험은 아님 |
| 호출 스레드 비차단 | 단위 시험에서 연결 시도를 막아도 호출자가 future를 반환받고 JDBC가 다른 스레드에서 실행됨 |
| JAR | 저장 클래스·V001·확장된 plugin.yml 포함, Java 25 바이트코드. pgJDBC/HikariCP/시험 DB 바이너리 미동봉 |
| Paper 및 실제 플레이어 | 이번에는 실행하지 않음. S4-1~3의 과거 실행 근거를 이번 JAR 런타임 결과로 재사용하지 않음 |

최종 결과·검사 목록·아티팩트와 시험 로그 해시는 [요약 증거](../verification/s4-4-result.json)에 기록합니다. 전체 실행 로그·원시 JUnit XML·DB 파일은 해당 임시 경로와 로컬 build에 남깁니다. Git에는 비밀 정보·DB 파일·바이너리·인증 환경 변수 값을 포함하지 않습니다.

### 오류·경고와 회귀 경계

- 최초 Gradle 스크립트에서 `java.time.Duration`이 Gradle의 `java` 확장과 충돌해 구성에 실패했습니다. 명시적 `Duration` import로 수정한 후 빌드·통합 시험이 통과했습니다. 이 구성 실패를 성공으로 소급하지 않습니다.
- 실패 주입은 예상된 시험 입력입니다. SQL 실패·중복 키·연결 불가를 기능 성공 응답으로 바꾼 것이 아닙니다.
- 단위 모형의 실패 연결 정리에서 HikariCP의 연결 생성 executor 종료 대기 경고가 한 번 관측됐습니다. 실제 DB 통합 시험에서는 풀 종료·postmaster 종료·포트 반환을 확인했습니다. 모든 네트워크 장애의 종료 시간을 검증한 것은 아닙니다.
- 기존 템플릿 파일 JUnit 회귀와 월드/WG 코드의 컴파일·JAR 빌드는 통과했습니다. 기존 S4-1~3 Paper 런타임을 이번 단계에서 다시 실행하지 않았습니다.
- CPU/RSS·TPS/MSPT·부하·누수는 측정하지 않았습니다. 실행 시간 제한과 메모리 설정을 실제 성능 측정 결과로 표현하지 않습니다.

## 5. 문서·산출물 검사

변경 Markdown 9개의 상대 링크 187개·표 열·제목·코드 블록을 검사해 오류 0건을 확인했습니다. 일본어/한자 문자 검색과 한국어 문맥 검토 후 혼입은 0건입니다. S3 제목의 기존 일본어식 구분기호 1개를 한국어 구분기호로 정리했습니다. `git diff --check`, 시험 도구 Python 구문 검사, 기존 월드 Java 소스 불변·저장 모듈의 Bukkit 비의존·S3/S4 상태 경계 정적 검사도 통과했습니다. 보안 검사는 스테이징과 Git hook 정책에 따라 수행하며 최종 결과는 작업 보고에서 확인합니다.

## 6. 남은 작업

- 실제 Paper 라이브러리 해석/클래스로더·비동기 DB 수명주기·메인 스레드 복귀·검증된 플레이어 세션 연결.
- 연결 끊김·commit 응답 불명·종료/저장 경합·대기열 포화·장기 지연·권한 최소화·인증/TLS·향후 스키마 버전 업그레이드 시험.
- 전체 인벤토리, 초기 30초 dirty 배치, 거래·보상 저장 순서/중복 방지, 경제 데이터 분리 구현은 후속 범위입니다. ADR-0003은 유지합니다.
- 월드 IDLE 자동 언로드·퀘스트/NPC/전투/AI·운영 배포는 미구현입니다. 실제 플레이어 보호는 [S4-3](s4-3-verification.md)의 미실행 상태를 유지합니다.

이 최소 모듈은 정상 저장의 진행 격리와 revision 기반 덮어쓰기 방지를 검증했습니다. 강제 종료 시 무손실 자동 복구나 모든 아이템 복사 방지를 보장하지 않으며 로그·백업을 통한 관리자 수동 대응 원칙을 유지합니다.
