# S4-8 — 인벤토리·진행 전환·두 JVM 통합 안정성 검증

- 확인일: **2026-10-26 (Asia/Seoul)**. 기준 커밋: `48277c8ef5c7e9be8c34e88f6db6c57772d946dc`.
- 상태: **S4-6·S4-7·S4-8의 이번 범위 통과. 전체 S4는 진행 중**입니다. 실제 접속 플레이어·운영 배포·장기 안정성을 완료한 것이 아닙니다.
- 사용자 추가 승인: S4-6 구현/사전 검사 → S4-7 구현/사전 검사 → 최대 두 JVM에서 실제 S4-6→7→8 시험. 초기 질문 대기 중에는 S4-6까지만 진행했고 승인 후 재개했습니다. 단위 성공만으로 앞 단계를 최종 완료로 표시하지 않았습니다.

## 재사용·실행 경계

[S4-5](s4-5-verification.md)의 Monitor·자원/포트·소유 DB 정리를 재사용했습니다. Monitor의 기본 동작/430초 작업 예산은 유지하고 호출자가 표식·작업 예산을 지정할 수 있도록 했습니다. [기존 DB 보조 도구](../src/integrationTest/java/io/github/jesterportfolio/hearth/persistence/S45DatabaseFixture.java)는 `--inventory`일 때만 신규 `hearth-s4-6.*`/`hearth_s4_6`과 고정 인벤토리 실패 주입을 허용합니다. SQL·접속 URL을 외부 입력으로 받지 않습니다.

[InventoryVerification](../src/main/java/io/github/jesterportfolio/hearth/verification/InventoryVerification.java)과 [실행 도구](../scripts/verify-inventory.py)를 추가했습니다. `inventory-verification-enabled` 기본값은 false이며 기존 verification 설정·S4-6 전용 경로/소유자/표식·JVM 플래그·새 DB 식별이 모두 맞을 때만 콘솔 경로를 활성화합니다. 일반 부팅의 로그인/아이템/드롭 이벤트·자동 DB 연결은 없습니다.

기존 PostgresProgressStore worker 2·대기열 16·Hikari 풀 2·CAS, Paper NBT API, 기존 월드 재시작 prepare/resume를 사용했습니다. 원래 월드 생성/복원 Java 코드·V001 SQL·S4-1~5 JSON 증거는 변경하지 않았습니다. Multiverse-Core·운영 계정/세션·경제 realm·NPC/퀘스트/AI·IDLE은 추가하지 않았습니다.

## 실제 실행

- 신규 통합 경로: `/tmp/hearth-s4-6.bxx056__`.
- Paper 26.2 build 134·Temurin Java 25.0.5+8·WG 7.0.19·WE 7.4.4·pgJDBC 42.7.14·HikariCP 7.1.0·PostgreSQL 18.3, Gradle 9.1.0.
- 기존 공식 Paper/WG/WE 해시와 S4-4의 핵심 JDBC/Hikari/시험 네이티브 아티팩트 해시를 다시 확인했습니다. 부트스트랩 캐시만 복사하고 월드/DB/기존 증거는 가져오지 않았습니다. Paper libraries 해석은 유지했고 관련 JAR 해시도 실행 후 대조했습니다.
- DB는 사용자 소유 신규 클러스터 한 개, localhost 포트 **37263**·자기 Unix socket·소규모 설정입니다. shared_buffers16MB/work_mem1MB/max_connections12/max_parallel_workers0/fsync on/synchronous_commit on을 유지했습니다. 임시 trust 인증이며 운영 인증 권장안이나 계정 변경이 아닙니다.
- Paper는 `127.0.0.1:25585`·online-mode/whitelist 유지, 명시 승인된 Hearth 격리 EULA만 사용했습니다. 클라이언트는 접속하지 않았습니다.

```bash
env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-s4-1.RSHqyT/gradle-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --offline --console=plain build test check stageInventoryRuntime -PinventoryWorkspace=/tmp/hearth-s4-6.bxx056__
python3 scripts/verify-inventory.py --workspace /tmp/hearth-s4-6.bxx056__ --java /home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/java --plugin build/libs/Hearth-0.1.0-SNAPSHOT.jar --artifact-source /tmp/hearth-s4-2.vnd2kki4 --accept-eula
```

