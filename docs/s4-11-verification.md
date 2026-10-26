# S4-11 — 월드 수명주기 통합 재검증과 실패 이력


**최신 판정: S4-9·S4-10·S4-11의 승인된 이번 범위 PASS.** 사용자 재개 지시에 따라 기존 변경 27개와 실패 증거를 보존하고 `/tmp/hearth-s4-11.j_mcgvnq`에서 두 JVM 통합 시험을 통과했습니다. 최신 근거는 [재검증 JSON](../verification/s4-11-reverification-result.json)과 아래 [수정본 재검증 결과](#수정본-재검증-결과)입니다. S4 전체는 진행 중, S4-3 실제 플레이어 시험은 보류입니다.

## 최초 실패 기록

다음 최초 실행·수정본 미실행·커밋 보류 설명은 **재개 전의 이력**입니다. 원본 [실패 JSON](../verification/s4-11-result.json), 이전 `/tmp` 증거 및 당시 수정본을 보존했습니다. 재검증 성공으로 과거 실패/미실행을 소급 변경하지 않습니다.

- 확인일: **2026-10-26 (Asia/Seoul)**. 시작 HEAD/origin/main: `7cf167e63a83a90761dcb67e0d46e0b16ea1c585`, main·깨끗한 작업 트리.
- 판정: **실패·미완료**. S4-9 단위 조건은 통과했고 S4-10은 단위/컴파일·일부 초기 준비만 확인했습니다. S4 전체는 진행 중, S4-3 실제 인증 플레이어 시험은 보류입니다.
- [단계 JSON](../verification/s4-11-result.json), [S4-9](s4-9-verification.md), [S4-10](s4-10-verification.md).

## 실행 환경과 재사용

새 사용자 소유 경로 `/tmp/hearth-s4-11.zot94g67`에서 Paper 26.2 build 134·Temurin 25.0.5+8·WG 7.0.19·WE 7.4.4·PostgreSQL 18.3·pgJDBC 42.7.14·HikariCP 7.1.0을 사용했습니다. Gradle 9.1.0입니다. 기존 공식 아티팩트 해시와 핵심 DB/네이티브 의존성 해시를 확인하고 다운로드 캐시만 복사했습니다. 기존 월드·DB·증거를 가져오지 않았습니다.

기존 RestartVerification의 prepare만 새 템플릿/A/B 준비에 사용했습니다. 재시작 후에는 이 코드의 resume 전체를 반복하지 않고 기록된 매핑을 새 관리자에 연결하도록 시험 경로를 마련했습니다. S4-5 Monitor·기존 DB fixture에 명시적인 `--lifecycle` 게이트를 추가했습니다. 기존 모드의 기본값은 유지합니다. 기본 플러그인 설정은 비활성이며, 새 경로·표식·소유자·JVM 플래그·시험 DB 식별까지 맞아야 새 콘솔 어댑터가 켜집니다. 일반 부팅의 자동 DB 연결·로그인/로그아웃·텔레포트는 없습니다.

실제 기동은 **Paper 1회, 독립 PostgreSQL 1회**입니다. Paper `127.0.0.1:25585`, PostgreSQL `127.0.0.1:34119`와 전용 socket을 사용했습니다. 기존 EULA 승인 범위만 적용했고 인증·whitelist는 유지했습니다. DB의 shared_buffers16MB/work_mem1MB/max_connections12/fsync on/synchronous_commit on 설정을 재사용했습니다. 임시 DB의 인증은 운영 권장 구성이나 기존 계정 변경이 아닙니다.

```bash
nice -n 10 env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-s4-1.RSHqyT/gradle-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --offline --console=plain build test check stageLifecycleRuntime -PlifecycleWorkspace=/tmp/hearth-s4-11.zot94g67
python3 scripts/verify-lifecycle.py --workspace /tmp/hearth-s4-11.zot94g67 --java /home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/java --plugin build/libs/Hearth-0.1.0-SNAPSHOT.jar --artifact-source /tmp/hearth-s4-2.vnd2kki4 --accept-eula
```

이 경로는 실패 증거로 보존하므로 재실행하지 않습니다. 시험 JAR과 수정 후 최종 빌드 JAR은 서로 다르며 JSON에 구분합니다.

## 실제 확인과 중단 위치

| 항목 | 실제 결과 |
| --- | --- |
| Paper/Hearth/WG/WE·Paper libraries·DB 모듈 | 정상 로딩, 새 V001/V002 및 가상 상태 생성 성공 |
| 같은 템플릿 A/B와 기존 준비 회귀 | 기존 prepare 통과, 월드/구역 저장 후 매핑 기록 |
| 새 관리자의 A/B 동시 예약·중복 요청 | 진행별 load 1회, 동일 요청 future 공유, 다른 진행 독립 준비 |
| WG 구역·우선순위, DB 공유/개인, 41슬롯 인벤토리 | 초기 복원 검사 후 READY/ACTIVE 진입 |
| 마지막 내부 참가자 퇴장·IDLE 재입장 취소 | 2회 확인, 기존 타이머 시점이 지나도 저장/언로드 없음 |
| DB 잠금 대기 중 dirty 변경·IDLE 만료 | **실패**. 시험 대기 루프가 같은 틱을 점유해 QUIESCING에 도달하지 못함 |
| 새 조정자의 정상 저장·언로드·재로드 | 미완료 |
| 두 번째 JVM의 영속성·오류·A 실패/B 정상 | **미실행**, 첫 JVM 실패 후 진행하지 않음 |

초기 확인된 매핑은 다음과 같습니다. 재로드 후 동일성 검사는 이번 실행에서 수행하지 못했습니다.

| 진행 | 물리 UUID | key | 초기 블록 | 확인 시 공유/개인/인벤토리 revision | 인벤토리 수량 |
| --- | --- | --- | --- | --- | --- |
| A: `00000000-0000-002a-0000-00000000000a` | `7be641b2-37d9-42cd-b598-2077a860efff` | `hearth_verify:restart_a` | GOLD_BLOCK | 0 / 0 / 0 | 32 |
| B: `00000000-0000-002a-0000-00000000000b` | `9b74a483-2ab4-439e-bab2-a8bbc8479e80` | `hearth_verify:restart_b` | DIAMOND_BLOCK | 0 / 0 / 0 | 7 |

P1은 `00000000-0000-0031-0000-000000000001`이며 A/B의 개인 키는 story_run_id+player_uuid입니다. 이후 A 공유 저장과 가상 블록/인벤토리 변경을 시작했지만 실패 후 DB/월드 최종 일치 상태는 재조회하지 않았습니다. 초기 snapshot을 최종 저장 성공 증거로 사용하지 않습니다.

## 실패 원인·수정·남은 범위

`LifecycleVerification.until()`의 조건 반복이 `Bukkit.getScheduler().runTask(plugin, this)`를 사용했습니다. 이 위치에서는 같은 heartbeat에서 반복 실행되어 tick과 IDLE 타이머·결과 큐 진행을 막았습니다. 잠긴 인벤토리 SQL은 3초 제한 후 **57014**를 반환했고, Paper watchdog이 정지한 메인 스레드를 기록한 뒤 종료했습니다. 이는 의도한 저장 실패 주입이 아니며 예상 오류 시험 통과로 세지 않습니다. B도 같은 서버 틱 정지의 영향을 받았으므로 런타임 A/B 실패 격리 성공을 주장하지 않습니다.

자기 재예약은 `runTaskLater(plugin, this, 1)`로 수정했습니다. PaperLifecycleBoundary의 안전 시점 재검사도 같은 방식으로 다음 틱을 명시했고, WorldGuard I/O 뒤 후속 검사는 owner executor로 전달합니다. 수정 후 빌드/단위 회귀는 통과했으나 **수정본 런타임은 미실행**입니다. 첫 JVM 정상 종료와 후속 JVM 시험까지 포함하는 전체 재시험을 완료하지 않았습니다. 추가 기동·시간 한도를 임의로 확대하지 않았습니다.

후속은 새 격리 환경에서 수정본의 첫 JVM 정상 정리와 새 JVM 복원, 저장 중 입장 대기, 최신 dirty/revision, 실제 unload false·DB 오류, 경계 주입 WG/world 저장 실패, 티켓/누락/UUID 거부, A 실패/B 정상 및 정상 종료를 다시 확인하는 작업입니다. 실제 파일시스템 장애·강제 종료 복구·클라이언트·DB 프로세스 재시작·TPS/MSPT·장기 부하/누수는 계속 미검증입니다.

## 자원·종료·보존

전체 **90.553초**, Paper 시작 완료 **13.380초**, A/B 초기 준비 LOADING **304/301ms**입니다. Paper 최대 표본 RSS **991.11MiB**, 관측 CPU 누적 **210.04초**, DB 보조 JVM RSS **209.90MiB**·CPU **2.39초**, 최소 가용 RAM 약 **11.213GiB**였습니다. CPU 값은 다중 스레드 user+system 마지막 표본이며 사용률·TPS 통과 기준이 아닙니다. DB 수치는 보조 JVM만 포함하고 PostgreSQL 전체 메모리/CPU는 별도 측정하지 않았습니다.

Xmx1536MiB·ActiveProcessorCount=2·nice+10·RSS2.5GiB 중단·RAM6GiB·디스크4GiB·작업550초+정리50초의 600초 상한을 적용했습니다. 장기 안정성이나 기존 서비스 성능을 검증하지 않았습니다.

Paper는 정상 종료가 아닌 **exit 70**, `HEARTH_LIFE_DISABLE_CLOSE FAIL`입니다. DB fixture는 정상 exit 0입니다. 원래 감시 결과는 비정상 종료 때문에 cleanup 오류와 resources_closed=false를 기록했으며 수정하지 않았습니다. 이후 독립 재확인에서 **소유 PID·프로세스 그룹 부재, 25585/34119 포트 반환, postmaster.pid 부재**를 모두 확인했습니다. 이는 정상 저장/풀 종료 통과와 별개입니다. 원시 로그·표본·실행 스크립트/JAR·매핑·실패 DB는 새 경로에 보존했습니다. 데이터 삭제나 자동 빈 데이터 복구는 하지 않았습니다.

기존 Docker/Zabbix/Cloudflare/SSH·방화벽·전역 Java·패키지·운영 DB·실제 게임 데이터·비공개 스토리 저장소는 변경/접근하지 않았습니다. AGENTS.md의 시험 실패 시 Git 흐름 중단 규칙에 따라 **커밋·푸시는 수행하지 않습니다**. S2/S3 완료·전체 S4 진행 중 상태를 유지합니다.


## 최종 문서·보안 검사와 작업 트리

수정 후 Gradle build/test/check가 성공했고 JUnit 단위/회귀 **26개, 실패/오류/생략 0개**입니다. Python 구문, 비활성 기본 설정·명시적인 다음 틱 예약·메인 스레드 동기 DB 대기 부재를 정적으로 확인했습니다. 이 검사들이 수정본 런타임 시험을 대체하지는 않습니다. 이전 검증 JSON·V001/V002·기존 인벤토리/전환/DB 저장 소스가 HEAD와 동일한 것도 확인했습니다.

변경 Markdown **10개·상대 링크 217개**, 제목·표 열·코드 블록과 일본어/한자 문자 검사 오류 0건입니다. 한국어 문맥도 검토했습니다. `git diff --check`와 승인 변경 27개 복사본에 대한 `gitleaks dir --redact --exit-code 1`이 통과했습니다. 커밋하지 않으므로 staged/pre-commit/pre-push 검사는 수행하지 않았습니다.

마지막 fetch 후 main의 HEAD와 origin/main은 기준 커밋으로 일치(좌/우 0/0)하며 스테이징은 비어 있습니다. 변경은 총 **27개 파일**이고 목록은 단계 JSON에 기록합니다. 수명주기 4개 클래스·시험 어댑터 1개·단위 클래스 2개·실행 도구 1개·새 기록 Markdown/JSON 각 3개를 추가하고 기존 backend/plugin/fixture/build/config 및 현황·로드맵·지침/문서 안내를 필요한 범위에서 수정했습니다. 원래 문서에 남은 초기 검증 경계는 당시 이력이며 최신 실패/미검증은 이 기록을 따릅니다.


## 수정본 재검증 결과

재개 시작 시 main·HEAD/origin/main은 `7cf167e63a83a90761dcb67e0d46e0b16ea1c585`로 일치했고 스테이징은 비어 있었습니다. 기존 변경 27개를 새 경로의 starting-worktree와 SHA-256 manifest로 보존한 후 필요한 대기 가드/시험·기록만 보완했습니다. 기존 WorldLifecycle·저장 모듈·V001/V002·이전 단계 증거는 보존했습니다. 기성 WorldGuard·Paper API 경로를 유지하며 새 기능·플러그인·다음 단계를 도입하지 않았습니다.

### Watchdog 수정 검토

- 같은 틱 자기 재예약을 제거한 `runTaskLater(...,1)`은 그대로 유지했습니다. 조건 대기에서 현재 틱이 이전 틱보다 커야 한다는 검사를 추가했고 실제 로그의 74개 반복 관측이 모두 증가했습니다.
- 거짓 조건을 3틱 뒤 WAIT_TIMEOUT으로 끝낸 후 5틱을 더 진행해 재예약이 증가하지 않음을 확인했습니다. 완료 future·닫힘·10초 future 타임아웃 가드를 추가했습니다. 안전 시점 대기도 완료/닫힘/이전 세대를 거부합니다.
- 메인 스레드에서 Future.join/get으로 DB 응답을 기다리는 코드는 없습니다. Supplier.get은 비동기 작업 생성 호출이며 Future.get과 다릅니다. 실제 DB 잠금 대기 중 Paper 틱이 진행했습니다.
- 두 번째 JVM의 제어 future를 완료하기 전에 pipeline 세대를 변경해 STALE_GENERATION을 확인했습니다. WG/world 저장 호출 수와 실제 A/B ACTIVE 상태는 바뀌지 않았습니다. 이는 Paper 안의 **제어 경계 probe**이며 실제 플레이어 세션 검증이 아닙니다.

### 실제 실행 순서

새 `/tmp/hearth-s4-11.j_mcgvnq`·동일 고정 버전/아티팩트 해시·localhost·사용자 소유·기존 EULA 승인 범위를 사용했습니다. PostgreSQL은 신규 한 개를 **43127** 포트로 기동해 두 Paper JVM 동안 유지했습니다. DB 프로세스 재시작은 하지 않았습니다. 새 실행은 한 번에 통과해 추가 재시험은 사용하지 않았습니다.

첫 JVM은 기존 prepare로 새 템플릿/A/B·구역·매핑을 준비했습니다. A/B load를 각 1회로 통합하고 공유/개인/인벤토리 확인 후 ACTIVE로 만들었습니다. IDLE 취소 2회 뒤, SQL 잠금으로 수량31 저장을 지연시키고 수량30을 추가 dirty로 만든 상태에서 마지막 내부 참가자를 퇴장시켰습니다. IDLE 약1초 후 QUIESCING에서 저장 완료를 기다렸고 새 입장은 SAVING에서 대기했습니다. 잠금 해제 후 최신 revision2까지 정리하고 WG/world 저장·실제 언로드·기존 재로드를 완료한 뒤 입장을 허가했습니다. 원래 UUID/key·블록·추가 블록·구역·DB 값이 보존됐습니다. 풀/파일 worker 정리 후 Paper를 정상 종료했습니다.

두 번째 JVM은 기존 매핑·월드·동일 DB만 읽었으며 createRun/createPlayer/createInventory나 빈 월드 대체 경로를 실행하지 않았습니다. 복원·stale probe·실DB 쓰기 실패·경계 저장 실패·실제 티켓/누락/UUID 거부를 확인했습니다. 실제 WorldUnloadEvent를 취소해 A를 ERROR_HOLD·로드 상태로 남긴 뒤 B의 수량6/revision1 저장과 언로드를 완료했습니다. A HOLD를 자동 해제하지 않고 서버 종료까지 보존했습니다. 풀과 worker를 정리하고 Paper 및 DB를 정상 종료했습니다.

추가 스트레스 반복은 없습니다. 관리자 기준 A 로드는 첫 JVM 2회(초기·재로드), 다음 JVM 1회이며 B는 각 JVM 1회입니다. 관리자 언로드는 A 성공 1회·거부 1회, B 성공 1회입니다. 기존 prepare의 템플릿/빈 물리 월드 준비·복제/기준 저장은 이 반복 횟수와 구분합니다.

### 보존한 상태

| 진행 | 물리 UUID / key | 블록·보호 구역 | DB / 인벤토리 |
| --- | --- | --- | --- |
| A | `f4f3a1cc-7e18-42d8-b86a-94100d6e0078` / `hearth_verify:restart_a` | (0,64,0) GOLD_BLOCK, 추가 (1,64,1) EMERALD_BLOCK 유지. protected BUILD DENY·build 우선순위10·overlap20 복원 | 공유 a-saved/revision1, P1 개인 p1/revision0, 수량30·41슬롯 메타데이터/revision2를 재로드·새 JVM에서 확인 |
| B | `37c22c86-95ba-4f31-b12e-8ee3cf921c1b` / `hearth_verify:restart_b` | (0,64,0) DIAMOND_BLOCK, protected BUILD ALLOW. A 설정과 분리 | 새 JVM까지 공유 b/revision0·개인 p1/revision0·수량7/revision0 유지. 이후 오류 독립성 시험에서 수량6/revision1 저장 완료 |

물리 UUID/key는 DB 소유권 키가 아닙니다. 개인 식별은 story_run_id+player_uuid를 사용합니다. 템플릿은 기존 prepare의 digest 검사로 보존을 확인했으며 이후 관리자에는 복제/생성 메서드가 없습니다.

### 실패 주입과 검증 수준

| 항목 | 실행 수준·결과 |
| --- | --- |
| 상태/예약·IDLE·중복·이전 타이머/콜백 | 기존 단위 8개 통과. Paper에서는 초기 로드 단일화·IDLE 취소·저장 중 입장·대기 틱 진행 확인 |
| 저장 순서·9개 경계 실패 | 기존 단위 3개 통과. 실제 정상 경로와 아래 대표 거부만 실행 |
| DB 쓰기 오류 | 실제 PostgreSQL 23514. 별도 A/P2 저장 pipeline에서 기존 수량5/revision0·dirty 보존, 뒤 월드 저장/언로드 호출 없음. 전체 A 상태를 새 HOLD로 바꾼 시험은 단위에 해당 |
| WG/world 저장 실패 | Paper 안의 경계 예외 주입. 뒤 단계 중단·언로드 미실행. 실제 파일시스템 고장 아님 |
| unload false | 실제 WorldUnloadEvent 취소 → false → A ERROR_HOLD, UUID/블록/로드 상태 유지·새 예약 HELD |
| 실패 진행 격리 | 위 A HOLD 중 B는 ACTIVE 유지 후 최신 dirty 저장·WG/world 저장·언로드 성공 |
| 세대 변경 | 실제 Paper 안의 제어 future/pipeline probe와 기존 단위 검사. 실제 인증 세션 아님 |
| 티켓·누락·UUID 오류 | 실제 자기 플러그인 청크 티켓 검사 및 잘못된 기존 매핑 등록 거부. 원본을 변경하거나 빈 월드를 생성하지 않음 |
| 종료 | 두 Paper 및 DB exit0, DISABLE_CLOSE PASS·파일 worker 종료·프로세스 그룹/포트 반환 |

실제 인증 플레이어/로그인·텔레포트·전체 인벤토리 행동·isTickingWorlds=true를 강제로 지속시킨 안전 시점 타임아웃·실제 디스크 장애·DB commit 불명·강제 종료 무손실 복구·PostgreSQL 프로세스 재시작·TPS/MSPT·장기 부하/누수·운영 배포는 미실행입니다. 실패 주입용 별도 probe의 dirty/HOLD는 저장 성공에 포함하지 않습니다. 일반 서버 이벤트나 자동 복구 기능을 활성화하지 않았습니다.

### 자원·정리·최종 검사

전체 실행 **51.424초**, Paper 두 회 순차·PostgreSQL 한 개입니다. Xmx1536MiB·RSS2.5GiB 이상 중단·가용 RAM6GiB·디스크4GiB·ActiveProcessorCount=2/nice+10·600초 상한을 유지했습니다. 최소 표본 가용 RAM은 약 **11.475GiB**입니다.

| 대상 | 시작 완료 | 최대 표본 RSS | 관측 CPU 누적 | 정상 종료 소요 |
| --- | --- | --- | --- | --- |
| 첫 Paper PID1148055 | 13.592초 | 951.72MiB | 56.73초 | 3.696초 |
| 두 번째 Paper PID1148279 | 8.773초 | 928.93MiB | 29.99초 | 3.538초 |
| DB 보조 JVM PID1147973 | 준비 표식 확인·별도 기동시간 미집계 | 215.66MiB | 2.27초 | 0.208초 |

초기 A/B LOADING 304/301ms, A 재로드350ms, 새 JVM A/B LOADING389/387ms입니다. 이 수치는 구역/DB/개인 복원을 포함하는 상태 체류 시간입니다. 첫 저장의 QUIESCING450ms(의도적 SQL 지연 포함)·SAVING353ms·UNLOADING95ms를 관측했습니다. CPU는 표본 user+system 누적이며 다중 스레드 합산입니다. DB 보조 JVM 수치는 PostgreSQL 전체 프로세스 사용량이 아닙니다. TPS·수용량·메모리 누수 통과를 뜻하지 않습니다.

감시 도구는 모든 자식 종료·프로세스 그룹 부재·25585/43127 포트 반환과 DB 정리를 확인했습니다. 원시 로그·자원 표본·실행 스크립트·JAR·매핑·시작 작업 트리는 새 경로에 보존했습니다. 이전 실패 JSON/원시 경로와 V001/V002·기존 S4-1~8 증거를 변경하지 않았습니다. 경고는 기존 비대화형 터미널·JOML Unsafe·고정 Paper 업데이트 안내·수동 저장/deprecation이며 watchdog 오류는 재발하지 않았습니다. 최종 검사와 커밋/푸시 결과는 작업 보고와 이 문서의 Git 이력에서 확인합니다.


재검증 최종 문서 검사에서는 변경 Markdown10개·상대 링크228개·표/제목/코드 블록 오류0건과 일본어/한자 혼입0건을 확인하고 한국어 문맥을 검토했습니다. git diff --check·Python 구문 검사와 승인 변경28개 복사본의 Gitleaks dir 검사가 통과했습니다. 최종 JAR은 실제 실행 JAR과 SHA-256이 같고 major69·plugin.yml 포함·API/DB 라이브러리 미포함을 확인했습니다. 스테이징 보안 검사와 Git hook·커밋/푸시 결과는 최종 보고 및 Git 이력으로 확인합니다.
