# 인증 플레이어 보호·세션 수동 시험 절차

대상은 [세션 구현](player-session-verification.md)의 **별도 수동 모드**입니다. 2026-10-26 정상 인증 클라이언트 한 명으로 아래 범위의 실제 시험을 수행했습니다. 기존 S4-3 독립 보호 도구의240초와 구분해 이번 세션/DB 동반 시험은 묶음별600초·Paper 순차 최대2회/PG1개의 한도를 사용했습니다. S4-3 전체 및 전체 S4 완료는 아닙니다.

## 실제 시험 결과 — 2026-10-26

기준 `c261779530a4e027b39a3698fbbb61912555657a`, main·작업 트리/스테이징 깨끗함, fetch 후 origin/main과 일치한 상태에서 시작했습니다. 사용자가 정상 인증 프로필과 기존 SSH 로컬 전달 준비를 확인했습니다. 새 경로에서 whitelist·일반 권한·fixture를 준비한 뒤 실제 접속을 안내했습니다. 게임 JAR은 이전 최종 자동 검증의 SHA-256 `129ef136102fa6b2a96f173c433bad789d72244ce3ce6755818363ad036a2d05` 그대로입니다. 계정 이름/UUID/IP·채팅 원문은 공개 요약에서 제외하고, 아래 `시험자`는 동일한 인증 클라이언트 한 명입니다.

| 범위 | 실제 관측과 판정 |
| --- | --- |
| 인증 접속·기존 인벤토리·입장 | 초기32개/revision0, 실제 PlayerInventory 적용·teleportAsync 성공 후 ACTIVE/playable=true 확인 |
| A 일반 보호·허용 건축 | op=false/bypass=false. 보호 설치/파괴 취소·최종 블록 불변, 허용 설치/파괴 성공·최종 STONE/AIR 확인 |
| 중첩 보호 | 첫 시도 (8,64,8) 파괴 차단, 재시험 두 번째 JVM 같은 위치 설치 차단·AIR 유지 |
| 실제 dirty 저장 | 첫 시도 추가 설치 뒤 메모리21·dirty=true/DB22 revision4, 이후 DB21 revision5 저장 확인 |
| 빠른 재접속·IDLE 취소 | 첫 시도 종료 저장 뒤 약6초 재접속,22개/revision4 유지·언로드 취소 |
| IDLE 언로드·재입장 |10초 뒤 저장/언로드/OFFLINE(epoch2), 재입장 ACTIVE(epoch3). 이후 추가 변경까지 기존 DB 소유권 유지 |
| 관리자 bypass | 같은 보호 블록 (4,64,4)이 일반 권한에서는 취소, op=true/bypass=true에서는 제거. 이후 op=false/bypass=false 복귀 확인. 해제 직후 동일 블록 재거부·관리자 중첩 행동은 미실행 |
| 두 Paper JVM 영속성 | 재시험 첫 JVM에서 (6,64,6)에 한 개 설치·31개/revision1 저장. 두 번째 JVM에서 신규 생성 없이 같은 UUID/key·WG 구역·31개/revision1 복원. 실제 파괴 이벤트 previous=STONE/final=AIR로 기존 설치 블록 보존 확인 |
| 종료 | 수정본 두 JVM/DB exit0, 저장 장벽·풀/worker 종료·소유 프로세스 그룹/게임 및 DB 포트 반환 확인 |

[실제 관측 JSON](../verification/player-session-manual-result.json)에 시도별 원본 해시·자원·이벤트와 DB 대조를 기록했습니다. 원본 runner의 `MANUAL_CLOSED_UNVERIFIED`는 행동 자동 심사를 하지 않는다는 뜻이며 위 판정은 실제 ACTION/STATE·DB·세션 로그를 별도로 대조한 결과입니다. 원본의 고정 `not_executed` 플레이어 문구도 소급 수정하지 않았으며, 이후 도구 출력은 `NOT_AUTOMATICALLY_ASSESSED`와 미실행을 구분하도록 정정했습니다.

### 오류·재시험과 자원

첫 시도 `/tmp/hearth-s4-11._x41of5k`는477.280초, Paper1회/DB1개가 exit0로 끝났지만 종료 직후 포트 검사에서 `EADDRINUSE`가 발생해 두 번째 JVM을 시작하지 못했습니다. 원본 결과는 **FAIL**로 보존합니다. 이후 소유 PID/그룹·DB pidfile 부재와 포트 반환을 확인했습니다. 실제 TIME_WAIT를 당시 직접 관측하지는 못했지만 별도 loopback 연결에서 이전 bind 검사 오탐을 재현했습니다. `SO_REUSEADDR`를 사용하는 포트 검사로 수정해 종료 연결의 재사용을 허용하면서 실제 LISTEN은 옵션 유무와 관계없이 거부하는 회귀6건을 통과했습니다. 시스템 TCP/SSH/방화벽 설정은 바꾸지 않았습니다.

