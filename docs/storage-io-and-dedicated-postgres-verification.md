# WorldGuard 저장 I/O와 Hearth 전용 PostgreSQL 구성

2026-11-26 구현, 2026-11-27 전용 PG 실행 확인. WorldGuard 저장이 사용하지 않는 Hearth JDBC 연결과 SQL 작업자를 점유하던 경로를 분리했다. 전용 PG 단독 구성도 추가했다. 실제 클라이언트 수용·지속 부하·정식 네트워크 온에어를 뜻하지 않는다.

## 변경과 보존 경계

`HousingProtection`과 `RpgObjects`의 WorldGuard 저장은 `PostgresProgressStore.file()`을 사용한다. JDBC 연결을 빌리지 않는 전용 작업자 1개·대기열 16개의 직렬 실행이며, SQL 작업자 2개·풀 최대 2개·SQL 대기열 16개는 그대로다. 작업 실패는 기존 완료 단계/실패 통지로 전달하고 포화는 `FILE_QUEUE_FULL`, 종료 뒤 접수는 `CLOSED`로 거부한다.

종료 시 두 실행기를 모두 닫고 기존 총 30초 한도 안에서 접수한 저장까지 기다린다. 실패·시간 초과를 정상 종료로 바꾸지 않는다. 거주·보호의 PG 정본, WorldGuard 적용 순서·오류 HOLD·runtime lease와 데이터 형식은 유지한다. 파일 저장을 Paper 메인 스레드로 옮기거나 WorldGuard 플러그인의 저장 드라이버를 바꾸지 않았다.

[`compose-postgres.yml`](../infrastructure/deployment/compose-postgres.yml)은 준비된 Hearth PG만 기동한다. 비root·localhost·읽기 전용 루트·capability 제거·기존 PGDATA/주 버전/중복 기동 검사를 사용하며, 로컬 불변 이미지의 자동 pull을 금지한다. [`Dockerfile.postgres`](../infrastructure/deployment/Dockerfile.postgres)는 검토한 Alpine base digest에서 데이터 소유권에 맞는 비특권 UID/GID를 준비한다. CPU/RAM/PID 운영 상한은 유휴 사용량으로 임의 선정하지 않는다. [운영 구성 설명](../infrastructure/deployment/README.md#hearth-전용-pg의-독립-구성)을 따른다.

## 최종 코드 검증

| 검사 | 결과 | 확인 범위 |
| --- | --- | --- |
| `PostgresProgressStoreTest` | PASS, 9개 | 파일 저장 JDBC 0회, SQL/파일 작업자 독립, 직렬 순서, 유한 큐 거부, 종료 대기/접수 차단, 실패 원문 비공개와 기존 SQL 경계 |
| E010 실제 격리 PG | PASS, 13개 | 동시 CAS·실제 SQL 실패·권한/원본 보존. 최종 코드의 첫 시험에서 통과한 동일 DB phase를 재사용 |
| 실제 Paper/WorldGuard 두 JVM | PASS, 59개 | 준비 29개·재시작 복원 30개. 두 Paper와 PG 정상 종료, 두 Paper 모두 정상 drain, source 해시/포트 정리 |
| 전용 PG Compose CLI | PASS | 단독 서비스, 비root/읽기 전용/기존 bind 경로, 불변 로컬 이미지 ID, CPU/RAM 상한 미지정 |

최종 JAR SHA-256: `31b6c0c060caa8dbc1491443b6fa3895bbab2cef845ae9edff4f56d4ceeda2f1`.

두 JVM 시험은 `/home/server/hearth-tmp/hearth-native-economy-run.1idr795o`의 `aura-trial-result.json`을 따른다. 81.455초, PG/두 Paper 모두 exit 0·정리 오류 없음이다. 기존 CPU 2개/nice 10·총 600초/작업 550초·Paper 최대 2회·합산 RSS 6GiB·localhost·사용자 소유 격리 경계를 유지했다. 가짜 Player나 실제 접속/클릭을 사용하지 않았다.

첫 시험 `/home/server/hearth-tmp/hearth-native-economy-run.ghrwfctd`는 PG 13개를 통과했으나 시험 구성에 BetterHud를 누락해 후반 검증에서 `NoClassDefFoundError`가 발생했다. FAIL·로그·원본을 보존했다. 고정 BetterHud를 포함한 새 시험 경로에서 두 Paper JVM만 재시험했다. `--rpg-db-evidence`는 E010/RpgStore/DB fixture hash·13개 성공·정상 PG 종료를 대조했으며, 실패한 전체 시험을 PASS로 변경하지 않았다.

## 전용 PG 실행 확인

2026-11-27 승인된 별도 PG 구성에서 PostgreSQL 18.7 Alpine 고정 digest 기반 비root 이미지를 빌드하고 단독 Compose를 실행했다. 새 클러스터에 Hearth 전용 비슈퍼유저 계정과 DB를 만들었으며, 최종 JAR의 실제 JDBC 경로로 E001–E014 마이그레이션·계정 권한·쓰기/읽기/롤백을 확인했다. 게임 realm/account는 0개로 유지했으며 시험 데이터를 운영 데이터로 승격하지 않았다.

새 PG만 정상 종료·재기동한 뒤 기존 컨테이너와 데이터 bind, 스키마 버전/체크섬 검증 및 애플리케이션 접속을 확인했다. 후속 검사 29개는 정상 기동, localhost 리스너, 비밀번호 없는 TCP 접속 거부, SCRAM 호스트 인증, 데이터 체크섬, fsync/full_page_writes/synchronous_commit, 비root/읽기 전용 루트/capability 제거, 비밀 파일 권한 및 기존 서비스 보존을 포함한다. CPU/RAM 운영 상한과 자동 재시작은 활성화하지 않았다.

초기 기동은 제한적인 umask로 실행 스크립트 디렉터리가 0700이 되어 거부됐다. 해당 디렉터리 접근 권한을 명시적으로 수정하고 기존 클러스터를 재초기화하지 않은 채 재개했다. 보존 검사의 Docker 마운트 목록 비교도 순서 대신 destination 기준으로 정규화했다. 최초 실패 기록은 보존했다.

초기 스키마의 custom-format 백업을 생성하고 `pg_restore --list`로 아카이브 목록을 확인했다. 이 백업의 실제 복원 시험이나 정기 백업 스케줄 설정은 수행하지 않았다. 호스트별 경로·관리자 권한·자격 증명·Linux 보안 변경과 상세 운영 기록은 Git 밖에 보관한다.

## 미검증·운영 분리

실제 플레이·다인/봇 지속 부하와 지연 개선율은 측정하지 않았다. 이 변경의 직접 근거는 불필요한 JDBC 연결 획득 제거와 SQL/파일 작업 격리·정상 저장/종료 검증이다. 전용 PG 실행 확인은 정식 Paper/프록시 배포·게임 데이터 이관·게임 서버 공개 승인을 의미하지 않는다. 자동 재시작 정책·운영 예산/백업 주기 선정은 별도다.
