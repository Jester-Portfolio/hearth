# 공통 플랫폼 보관·진단·운영 후보

[검증 기록](../../docs/platform-operations-verification.md)과 [ADR-0035](../../docs/decisions/0035-platform-operations-and-retention.md)을 따른다. 기능을 실행하는 기존 `hearth.platform.admin` 권한을 사용한다. 보존 기간·운영 주기·자원 예산·획득처/밸런스는 선택하지 않았다.

## 설정·작업 이력 보관

```text
/플랫폼 config archive <확정 후보 UUID>
/플랫폼 config inspect <후보 UUID>
/플랫폼 jobs archive <확정 작업 UUID>
/플랫폼 jobs inspect <작업 UUID>
/플랫폼 archive-list config <offset>
/플랫폼 archive-list job <offset>
```

`/플랫폼 admin` → 이력·월드 보관에서 설정/작업 보관 이력을 36개씩 조회한다. 설정 후보 검토의 ‘확정 이력 보관’과 작업 UUID 입력도 같은 API다. 진행/보류 이력은 보관할 수 없다. 원본/후보 파일은 `platform-changes/<UUID>`에서 `platform-archive/config/<UUID>`로 원자 이동하고, E014의 archived 표식은 요청 ID·전후 해시·상태 원본 행을 그대로 유지한다. PG와 파일 이동이 한 트랜잭션이라고 가정하지 않는다. 중단되면 같은 config archive 요청을 재개한다. 파일 누락/충돌은 덮어쓰지 않고 유지보수로 보존한다.

보관 이력은 현재 목록의 4,096개 상한을 소비하지 않는다. config inspect/jobs inspect는 원래 UUID로 보관 이력도 읽는다. 보관한 설정은 재적용/롤백할 수 없으며 새로운 후보를 만든다. 보관된 작업을 같은 ID/입력으로 재요청해도 기존 확정 영수증을 반환하고 효과를 다시 실행하지 않는다. ID를 삭제하거나 재사용 가능하게 만들지 않는다. offline 경제/시장 정책 기록은 live 후보의 현재 목록과 분리한다.

보관은 DB/파일의 영구 삭제나 디스크 여유 확보가 아니다. DB 원본 행과 cold 파일은 계속 증가한다. 장기 삭제·오프사이트 이동에는 별도 보존 정책과 운영 승인이 필요하다. 자동 보존 기간을 임의로 적용하지 않는다.

## 과거 자원 월드

```text
/플랫폼 reset-list
/플랫폼 world-vault-list
/플랫폼 world-vault store <자원 교체 요청 UUID>
/플랫폼 world-vault restore <동일 UUID>
```

보관 GUI에는 `store UUID` 또는 `restore UUID`를 입력한다. 실제 자원 교체 기록 중 RETIRED·`hearth_resource` 생성 월드만 허용한다. 기본/개인 거주, 원래 생존/extra 바인딩, 로드된 월드, ACTIVE/준비 세대가 참조하는 이전 월드, 미확정 작업은 거부한다. 즉시 이전 세대는 활성 세대의 old UUID 참조가 남아 있으므로 보관 대상이 아니다.

메타데이터 UUID와 전체 파일 SHA-256 seal을 확인하고 같은 파일시스템의 서버 루트 `.hearth-resource-archive/<요청 UUID>`로 원자 이동한다. 복원은 원래 dimensions 경로로만 이동한다. 월드를 생성하거나 활성 역할을 과거로 바꾸지 않는다. 파일/원본 행/요청 ID를 삭제하지 않으며 동일한 요청 재개는 두 경로 중 한쪽과 seal이 일치할 때만 허용한다. 양쪽 존재·양쪽 부재·변조·링크·특수 파일은 보존/거부한다. 기술 검사 상한은 100,000개 파일/디렉터리다.

E014의 PREPARED → STORED → RESTORING → RESTORED와 revision CAS를 사용한다. 파일 이동 후 PG 확정 전 중단도 새 JVM에서 유지보수/예약 차단으로 복원한다. 같은 요청의 store/restore로 마무리하며 config recover로 우회하지 않는다. 목록은 보류 우선 최대128개이며 과거 UUID 직접 지정은 별도로 지원한다. 보관된 생성 세대는 hot 자원 조회 상한에서 분리한다.

## 파일 작업·상태 관측

설정 이력 전체 검증, 후보 읽기/보존/원자 공개, 상세 조회와 월드 seal/이동은 순서 보장 worker 한 개·대기32개로 처리한다. Paper 설정 검증/API는 owner 스레드로 돌아온다. 종료 drain은 설정/파일 작업이 끝나야 허용한다. 게임 tick에 이력 전체 조회/DB 조회를 추가하지 않는다. Paper에 실제 적용하는 제한된 설정 파싱/플러그인 reload는 기존 주 스레드 계약을 따른다.