사용자가 준비 유지를 확인한 뒤 새 `/tmp/hearth-s4-11.uorim7l_`에서 두 JVM 재시험을514.678초에 마쳤습니다. 한 묶음의 시간/기동 한도는 늘리지 않았고 이전 DB/월드/실패 증거는 보존했습니다.

| 수정본 대상 | 시작 완료 | 최대 표본 RSS | 누적 CPU | 종료 소요 |
| --- | --- | --- | --- | --- |
| 첫 Paper |13.897초|1,073.49MiB|109.80초|0.621초|
| 두 번째 Paper |9.264초|962.65MiB|52.42초|0.550초|
| DB 보조 JVM |준비 표식 확인|216.09MiB|2.31초|0.108초|

최소 가용 RAM 약11.360GiB이며 RSS2.5GiB·가용6GiB·CPU2/nice+10·localhost·600초 한도를 지켰습니다. DB 보조 JVM은 PostgreSQL 전체 프로세스 메모리 측정이 아닙니다. TPS/MSPT·장기 부하/누수는 미측정입니다.

두 번째 JVM에서 기존 허용 목록을 따른 읽기 명령 `execute in ... run if block`은 문법 오류로 실행되지 않았습니다. 블록을 고치거나 성공으로 세지 않고 실제 플레이어 파괴의 이전 블록으로 보존을 검증했습니다. 허용 목록·기존 문서의 명령은 `execute in ... if block ... run say`로 정정했고 명령/좌표 회귀10건과 Python 구문 검사가 통과했습니다. 수정된 읽기 명령의 Paper 재실행은 하지 않았습니다. Java 소스·JAR·V001/V002는 변경하지 않아 이미 통과한 Gradle37개/자동 세션 시험을 중복 실행하지 않았습니다.

최종 문서 검사: 변경 Markdown 9개·상대 링크 220개, 표/제목/코드 블록·일본어/한자 혼입 오류 0건입니다. `git diff --check`, 원시 결과/로그 해시·기존 JAR/소스 불변·공개 요약 익명화 확인과 수동/자동 결과 출력 분기 2건도 통과했습니다. 비밀 검사는 기존 커밋/푸시 훅을 사용합니다.

### 남은 범위

B의 실제 플레이어 설치/파괴, 관리자 권한 해제 직후 재거부, P1~P5 전체의 재시작 후 반복은 남아 있어 S4-3은 **부분 검증**입니다. 다른 인증 사용자/동시 코옵, 실제 클라이언트의 DB 지연·실패/HOLD·준비 중 종료, 전41슬롯 메타데이터 전수 대조·커서/GUI/엔더 상자·전투, DB 프로세스 재시작·강제 종료·운영 배포도 미검증입니다. 이번 정상 재접속 성공을 실제 클라이언트 DB 경합/모든 행동 성공으로 확대하지 않습니다.

아래는 동일 방식으로 재시험할 때의 절차입니다. 현재 시험 서버/DB는 모두 종료돼 있습니다.

## 시작 전 사용자 준비

1. 정상 인증된 Minecraft Java Edition 26.2 클라이언트를 실행하고 **프로필 이름**을 확인합니다. 비밀번호·인증 토큰은 필요하지 않습니다.
2. 사용자 PC에서 이미 허용된 Rocky SSH 접속 경로로 아래 로컬 전달이 가능한지 확인합니다. `ROCKY_SSH_ALIAS`는 기존 SSH 별칭으로 바꿉니다. 원격 서버·SSH·방화벽·Cloudflare 설정은 변경하지 않습니다.

   ```sh
   ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:25586:127.0.0.1:25585 ROCKY_SSH_ALIAS
   ```

3. 클라이언트에 `127.0.0.1:25586`을 준비합니다. 이번 시험에서는 해당 전달 경로로 접속을 확인했습니다. 새 시험에서는 전달 연결이 계속 유효한지 다시 확인합니다. 사용자 준비를 확인하기 전에는 시험 서버를 켜지 않습니다. 미허용이면 운영 설정을 바꾸지 않고 미실행으로 남깁니다.

## 운영자 준비 및 시작