위 경로는 보존한 증거이므로 재실행하지 않습니다. 기록기 보완 후 최종 build/test/check를 다시 통과하고 동일 JAR로 실행했습니다. 별도의 S4-6 SQL 사전 검사는 `/tmp/hearth-s4-6.l5kt4i1l`에서 완료·종료한 뒤였으며 DB 두 개를 동시에 실행하지 않았습니다.

### 첫 Paper JVM

1. 기존 prepare를 재사용해 같은 템플릿의 A/B 식별·블록/구역을 만들고 저장했습니다.
2. V001/V002 적용 전 인벤토리 쓰기 거부, 실제 ItemStack 41슬롯/메타데이터/빈 슬롯 왕복·손상 NBT 거부를 확인했습니다.
3. A/B와 P1/P2의 별도 행을 만들고, 미변경 생략 뒤 실제 30,049ms 주기의 dirty 저장을 관측했습니다.
4. DB 잠금 대기 중 추가 변경·중복 flush, 쓰기 실패/dirty 보존·확실한 rollback 수동 해제·새 풀 연결 복원을 통과했습니다.
5. `HEARTH_S46_GATE PASS` 후 A 저장 수량59/revision5를 확인하고 B 수량15/revision0을 한 번 적용했습니다. 이 뒤에만 `HEARTH_S47_GATE PASS`를 기록했습니다.
6. B의 새 dirty를 정상 로그아웃 인터페이스로 수량14/revision1까지 저장하고 풀을 정리한 뒤 JVM을 종료했습니다.

### 두 번째 Paper JVM

1. 기존 월드 UUID/key·블록·WorldGuard 구역/자연 피스톤 경로를 복원했습니다. 새 월드나 인벤토리 행을 생성하지 않았습니다.
2. A/P1=59/revision5, B/P1=14/revision1, A/P2=7/revision0, B/P2=20/revision0의 슬롯·메타데이터를 확인했습니다.
3. 외부 발급 컨텍스트·누락/손상 target·source 쓰기 실패·적용 콜백 실패·지연된 target의 세대 변경을 거부하고 target 미적용/HOLD를 확인했습니다.
4. 잠긴 DB 읽기 40개 중 22개를 QUEUE_FULL로 거부하고 나머지를 완료했습니다. 대표 저장/복원을 **3회**만 반복해 A/P1=58/57/56과 revision6/7/8을 확인했으며 B/P1은 불변이었습니다.
5. 저장 중 추가 dirty 이후 로그아웃을 요청해 신규 저장 CLOSED와 기존/최신 저장의 정리를 확인했습니다. 최종 A/P1은 수량54/revision10이었습니다.
6. 새 시험 DB만 정상 종료하고 연결 실패(57P01)에서 dirty·보류를 확인했습니다. 이 마지막 실패 주입용 내부 probe는 저장 미완료로 끝났으며 정상 저장 성공에 포함하지 않았습니다. 풀과 Paper를 종료하고 프로세스·포트를 정리했습니다.

## 성공·단위만·미실행 구분

| 항목 | 실제 확인 범위 |
| --- | --- |
| S4-6 | 단위 4개·기존 회귀, 별도 실DB 업그레이드/격리 시험, Paper NBT·30초·dirty 경합·쓰기/연결 실패·새 연결 복원 통과 |
| S4-7 | 단위 6개, Paper+DB 정상 전환·중복/누락/손상/쓰기/적용/stale 거부·로그아웃 저장 통과 |
| 전환 중 source 지연·CAS 충돌 상세 | 제어 future의 단위 검사. 인벤토리 CAS는 실DB 사전 검사, 일반 저장 지연은 Paper에서 별도 확인 |
| S4-8 | 동일 DB를 유지한 두 Paper JVM 영속성·고정 데이터 반복·큐/종료/오류·자원·정리 통과 |
| PlayerInventory 실제 캡처/적용 | API 코드는 컴파일했지만 실제 인증 플레이어에서는 미실행. 런타임은 실제 Bukkit 인벤토리 객체/ItemStack 기반 |
| 실제 계정·게임 행동 | 미실행. 가짜 Player/합성 이벤트로 대체하지 않음. S4-3 보류 유지 |
| DB 재시작·강제 종료 복구·장기 부하·TPS/MSPT·누수 | 미실행. DB 정상 종료는 연결 실패 주입이며 재시작 검증이 아님 |

수동 23514 거부 제거/손상 payload 복원은 이번에 만든 가상 DB의 실패 주입을 정리한 것입니다. 실제 플레이어 DB를 자동 복구하는 기능은 아닙니다. source/target 컨텍스트와 물리 월드 UUID/key는 별도이며 개인 소유권은 story_run_id+player_uuid만 사용합니다.