기존 상태 보고의 format2는 platform의 유한 카운터(enabled/ready/maintenance/config_busy/config_held/vault_held/job_held/io_pending/sales/generation/reset_pending)를 포함한다. controller_ready는 플랫폼 보류도 반영한다. format1과 종료 보고는 기존 방식으로 읽는다. 개인 ID·파일 원문·주소·자격 증명을 지표에 넣지 않는다. `runtime_health.py`와 기존 Zabbix Text 마스터의 `$.platform.*` 종속 항목으로 읽을 수 있다. Zabbix 설정/서비스는 자동 변경하지 않는다.

`platform_soak.py`는 기존 실제 관측을 JSONL로 저장하고 지정한 TPS/MSPT/힙 기준·현재 nonce·보고서 나이를 검사한다. 기간1~550초·간격1~60초다. 같은 신선한 보고서를 중복 표본으로 세지 않으며 stale/보류/읽기 실패는 FAIL이다. 접속자나 게임 효과/거래를 합성하지 않는다. 장시간 수용은 운영 승인을 받은 관측 도구/기존 감시 체계에서 별도로 수행한다.

## 네트워크 배포 후보

[빈 검토 양식](release-review.example.json)에 실제 운영자가 검토한 값을 입력한다. proxy/lobby/economy/postgres의 서로 다른 준비된 경로, 고정 이미지 digest, CPU·RAM·힙·프로세스/정지 예산, localhost 포트, 백업 주기/보존·오프사이트/RPO/RTO를 반드시 지정한다. 테스트 예산을 운영값으로 복사하지 않는다.

```sh
python3 scripts/network_release.py plan /PRIVATE/review.json --output /PRIVATE/fresh-candidate
python3 scripts/network_release.py inspect /PRIVATE/fresh-candidate/release.json
# 서비스 실행 없이 구문만 검사; 기동 승인 값은 아니다.
HEARTH_NETWORK_APPROVAL=configuration-validation-only docker compose \
  -f /PRIVATE/fresh-candidate/compose.json config --quiet
```

입력은 미리 준비한 정상 네트워크만 받는다. backend server.properties는 localhost/online-mode=false, Paper modern forwarding과 proxy의 정품/modern 전달, 양쪽 서버 주소/포트, 동일 Hearth JAR·forwarding/relay·DB 환경 값을 대조한다. Hearth 생성기의 scalar TOML 범위만 지원하고 복잡한 임의 TOML은 추측하지 않는다. 각 Java 서비스의 준비된 runtime.env에는 HEARTH_VELOCITY_SECRET/HEARTH_NETWORK_SECRET/HEARTH_DB_PASSWORD 세 키를 권한0600으로 둔다. 비밀번호 환경 변수 이름이 다른 구성은 먼저 검토한 어댑터가 필요하다. 비밀은 후보 JSON이나 Git에 복사하지 않고 해시/원래 경로만 묶는다.

출력은 compose.json, release.json, PG를 포함한 역할별 입력 해시/기동 검사, 백업 정책 명세다. format2는 Compose dotenv 대신 검토한 runtime.env를 원문으로 전달한다. 네 역할의 정지 snapshot/새 경로 복원은 [복구 연결](network-recovery.md)을 따른다. Compose 프로젝트 이름은 검증한 network UUID로 고정해 다른 네트워크와 구별한다. PGDATA는 기존 준비본만 마운트하고 초기화/데이터 변환을 하지 않는다. Java 이미지에는 JDK25와 /bin/sh가, PG 이미지에는 기존 버전 PostgreSQL 도구가 필요하다. 실제 이미지 내용·빌드/컨테이너 실행은 별도 수용이다. PG 수신 준비 후 backend, 그다음 proxy를 시작하는 의존 관계를 담지만 service_started를 게임 준비 완료로 보지 않는다.

실제 클라이언트·부하·복구·릴리스 회귀 수용이 모두 기록되지 않으면 Java 기동 검사가 거부한다. 별도 운영 승인 후 HEARTH_NETWORK_APPROVAL=reviewed-and-approved가 있어야 하며 비root/JDK25·준비된 EULA·불변 입력 해시를 검사하고 매 기동 nonce를 만든다. 정상 drain/일관 백업·격리 복원과 짝이 맞는 이전 JAR/설정/전체 Paper+PG 롤백 사본을 마련한 뒤 전환한다. 이 생성기는 Docker 빌드/up, 프로세스 재시작, 백업 스케줄 설치, Zabbix/SSH/방화벽 변경을 실행하지 않는다.