실제 프로필은 자동 시험의 의도적 SQL 실패·이동 실패·세대 무효화/HOLD 데이터와 **다른 새 경로·DB**를 사용합니다. 기존 자동 결과 경로를 수동 모드로 다시 열지 않습니다. 일반 서버의 기본 설정은 비활성입니다.

사용자가 준비된 뒤에만 아래 순서로 실행합니다. `PROFILE_NAME`은 실제 프로필 이름으로, `HEARTH_MANUAL_ROOT`는 새로 출력된 경로로 설정합니다. 초기 경로 생성과 의존성 준비는 서버를 기동하지 않습니다.

```sh
python3 - <<'PY'
import tempfile
from pathlib import Path
root = Path(tempfile.mkdtemp(prefix='hearth-s4-11.'))
(root / '.hearth-lifecycle-isolated').write_text('S4-11 isolated test only\n')
print(root)
PY
export HEARTH_MANUAL_ROOT=/tmp/hearth-s4-11.REPLACE_WITH_NEW_PATH
nice -n 10 env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-s4-1.RSHqyT/gradle-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --offline --console=plain stageLifecycleRuntime -PlifecycleWorkspace="$HEARTH_MANUAL_ROOT"
python3 scripts/verify-lifecycle.py --workspace "$HEARTH_MANUAL_ROOT" --java /home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/java --plugin build/libs/Hearth-0.1.0-SNAPSHOT.jar --artifact-source /tmp/hearth-s4-2.vnd2kki4 --accept-eula --sessions --manual-player --operator-ready --profile PROFILE_NAME
```

운영자 TTY·명시적 준비 플래그·프로필 이름이 없으면 도구는 시작 전에 거부합니다. 원본 캐시의 공식 해시·새 경로 소유권·RAM6GiB/디스크4GiB를 검사하고 localhost만 사용합니다. Xmx1536MiB/RSS2.5GiB 중단·CPU2/nice+10·작업550초/정리50초 한도를 유지합니다. 시간 한도를 연장하지 않으며 첫 수동 조작 약150초·두 번째 약120초를 목표로 준비합니다. 부족하면 미완료로 남깁니다.

첫 JVM은 기존 `prepare-player`로 A/B·WorldGuard 설정을 준비하고 같은 JVM에서 `session-prepare`를 실행합니다. 도구가 다음 콘솔 명령을 자동 전송합니다. **수동으로 중복 실행하지 않습니다.**

```text
whitelist add PROFILE_NAME
deop PROFILE_NAME
hearthverify session-fixture PROFILE_NAME
```

`HEARTH_SESSION_FIXTURE READY`와 수동 준비 안내가 나온 뒤에만 접속합니다. fixture는 기존 공유 진행 A에 개인 행·41슬롯(첫 슬롯 STONE32, 검·장화·방패 메타데이터 포함)을 명시적으로 준비합니다. 로그인 시 생성하지 않습니다. 수동 모드에서는 자동 시험 플레이어/실패 fixture를 만들지 않고 자동 시험 명령도 거부합니다.

## 첫 JVM — 보호·복원·빠른 재접속·IDLE

콘솔 명령은 아래 코드 블록에서 `PROFILE_NAME`만 치환합니다. 플레이어는 게임 명령 대신 이동·클릭만 수행합니다. 인벤토리 GUI 클릭/드래그·엔더 상자·컨테이너·커서·전투·임의 텔레포트는 이 격리 모드에서 제한됩니다. 이번 범위를 일반 게임 정책으로 확대하지 않습니다.

1. 접속 후 `격리 스토리 진행 복원 완료`를 기다립니다. 준비/이동 중에는 조작 불가, 이후 A의 시험 발판에 입장해야 합니다. 콘솔에서 상태와 일반 권한을 확인합니다.

   ```text
   hearthverify player-status PROFILE_NAME
   hearthverify session-status PROFILE_NAME
   ```

   기대: `op=false`, `bypass=false`, A의 실제 key/UUID, `state=ACTIVE playable=true slot0=32 revision=0 dirty=false`, DB `count=32 revision=0`. 스냅샷/매핑의 A UUID와 일치해야 합니다. 서버 로그의 `HEARTH_S4_3_ACTION` 및 `HEARTH_SESSION_` 기록과 실제 화면을 함께 보존합니다.