## 자원·시간·종료

전체 실행 **74.732초**. Paper 최대 두 회 순차, DB 한 개·한 번 기동입니다. 작업 550초와 정리 예산 50초로 600초 상한을 적용했고 확대하지 않았습니다. Xmx1536MiB·RSS 2.5GiB 이상 중단·ActiveProcessorCount=2/nice+10·가용 RAM6GiB/디스크4GiB 검사와 실행 중 RAM 관측을 유지했습니다.

| 관측 대상 | 시작 완료 | 최대 표본 RSS | 관측 CPU 시간 | stop 후 종료 |
| --- | --- | --- | --- | --- |
| 첫 Paper PID 1015492 | 13.962초 | 1,261.03MiB | 63.12초 | 0.595초 |
| 두 번째 Paper PID 1015781 | 8.982초 | 1,142.95MiB | 38.95초 | 3.522초 |
| DB 보조 Java PID 1015417 | 준비 표식 확인, 별도 시간 미집계 | 214.80MiB | 2.37초 | 0.005초 |

최소 표본 가용 RAM은 약 **10.839GiB**입니다. CPU는 마지막 `/proc` 표본의 user+system 누적이며 다중 스레드 합산·표본 오차가 있습니다. DB 보조 JVM의 수치는 PostgreSQL 전체 자원이 아니며 postmaster/worker 총량을 별도 측정하지 않았습니다. 자동 저장의 30,049ms는 캡처 주기 관측으로 정확한 내구성/최대 유실시간 보장이 아닙니다.

모든 시험 JVM exit 0, process group 잔존 없음·PostgreSQL 종료/포트 반환·postmaster.pid 제거를 확인했습니다. 원시 DB/로그/자원 표본은 새 시험 경로에 보존하며 삭제하지 않았습니다. [통합 JSON](../verification/s4-8-result.json)에 PID·단계·슬롯 수량/revision·원시 로그/표본 해시·매핑을 기록했습니다.

## 문제·제약·다음 범위

실행 실패는 없었습니다. 단계 순서 해석을 사용자에게 확인한 후 재개했으며 미실행 단계의 완료를 추정하지 않았습니다. 비대화형 터미널/JOML Unsafe/고정 Paper 업데이트·시험 수동 저장, 합성 metadata API의 컴파일 deprecation 경고는 보존합니다. 의도적 연결 종료의 Hikari 57P01 경고는 예상 거부 결과입니다.

테이블 잠금·trigger·payload 손상은 새 시험 DB의 고정 실패 주입이며 운영 Store가 전체 테이블을 잠그는 정책이 아닙니다. 정리 후 별도 포트 재조회가 샌드박스 netlink 제한으로 한 번 차단됐으나 승인된 실행 환경에서 25585/37263 리스너 부재를 재확인했습니다.

일반 로그인/클릭/드롭·다른 플러그인 변경 감지, 엔더 상자·커서/GUI·body/saddle·경제 realm, 세대가 바뀐 실제 세션·부분적인 PlayerInventory setter 실패, commit 불명·임의 비활성화·업그레이드/부하·강제 종료 복구는 남습니다. 일반 이벤트는 자동 활성화하지 않았습니다. 보류 상태를 새 조정자로 무조건 대체하거나 DB를 빈 인벤토리로 덮으면 안 됩니다. ADR-0003의 로그/백업/관리자 수동 대응과 성능 우선 원칙을 유지합니다.

기존 Docker/Zabbix/Cloudflare/SSH·운영 DB·전역 Java/패키지·기존 게임 데이터·비공개 서사는 변경/접근하지 않았습니다. 전체 S4 완료나 운영 배포로 기록하지 않습니다.

## 문서·보안·최종 산출물 검사

변경 Markdown 11개·상대 링크 229개·표/제목/코드 블록 검사 오류 0건, 일본어/한자 문자 검색과 한국어 문맥 검토의 혼입 0건입니다. `git diff --check`, Python 구문·Monitor의 기존 기본/신규 예산 설정, V001 SHA-256·기존 증거/월드 코드 불변·비활성 기본 설정을 확인했습니다. 최종 단위/회귀 15개가 통과했고 실행 JAR 해시와 최종 JAR 해시가 같습니다. Gitleaks와 Git hook 검사·커밋/푸시 결과는 최종 작업 보고와 Git 이력에서 확인합니다.
