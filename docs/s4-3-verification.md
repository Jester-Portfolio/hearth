# S4-3 실제 플레이어 건축 보호 — 준비 결과와 수동 시험 절차

- 확인일: **2026-10-26 (Asia/Seoul)**. 기준 `a4a32d53bc9fe4c448db4f4092f3e32689e8c613`. 시작 시 main·작업 트리/스테이징 깨끗함, fetch 후 origin/main과 일치.
- **준비 코드·빌드/정적 검사 및 S4-1/S4-2 자동 회귀는 통과했습니다. S4-3 수동 모드의 런타임, 인증된 플레이어 접속/설치/파괴/중첩/bypass/재시작 후 행동은 모두 미실행입니다. S4 전체는 진행 중입니다.**
- PATH에서 minecraft-launcher·prismlauncher·multimc를 찾지 못했고 DISPLAY/WAYLAND_DISPLAY도 없었습니다. 이 작업에서 사용할 인증된 클라이언트가 확보되지 않았다는 관측이며 호스트 전체의 설치 부재를 단정하지 않습니다. 계정·런처 인증 파일은 읽지 않았고 비밀번호/토큰을 요청하거나 저장하지 않았습니다.

## 재사용과 최소 확장

[S4-1](s4-1-verification.md)·[S4-2](s4-2-verification.md)의 같은 Paper 26.2-134·Java 25·WG 7.0.19·WE 7.4.4와 [ADR-0014](decisions/0014-worldguard-protection-engine.md)의 보호 엔진을 유지합니다. Multiverse-Core·별도 보호 엔진·PostgreSQL/NPC/퀘스트/AI/경제는 추가하지 않습니다.

| 파일 | 준비한 범위 | 실제 검증 경계 |
| --- | --- | --- |
| `RestartVerification` | 기존 resume 경로를 재사용하는 player-open. 매핑/템플릿·UUID 메타데이터/필수 파일/물리 UUID/key 검사 후 A/B 로드·기존 구역/우선순위 API 게이트 재사용 | 일반 S4-2는 공간 해시 엄격 검사 유지. 수동 재접속은 합법적 플레이 변경을 허용하도록 A/B 전체 공간 해시 불변 검사만 생략. 재복제/빈 월드 대체/구역 재생성 없음. 수동 경로 실행 미검증 |
| `HearthPlugin` | 기존 콘솔/격리 게이트 안에서 JVM `hearth.verification.players=true`인 경우만 player-open/player-status와 관측 활성화 | 일반 서버/기본 S4-2에서는 비활성. 실제 Bukkit 온라인 플레이어의 OP·WorldGuard SessionManager bypass를 읽음. 권한 판정 재구현 없음 |
| `PlayerActionObserver` | A/B의 온라인 플레이어 BlockPlace/BlockBreak를 MONITOR에서 기록, 2틱 뒤 최종 블록 관측 | 취소/허용/아이템/월드를 수정하거나 가짜 Player·이벤트를 만들지 않음. 로그는 관측이지 자동 PASS가 아님. 실제 클라이언트 이벤트 실행 미검증 |
| [기존 실행 도구](../scripts/verify-s4-2.py) | 선택 `--manual-player`: 기존 prepare→수동 JVM 전→정상 종료→같은 데이터의 수동 JVM 후. 자원 감시·격리 구성 재사용 | 운영자 TTY 필요, EULA·localhost·online-mode·whitelist 유지. TTY 존재는 인증 성공 증거가 아님. 수동 두 세션이 닫혀도 결과는 MANUAL_SESSIONS_CLOSED_UNVERIFIED |

콘솔은 whitelist/OP·deop/creative/시험 아이템/status/save/stop와 **A/B 안의 제한된 fill/setblock/tp/블록 확인**만 전달합니다. 편집/이동 좌표는 x/z -2..17·y 63..70, 블록 편집은 정수 좌표·STONE/AIR/COBBLESTONE만 허용합니다. 다른 월드·임의 execute 체인·과도한 범위 명령은 거부합니다. 수동 모드에서 부여한 OP는 정상 종료/시간 제한의 stop 요청 전에 deop 명령을 보냅니다. 강제 종료/예외 시 deop 반영을 보장하지 않으므로 해당 새 시험 경로를 운영 서버로 전용하지 않습니다.