2. 콘솔에서 A 안의 시험 블록만 준비합니다. `(0,64,0)`의 식별용 GOLD_BLOCK과 B의 원본 블록은 건드리지 않습니다. x/z=1~15의 y=63 발판은 시험용이며 정식 로비/복귀 정책이 아닙니다.

   ```text
   execute in hearth_verify:restart_a run setblock 4 64 4 minecraft:stone
   execute in hearth_verify:restart_a run setblock 6 64 6 minecraft:stone
   execute in hearth_verify:restart_a run setblock 8 64 8 minecraft:stone
   ```

   | 사용자 행동 | 성공 판정 |
   | --- | --- |
   | A `(4,64,4)` 파괴 및 그 옆 설치 시도 | 보호 DENY, 블록·수량 불변 |
   | A `(6,64,6)` 맨손 파괴 | 허용, 블록 제거. 돌을 맨손으로 깨면 획득 아이템 없음 |
   | A `(7,64,6)`에 첫 슬롯 돌을 정확히 한 개 설치 | 건축 ALLOW, 첫 슬롯32→31 |
   | A `(8,64,8)` 파괴/설치 시도 | 중첩 priority20 DENY, 수량31 유지 |

   조건/좌표가 다른 행동은 별도로 기록합니다. 다른 아이템 사용·획득이 있으면 실제 수량을 기준으로 기대값을 명시하며 성공을 추정하지 않습니다. `player-status`의 bypass와 실제 취소 여부를 함께 봅니다. 엔진은 WorldGuard이며 Hearth가 보호를 재구현하지 않습니다.

3. `session-status`로 메모리31·dirty/revision을 확인합니다. 30초 전 DB 값32는 저장 완료 증거가 아닙니다. **종료 후 즉시 재접속**합니다. 이전 종료 저장 완료까지 새 복원이 대기하고, 이전 `HEARTH_SESSION_QUIT SAVED` 및 복원 후 메모리/DB31·revision 증가를 확인해야 합니다. `HOLD`이면 재시도/빈 데이터 생성으로 우회하지 않습니다.
4. 다시 종료하고 `QUIT SAVED`, A의 `IDLE → QUIESCING → SAVING → UNLOADING → OFFLINE`을 기다립니다. 수동 시험 IDLE은10초, 자동 시험은1초이며 운영 추천5분을 바꾸지 않습니다. `session-status`로 OFFLINE·DB31을 확인한 뒤 재접속해 같은 A UUID/key·보호 구역·인벤토리31을 확인합니다.
5. 관리자 bypass는 별도 비교합니다. 콘솔 `op PROFILE_NAME` → `player-status`로 bypass 확인 → 보호 블록 `(4,64,4)` 맨손 파괴가 허용되는지 확인 → `deop PROFILE_NAME` → 일반 권한으로 돌아왔는지 확인합니다. 관리자로 수행한 결과를 일반 플레이어 보호 성공으로 세지 않습니다.
6. 클라이언트를 종료하고 `QUIT SAVED`·A OFFLINE을 확인한 뒤 **운영자 TTY에 `stop`**을 입력합니다. 이는 즉시 강제 종료가 아니라 기존 세션/DB/월드 저장 장벽 완료 → Paper 정상 종료 → 두 번째 JVM 기동입니다. 접속자가 남으면 종료 장벽이 거부되므로 먼저 클라이언트를 종료합니다.

## 두 번째 JVM — 기존 데이터 복원

같은 DB/월드를 재사용하고 자동 실패 시험은 실행하지 않습니다. 도구는 `player-open`, `session-resume`, 아래 연결만 실행합니다. `session-fixture`를 다시 실행하지 않습니다.

```text
hearthverify session-link-existing PROFILE_NAME
```

`FIXTURE READY`·수동 준비 안내 후 접속합니다. `session-status`/`player-status`로 **신규 생성 없이** 같은 A UUID/key·보호 우선순위·인벤토리31/메타데이터·저장된 revision이 복원됐는지 확인합니다. 첫 JVM에서 합법적으로 바꾼 블록과 보호 정책을 다시 확인합니다. 이 경로는 Paper JVM 재시작이며 PostgreSQL 프로세스 재시작 시험은 아닙니다.

마지막으로 종료 저장·OFFLINE을 확인하고 TTY에 `stop`을 입력합니다. DB·모든 자식 프로세스·25585/임시 DB 포트 반환과 `HEARTH_LIFE_DISABLE_CLOSE PASS`를 확인합니다. 도구의 `MANUAL_CLOSED_UNVERIFIED`는 정리 결과이며 실제 행동 PASS가 아닙니다. `run-result.json`·두 콘솔 로그에 행동별 실제 결과/미실행을 추가 기록해야 합니다. B의 실제 플레이어 이동/보호, 모든 클라이언트 경합·슬롯 행동, TPS/MSPT·부하·강제 종료 복구는 이 절차로 자동 완료되지 않습니다.
