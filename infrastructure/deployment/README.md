# 운영 검토용 구성

이 디렉터리는 **미적용 후보**다. Docker 이미지 선택/빌드/실행, 운영 서비스·Zabbix·네트워크 적용을 승인하거나 완료한 기록이 아니다. 운영 예산·이미지 digest·포트·소유 UID/GID·보존 기간·RPO/RTO·AI 품질/성능 수용은 별도로 정한다. 시험의 자원 상한을 운영 기본값으로 복사하지 않는다.

## 구성과 준비

`compose.yml`은 Linux host network에서 Paper와 PostgreSQL을 실행하는 후보다. 스토리의 기존 로컬 Ollama 주소 제한을 유지한다. host network는 호스트 네트워크 접근 범위가 넓으므로 별도 토폴로지 검토가 필요하다. Paper/PG를 각각 localhost에만 바인딩하며 SSH·방화벽·Cloudflare를 수정하지 않는다. 경제/스토리는 서로 다른 프로젝트·전체 데이터 경로·PG 포트를 사용한다. AI는 이 Compose가 설치·기동·재시작하지 않는다.

`review.env.example`은 빈 양식이다. 운영자가 정한 자원·경로·이미지 digest를 채우며 실제 `.env`/비밀은 저장소에 넣지 않는다. Java 이미지에는 **Java 25 JDK**, `/bin/sh`, 기본 POSIX 도구와 `grep`, `sed`, `tr`, `wc`, `head`가 필요하다. PG 이미지는 기존 데이터의 major와 일치하고 `postgres`·`pg_isready`가 PATH에 있어야 한다. 이미지 내 비특권 UID가 데이터/비밀을 읽을 수 있는지도 검토한다. 두 진입점은 root를 거부한다.

Paper 데이터는 기존 승인 절차로 준비한 **전체 서버 사본**을 사용한다. 기본 `level-name=world`, 기존 EULA 동의, 온라인 인증/화이트리스트, loopback 게임/DB 주소와 검토한 실제 콘텐츠를 요구한다. JAR 이름은 `plugins/Hearth.jar`와 별도로 지정한 Paper JAR 경로다. WorldEdit/WorldGuard 등 실제 사용 플러그인 JAR과 필요한 라이브러리/캐시도 사전에 검토해 둔다. 자동 다운로드나 새 월드 생성으로 누락을 메우지 않는다.

PGDATA는 같은 시점의 정지 백업에서 복구한 기존 데이터만 사용한다. PG 진입점은 버전·기존 `postmaster.pid`를 확인하며 `initdb`, `chown`, 스키마/계정 생성, 비밀번호 변경을 하지 않는다. 기존 인증 설정/역할과 Paper의 환경 변수 비밀번호가 일치해야 한다. 비밀번호 파일은 제한된 소유권/읽기 권한의 ASCII 한 줄(마지막 개행 1개 선택), 1~4096바이트다. Compose의 파일 기반 secrets는 UID/GID 재매핑을 보장하지 않으므로 실제 이미지 UID로 읽기 권한을 확인한다. 전역 읽기 권한을 부여해 해결하지 않는다.

## 오프라인 설정 검토

기존 서비스가 아닌 준비 사본을 대상으로, 서버/DB/모델을 시작하지 않고 실행한다. 출력 디렉터리는 기존에 없어야 하며 서버 데이터 바깥의 사용자 소유 경로를 지정한다.

```sh
gradle operatorConfigAudit \
  -PauditRoot=/REVIEWED/PREPARED_SERVER \
  -PauditOutput=/REVIEWED/NEW_AUDIT_OUTPUT \
  -PauditServerJar=server.jar
```

기존 프로젝트의 Java 25/Gradle 실행 환경을 사용한다. 추가 정적 설정은 `-PauditInclude=config/paper-global.yml,plugins/WorldGuard/config.yml`처럼 명시한다. 검토한 콘텐츠 파서/해시, 월드 소유 표식·메타데이터/진입 청크의 존재, 인증/모드/AI 구성과 기존 미정리 runtime 표식을 검사한다. 실제 PG 행·월드 UUID 내부 값·WorldGuard 권한/구역·현재 AI 모델 적재는 확인하지 않는다. 잘못된 점검을 성공으로 바꾸려고 원본 설정/표식을 고치지 않는다.

출력 `deployment.properties`의 SHA-256을 별도 승인 기록과 `HEARTH_MANIFEST_SHA256`에 고정한다. 이 파일은 정적 JAR·Hearth 설정/콘텐츠·선택 추가 파일·소유 표식 목록인 `deployment.sha256`의 해시를 고정한다. 시작 직전 이미지에 포함된 JDK 전용 검사기가 이를 다시 확인한다. `server.properties`·EULA는 Paper가 다시 쓸 수 있어 파일 전체 해시 대신 필수 정책을 재검사한다. 월드 청크/PG/화이트리스트/WG 구역의 동적 내용은 이 정적 manifest로 무결성을 증명하지 않는다. 정지 백업/복원 검사와 정상 실행의 실제 연결·권한 검사를 함께 사용한다.