## 실제 수행한 검사

| 구분 | 결과 |
| --- | --- |
| 빌드 | Temurin 25.0.5+8·Gradle 9.1.0, compileJava/jar/test/check exit 0. JUnit 파일 안전성 1개·실패/오류/skip 0 |
| Python/게이트 | 구문·도움말 통과. EULA 인자 누락 또는 수동 모드의 비TTY 호출은 exit 2, 서버 미생성. 허용/금지 콘솔·좌표 범위·소수 블록 좌표 검사 통과 |
| 기존 자동 회귀 | 새 `/tmp/hearth-s4-2.307e1geh`에서 기본 모드 실행. 기존 S4-1 smoke와 S4-2 두 JVM UUID/블록/구역/API/자연 피스톤 시험 통과. 수동 모드/JVM players flag는 사용하지 않음 |
| 자원·종료 | 회귀 합산 42.519초. prepare/resume 최대 RSS 1,225.05/956.30MiB·누적 CPU 52.18/35.31초, JVM exit 0/0. 종료 후 시험 포트 해제 확인. 플레이어 부하·TPS/MSPT·누수 미측정 |
| 증거 보존 | 기존 S4-1/S4-2 원본 로그/결과 해시 불변 확인. 새 [준비 증거 JSON](../verification/s4-3-preparation-result.json)에 JAR·회귀 로그 해시/결과와 클라이언트 미확보·미실행 기록 |

회귀 JAR SHA-256은 `24d9ade82f3b724df86bd8a95c122b08b18ece91de48effe450a6ba187e40c27`입니다. 배포 JAR은 기존 [공식 아티팩트 해시](../verification/s4-1-artifacts.json)와 다시 대조했습니다. Paper bootstrap의 전이 다운로드 전체를 별도 해시 고정했다고 주장하지 않습니다. 경고는 기존 비대화형 터미널/JOML Unsafe/고정 빌드 업데이트 안내/수동 저장 성능 경고이며 회귀에서 ERROR는 없었습니다.

## 수동 실행 절차 — 아직 실행하지 않음

### 1. 독립 인증 클라이언트와 새 시험 환경 준비

운영자는 별도 Minecraft Java Edition 26.2 클라이언트에서 정상 로그인합니다. Codex에 계정 비밀번호·토큰을 전달하지 않습니다. 새 작업 경로에 **확인된 JAR만** 복사하고 기존 시험 월드/서버 경로를 재사용하지 않습니다. `/tmp` 자료가 없거나 해시 검증에 실패하면 중단하고 공식 배포 확인부터 다시 수행합니다.

```sh
HEARTH_S43_WORKSPACE=$(mktemp -d /tmp/hearth-s4-2.XXXXXX)
cp /tmp/hearth-s4-2.307e1geh/paper-26.2-134.jar /tmp/hearth-s4-2.307e1geh/worldguard-bukkit-7.0.19.jar /tmp/hearth-s4-2.307e1geh/worldedit-bukkit-7.4.4.jar "$HEARTH_S43_WORKSPACE/"
python3 scripts/verify-s4-2.py --workspace "$HEARTH_S43_WORKSPACE" --java /home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/java --plugin build/libs/Hearth-0.1.0-SNAPSHOT.jar --accept-eula --manual-player
```

이 명령은 **운영자 터미널**에서 실행하는 준비 절차이며 이번 실행 기록이 아닙니다. 기존 prepare가 종료된 뒤 첫 수동 JVM이 자동으로 A/B를 열고 `HEARTH_S4_3_READY`를 출력합니다. 모든 명령은 READY 이후 **한 줄씩 결과를 확인하며** 입력합니다. 인증된 클라이언트는 같은 호스트의 `127.0.0.1:25585`로 접속합니다. 다른 PC에서는 이미 허용된 SSH 로컬 포워딩 등 기존 승인 경로만 사용할 수 있으며 이번에 접근 경로를 시험하지 않았습니다. 불허 시 SSH/방화벽/Cloudflare를 수정하거나 게임 포트를 공개하지 않습니다.

