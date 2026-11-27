# 프로젝트 전용 PostgreSQL 정본과 연결

2026-11-27 확인한 Hearth 경제용 PG 정보는 [`hearth-postgres.json`](hearth-postgres.json)을 정본으로 사용한다. **접속값·경로·이미지 식별자는 Git에 보존하고 비밀번호 값·PGDATA·백업은 보존하지 않는다.** 이 프로필은 관리자 권한 설정이나 게임 서버 기동 승인을 포함하지 않는다.

| 항목 | 현재 값 |
| --- | --- |
| 서버/DB | `127.0.0.1:5433` / `hearth_economy` |
| 게임 역할 | `hearth`, 비슈퍼유저·해당 DB 소유/마이그레이션 권한 |
| 비밀번호 주입 | `HEARTH_DB_PASSWORD`, 값은 `/home/hearth-services/postgres/secrets/app.password` |
| PostgreSQL | 18.7 Alpine, base digest와 빌드된 로컬 image ID는 JSON 참조 |
| Compose 프로젝트 | `hearth-project-db` |
| 실행 사용자 | UID/GID `1000:1000` |
| 데이터 | `/home/hearth-services/postgres/data` |
| 현재 Compose/환경 파일 | `/home/hearth-services/postgres/compose.yml`, 같은 디렉터리 `runtime.env` |
| 백업 | `/home/hearth-services/postgres/backups` |
| 재시작/자원 정책 | 수동 재시작, CPU/RAM/PID 운영 상한 미선정 |

Zabbix PG와 클러스터·데이터·역할·Compose 프로젝트를 공유하지 않는다. WorldGuard 보호 구역은 기존 파일 저장을 유지하며, 파일 저장 때문에 JDBC 연결을 빌리지 않는다. Hearth 경제/거주/거래/인챈트 원장은 이 전용 PG를 사용한다. AuraSkills의 사용자 저장 방식을 이 작업에서 PG로 전환하지 않는다.

## 기존 설정 생성기 연결

기존 [`survival_config.py`](../../scripts/survival_config.py)에 이 프로필을 전달한다. 예시는 새 0600 파일만 만들며 서버·월드·DB를 변경하지 않는다. 이미 준비한 서버에는 기존 group/realm을 유지한 채 검토한 `survival-economy` 연결 필드만 병합한다. 새 group/realm을 만들어 기존 데이터에 덮지 않는다.

```sh
python3 scripts/survival_config.py --output /PRIVATE/NEW_GAME_CONFIG.yml \
  --group-id EXISTING_GROUP_UUID --with-economy --economy-realm EXISTING_REALM_UUID \
  --database-profile infrastructure/deployment/hearth-postgres.json
```

`--db-host/--db-port/--db-name/--db-user/--password-environment`와 프로필을 함께 지정하면 거부한다. 프로필 없이 직접 입력하는 기존 사용법과 기본 포트 5432는 유지한다. 프로필 사용 시 포트는 5433이며 `--allow-create`·`--allow-new-realm`은 자동 활성화하지 않는다. 아직 realm/account가 없는 현재 전용 DB의 최초 게임 준비는 별도 설정/월드 준비 단계다.

생성 YAML은 기존 [네트워크 설정 생성](../network/README.md)의 `--economy-config` 입력으로 전달할 수 있다. 기존 기본 플러그인 설정은 비활성을 유지한다. 로비·스토리를 이 경제 DB에 자동 연결하지 않는다.

## Compose와 자격 증명

같은 프로필로 [`compose-postgres.yml`](compose-postgres.yml)의 환경 입력을 만든다. 출력은 기존 파일/링크를 덮지 않으며 승인 표식·비밀번호를 포함하지 않는다.

```sh
python3 scripts/project_database.py \
  --profile infrastructure/deployment/hearth-postgres.json \
  --output-env /PRIVATE/NEW_PG.env
HEARTH_OPERATING_APPROVAL=configuration-validation-only docker compose \
  --env-file /PRIVATE/NEW_PG.env -f infrastructure/deployment/compose-postgres.yml config --quiet
```

실제 서비스의 기존 승인 표식은 private 운영 기록에 유지한다. 현재 `runtime.env`의 비밀 없는 PG 값과 Git 프로필이 일치함을 확인했다. 새 환경 파일로 현재 서비스를 자동 재생성하거나 승인 표식을 자동 발급하지 않는다.

기존 [Paper 진입점](paper-entrypoint.sh)은 비밀번호 파일을 Docker secret으로 읽어 검토한 환경 변수에 주입한다. 기존 네트워크 후보는 세 역할의 private 0600 `runtime.env`에서 `HEARTH_DB_PASSWORD`를 읽는다. 이 값은 프로필의 외부 비밀번호 파일에서 운영 준비 과정에 주입하고 출력·Git 커밋하지 않는다. `project_database.py`와 생존 설정 생성기는 비밀번호 파일을 읽거나 복사하지 않는다.

로컬 image ID는 현재 Docker 호스트에서만 사용할 수 있다. 새 호스트에서는 JSON의 고정 base digest와 [`Dockerfile.postgres`](Dockerfile.postgres)로 빌드하고 실제 image ID를 검토·갱신한다. `latest` pull이나 다른 이미지 ID로의 자동 대체는 하지 않는다. 데이터 주 버전/소유권과 파일 변경을 검토하며 기동 중 PGDATA에 재초기화·재귀 chown을 하지 않는다.

## 네트워크 후보와 백업 경계

[`network_release.py`](../../scripts/network_release.py)는 정지한 네 역할의 일치 사본과 검토한 자원/수용 정책을 요구한다. 실행 중인 전용 PGDATA를 그대로 후보로 가져오거나 같은 데이터를 두 PG 컨테이너에서 열지 않는다. 네트워크 전체로 관리 주체를 전환할 때는 Java drain → PG 정상 종료 → 일치 백업/새 경로 복원 → 후보 검토 → 한 소유자만 기동 순서를 따른다. 후보의 PG 포트는 이 프로필의 5433, 경제 YAML의 연결과 일치시킨다. 네트워크 후보의 repository digest 요구와 CPU/RAM/백업 예산을 로컬 image ID나 유휴 측정으로 대체하지 않는다. 이 작업은 전체 게임 네트워크 배포를 실행하지 않았다.

[정지 백업](../backup/README.md)의 `--postgres`는 위 PGDATA를 가리킨다. 같은 시점의 전체 Paper와 함께 정상 종료 후 실행하며, 여러 역할은 [네트워크 복구](../network/network-recovery.md)를 따른다. 자격 증명 파일은 별도 안전 보관하거나 검토한 `--assets`로 포함한다. 현재 초기 `pg_dump` 아카이브는 DB만 보존하며 전체 월드/WorldGuard/아이템과 일치하는 게임 복원본이 아니다. 실제 복원·정기/오프사이트 정책은 아직 미수용이다.

검증 근거: [저장 I/O·전용 PG 기록](../../docs/storage-io-and-dedicated-postgres-verification.md), [프로필 통합 검사](../../verification/project-database-profile-result.json). 프로필·기존 생성기 Python 13개, 생성 Compose 구문 및 현재 실행 환경과의 일치 검사를 통과했다. 현재 접속·재기동·보존 검사와 실제 플레이·지속 부하·정식 게임 네트워크 공개는 구분한다.