이미지에는 검토한 `OperatorLaunchCheck` 코드와 진입점만 넣는다. 해당 Dockerfile 전용 `.dockerignore`는 빌드 문맥에서 이 두 입력만 허용하고 `.git`·환경 파일·월드/DB를 제외한다. 플러그인 JAR을 기동 검사 코드로 먼저 실행하지 않는다. 별도 Docker 변경 승인 뒤 저장소 루트에서 `infrastructure/deployment/Dockerfile`과 검토한 `JAVA25_IMAGE` digest로 빌드하고 결과 image digest를 기록한다. 이 저장소 작업에서는 이미지를 빌드/실행하지 않았다. 구문 검사는 다음처럼 **데몬 변경 없이** 할 수 있다.

```sh
docker compose --env-file /REVIEWED/PRIVATE.env \
  -f infrastructure/deployment/compose.yml config --quiet
```

`HEARTH_OPERATING_APPROVAL=reviewed-and-approved`는 별도 운영 승인 후에만 넣는다. 코드상의 명시 표식이며 사람의 승인을 대신하지 않는다. `restart: no`로 비정상 종료/HOLD의 자동 재시작 반복을 막는다. 정상 재시작도 기존 명시적 drain→저장/언로드 완료→종료 순서를 따른다.

## 상태 관측

Hearth의 `runtime-observation-seconds`는 기본 0(비활성), 1~3600초다. 후보 감사는 관측 활성화를 요구한다. 로그 `HEARTH_RUNTIME_REPORT`에는 기동 nonce, 모드/컨트롤러 단계, 월드/HOLD 수, TPS/MSPT/힙과 AI 준비 **이력**만 기록한다. 개인 식별자·질문·답변·DB 주소/비밀번호는 받지 않는다. `controller_ready`는 READY와 집계한 lifecycle HOLD 부재를 뜻하며 모든 게임 효과·모델·DB 종단 간 성공을 보장하지 않는다. `ai_preparation_recorded`는 과거 준비 완료의 메모리 기록으로 현재 서버 가동/모델 적재/응답 품질을 의미하지 않는다. 준비/추론은 기존 `hearthai` 명시 경로를 따른다.

Paper 진입점이 매 기동에 새 `/run/hearth/boot-id`를 만들고 JVM에 전달한다. Python이 있는 관측 호스트에서 이 nonce 파일을 현재 인스턴스와 연결해 다음처럼 읽는다.

```sh
python3 scripts/runtime_health.py --log /REVIEWED/SERVER/logs/latest.log \
  --boot-file /REVIEWED/CURRENT_BOOT_ID --mode story --max-age REVIEWED_SECONDS
```

검사기는 최근 최대 1MiB의 완결 로그만 읽고, 서버 메인 스레드의 정확한 플러그인 접두사·스키마·기동 nonce·시간·집계 불변식을 확인한다. 오래된 READY, 다른 기동, 깨진 최신 보고서, 심볼릭 링크, 경고/종료는 정상 READY로 처리하지 않는다. 종료 코드 0=현재 컨트롤러 READY, 1=관측 성공/비준비, 2=관측 불가다. 파일/파싱 실패도 민감한 원문 대신 코드만 출력한다. 관측 호스트의 PID와 컨테이너 PID를 동일시하지 않는다.

`zabbix-userparameter.example.conf`는 고정 경로의 Text 마스터 항목 후보다. `$.available`, `$.controller_ready`, `$.phase`, `$.held_worlds`, `$.tps`, `$.mspt`, `$.heap_used_bytes` 등을 종속 항목으로 분리할 수 있다. 실제 Zabbix 버전·권한·nonce 전달 경로·시간 기준·지연/무응답 경보를 검토한 뒤 적용한다. nonce를 위해 Docker 소켓/sudo/전체 데이터 읽기 권한을 주지 않는다. agent 설정은 설치/수정/재로드하지 않았다. Compose의 `pg_isready`는 PG 접속 수신 여부만 확인한다. CPU/RSS/GPU·디스크·PG/AI 프로세스 관측과 종단 간 수용은 기존 관측을 유지한다.

## 승인·전환·롤백