**전체 prepare+두 수동 JVM 합산 240초**·Xmx1536MiB·RSS 감시 2.5GiB·호스트 가용 6GiB·nice+10·CPU 힌트 2를 유지합니다. stop 유예 후 해당 자식만 종료합니다. 시작 전 클라이언트/관측표를 준비하고, 시간 내 끝내지 못한 항목은 미실행/미완료로 남깁니다. 예산을 늘리거나 서버를 상시 실행하지 않습니다. EOF/오류/시간 제한이면 실패/중단 기록을 보존하고 후속 JVM을 무리하게 진행하지 않습니다.

### 2. 접속·안전 발판·권한 준비

아래 `TestPlayer`를 실제 게임 프로필 이름으로 바꿉니다(비밀번호/토큰 아님). 운영자 콘솔에서:

```text
whitelist add TestPlayer
deop TestPlayer
```

인증된 클라이언트로 접속한 뒤 다음을 실행합니다. 신규 시험 월드는 void 기반이므로 **이동 전에** 발판을 준비합니다. 콘솔의 내부 블록 변경은 시험 장치 준비이며 플레이어 건축 허용의 증거가 아닙니다.

```text
execute in hearth_verify:restart_a run fill 0 63 0 15 63 15 minecraft:stone
execute in hearth_verify:restart_b run fill 0 63 0 15 63 15 minecraft:stone
gamemode creative TestPlayer
give TestPlayer minecraft:cobblestone 64
give TestPlayer minecraft:diamond_pickaxe 1
execute in hearth_verify:restart_a run tp TestPlayer 2.5 65 2.5
hearthverify player-status TestPlayer
```

일반 시험은 실제 world key와 **op=false bypass=false**를 확인한 뒤 실행합니다. creative는 추락 방지·빠른 시험 조작용이며 OP/bypass를 대신하지 않습니다. 상태가 예상과 다르면 시험하지 않고 원인을 확인합니다. 클라이언트에서 다른 관리 명령이나 다른 월드 이동은 하지 않습니다.

### 3. 건축 시험 행렬과 관측

각 시험 전에 파괴 대상 STONE과 설치 대상 AIR를 준비합니다. 아래는 A 보호 구역 예시입니다. 건축 허용은 x/z=6/6, 중첩은8/8, B는 A와 같은2/2를 사용합니다.

```text
execute in hearth_verify:restart_a run setblock 2 64 2 minecraft:stone
execute in hearth_verify:restart_a run setblock 3 64 2 minecraft:air
```

| ID | 실제 플레이어 위치·행동 | 일반 권한 기대 결과 | 이번 결과 |
| --- | --- | --- | --- |
| P1 | A (2,64,2) STONE 파괴, 인접 (3,64,2)에 COBBLESTONE 설치 | 파괴/설치 거부, STONE/AIR 유지 | 미실행 |
| P2 | A (6,64,6) 파괴, (7,64,6) 설치 | priority10 build 안에서 허용, AIR/COBBLESTONE으로 변경 | 미실행 |
| P3 | A (8,64,8) 파괴, (9,64,8) 설치 | priority20 deny 우선, STONE/AIR 유지 | 미실행 |
| P4 | B (2,64,2) 파괴, (3,64,2) 설치 | B allow에서 허용, A deny의 영향 없음 | 미실행 |
| P5 | 같은 A 보호/중첩에서 관리자 bypass로 행동 | status bypass=true 확인 후 허용, 해제 후 다시 거부 | 미실행 |
| P6 | 정상 저장/종료, 새 JVM에 접속해 P1~P5 반복 | 같은 UUID/key/구역/권한 규칙 유지 | 미실행 |

행동은 하나씩 수행하고 클라이언트 표시·WorldGuard 거부 안내, ACTION의 UUID/world/좌표/OP/bypass/cancelled, 같은 id의 2틱 뒤 STATE를 기록합니다. 거부된 설치 대상의 예시 확인 명령:

```text
execute in hearth_verify:restart_a run if block 3 64 2 minecraft:air run say A_PLACE_TARGET_STILL_AIR
```

메시지가 없어도 성공으로 추정하지 않습니다. WorldGuard가 PlayerInteract 단계에서 거부해 BlockPlace/Break가 발생하지 않을 수도 있으므로, 이벤트가 없다는 이유만으로 거부 성공을 판정하지 않습니다. 실제 시도 영상/표시·올바른 도달 거리·전후 블록을 대조하고 근거 부족은 미판정으로 남깁니다. 합성 이벤트/API 판정으로 P1~P6를 통과 처리하지 않습니다.

### 4. 관리자 bypass와 완전 재시작

새 시험 서버 콘솔에서만 임시로 `op TestPlayer`를 실행하고, 대상 A에서 `hearthverify player-status TestPlayer`의 **bypass=true**를 확인한 뒤 P5를 수행합니다. OP만으로 bypass를 추정하지 않고 WorldGuard의 [실제 SessionManager](https://github.com/EngineHub/WorldGuard/blob/7.0.19/worldguard-bukkit/src/main/java/com/sk89q/worldguard/bukkit/session/BukkitSessionManager.java)를 사용해 관측합니다. 이어 `deop TestPlayer`, status에서 OP/bypass 모두 false를 확인하고 다시 거부 동작을 시험합니다. 반영 대기/불일치는 보류합니다.

첫 세션에서 관측한 허용 변경과 구역을 기록하고 `save-all flush`, `stop`을 한 줄씩 실행합니다. WG 저장/정상 종료 로그·exit0·포트 해제 후 같은 데이터로 두 번째 JVM이 시작됩니다. whitelist는 보존되며 다시 인증 접속합니다. 기존 UUID/key·구역/flags와 실제 저장된 블록을 **시험 장치를 다시 설정하기 전에** 확인합니다. 이후 필요한 시험 대상만 초기화하고 P1~P5를 반복합니다. 구역/템플릿은 재생성하지 않습니다.

마지막에는 deop 확인·save-all flush·stop, 전체 JVM/25585 해제 확인을 수행하고 각 세션과 행동 로그/스크린샷/기대·실제 결과를 연결합니다. 로컬 결과 표에는 날짜·빌드/JAR·phase/PID·플레이어 UUID·좌표·권한·전후 블록·판정 근거를 연결합니다. 공개 Git 요약에는 시험자 별칭과 필요한 판정만 기록하며 실제 계정 이름/UUID/IP·채팅 원문·인증정보와 원본 플레이어 데이터를 복사하지 않습니다. 수동 runner의 종료 코드0이나 MANUAL_CLOSED는 정상 종료만 의미하며 보호 PASS가 아닙니다.

## 현재 판정

인증된 클라이언트가 확보되지 않아 준비까지만 수행했습니다. P1~P6·임시 OP 정리·이동/발판·관측 코드의 실제 클라이언트 실행·수동 runner의 TTY 동작은 **미실행**입니다. 기존 자동 회귀 성공과 혼동하지 않습니다. 게임 구현/DB/운영 환경을 확장하지 않았고 S4 전체는 진행 중입니다.

## 문서·보안 검사

2026-10-26: Markdown 38개·상대 링크 381개(앵커 2개 포함)·표 95개 검사 오류 0. 허용/금지 명령·좌표·EULA/TTY 거부, JAR/테스트 XML/회귀 로그 해시·이전 증거 보존·종료 PID를 확인했습니다. Python 구문·diff 검사를 통과했습니다. 보안 검사와 commit/push 결과는 Git Hook 및 작업 보고에서 확인합니다. 실제 클라이언트 행동을 실행했다고 기록하지 않습니다.