1. 운영 예산·토폴로지·이미지/UID·포트 충돌·비밀 권한·로그 보존과 백업/오프사이트 정책을 확정한다. 실제 플레이·AI 품질/목표 사용량과 릴리스 전체 회귀의 미완료를 검토한다.
2. 정지한 전체 Paper/PG의 일치 백업을 보존하고 새 경로 복원 검사, 오프라인 audit, 정상 격리 기동/저장 종료를 완료한다. 백업 도구/한계는 [기존 기록](../../docs/offline-backup-verification.md)을 따른다.
3. 정확한 버전·대상·변경·중단 시간·롤백 사본을 제시해 **별도 운영 승인**을 받는다. 이 저장소의 사전 작업은 기존 서비스를 중단하지 않는다.
4. 승인된 전환에서 기존 관리 명령으로 입장을 닫고 drain 완료를 확인한 뒤 중단한다. 불명 commit/HOLD·미정리 표식이면 전환을 보류하고 원본을 보존한다. 표식 삭제/자동 재지급으로 우회하지 않는다.
5. 승인된 후보를 새 경로에서 기동하고 인증/격리/PG/AI·팩·저장/실제 클라이언트 수용을 확인한다. 불합격이면 입장을 닫고 drain/종료한 뒤 **짝이 맞는 이전 JAR·설정·전체 월드/PG** 사본으로 복귀한다. 새/이전 DB와 월드를 섞지 않는다. 기존 경로를 덮어쓰거나 Git 이력을 재작성하지 않는다.

공식 기준: [Compose 서비스](https://docs.docker.com/reference/compose-file/services/), [host network 동작](https://docs.docker.com/compose/how-tos/networking/), [Zabbix User parameters](https://www.zabbix.com/documentation/current/en/manual/config/items/userparameters). 설치된 CLI의 구문 PASS와 이미지/운영 수용을 구분한다.

## 경제 멀티서버 후보

기존 2서비스 검토 후보와 별도로 [network_release.py 및 검토 양식](../network/platform-operations.md#네트워크-배포-후보)은 준비된 proxy/lobby/economy/postgres를 묶는다. 불변 원본/생성 파일·현대 인증 전달·이미지/예산·백업 정책을 검사하고 수용 미완료/별도 승인 없이는 Java 기동을 거부한다. Docker compose config만 격리 예제로 확인했으며 이미지 빌드/서비스 적용은 진행하지 않았다. format2 상태 보고의 `$.platform.*`와 controller_ready는 설정/월드/작업 보류도 반영한다. 실제 Zabbix 설치/수집·운영 백업 스케줄은 별도다.

format2 네트워크 후보의 PG도 역할별 수용/검토 파일 SHA를 먼저 검사한다. runtime.env는 dotenv 보간 없이 검토한 원문으로 전달한다. 네 역할의 일관 보존/새 경로 복원과 수용 초기화는 [네트워크 복구](../network/network-recovery.md)를 따른다.

## Hearth 전용 PG의 독립 구성

[`compose-postgres.yml`](compose-postgres.yml)은 준비된 Hearth PG 하나만 실행한다. 기존 Zabbix의 컨테이너·데이터·계정·포트를 재사용하지 않으며, Paper/AI 기동이나 게임 데이터 이전을 수행하지 않는다. 기존 [`postgres-entrypoint.sh`](postgres-entrypoint.sh)의 비root·명시 승인·기존 PGDATA·주 버전·중복 기동 검사를 재사용한다. root filesystem은 읽기 전용이고 PG는 localhost에만 수신한다. 새로운 PGDATA 생성과 계정 준비는 명시 승인된 별도 초기화 작업이며 이 진입점이 자동으로 수행하지 않는다.

[`Dockerfile.postgres`](Dockerfile.postgres)는 검토한 **Alpine PostgreSQL base digest**와 양수 UID/GID를 받아 `hearth_pg` 비특권 사용자를 만든다. 최종 이미지 ID/digest를 고정하며, 데이터와 UID/GID가 일치해야 한다. 기존 단일 소유자 [정지 백업](../backup/README.md)을 사용할 때는 Paper/PG 파일 소유권도 함께 맞춘다. 기존 데이터의 재귀 chown이나 다른 주 버전 데이터의 재사용을 자동화하지 않는다.

전용 PG에도 관리자와 게임 접속 역할을 구분한다. 게임 역할은 해당 DB의 소유/마이그레이션 권한을 가지되 클러스터 슈퍼유저·역할 생성·복제 권한을 주지 않는 구성을 검토한다. `hearth_pg`와 local peer 인증을 사용하는 초기화 구성에 맞춰 상태 확인을 제공하며, `pg_isready`만으로 게임 계정의 실제 인증·DDL·거래 성공을 판정하지 않는다. 자격 증명은 저장소 밖의 접근 제한 파일로 관리한다.

운영 경로·이미지·포트·비밀·자원 값은 실제 검토 파일로 전달한다. 이 구성은 CPU/RAM/PID hard limit을 임의 선정하지 않으며, 운영 부하와 전체 호스트 예산을 측정한 뒤 확정한다. `restart: no`는 기존 준비/수동 복구 경계를 유지한다. 정식 네트워크 공개·자동 재시작 정책·전체 백업/복원 수용은 별도 운영 단계다. 네트워크 전체 후보로 전환할 때는 같은 PGDATA를 두 서비스에서 동시에 열지 않는다.
