# 스크립트

개발·검증과 Hearth 운영 보조 스크립트를 위한 공간입니다. 기존 S4-1/S4-2 도구와 별도로 S4-4 격리 DB 시험 도구를 제공합니다.

스크립트 추가 시 목적, 사전 조건, 변경 대상과 검증 방법을 문서화합니다. 비밀 정보를 포함하거나 출력하지 않고, 두 월드의 데이터 경계를 유지합니다. 운영 변경이나 파괴적 작업의 승인 요건은 `AGENTS.md`를 따릅니다.

## S4-4 PostgreSQL 시험

`verify-s4-4.py`는 준비된 JDK·Gradle·의존성 캐시를 받아 새 사용자 소유 `/tmp/hearth-s4-4.*` 경로에서만 실행합니다. 핵심 의존성 해시·자원 사전 검사 후 실제 PostgreSQL 통합 task를 실행하며 종료 시 자신의 시험 DB/포트를 확인하고 증거 데이터는 보존합니다. 운영 DB URL이나 계정 설정을 받지 않습니다. 사용 명령·라이선스/무결성 경계·시험 결과·미실행 범위는 [S4-4 기록](../docs/s4-4-verification.md)을 따릅니다.

## S4-5 Paper·PostgreSQL 통합 시험

`verify-s4-5.py`는 사전 빌드·stage된 새 사용자 소유 `/tmp/hearth-s4-5.*` 경로에서만 실행합니다. 기존 S4-2 자원/포트 검사·S4-4 해시/소유 DB 정리를 재사용하며 Paper 최대 2회 순차·PostgreSQL 1개·480초(작업 430초/정리 50초)·가용 RAM 6GiB·Paper 힙 1536MiB/RSS 2.5GiB 중단 한도를 적용합니다. 정확한 명령과 성공/미실행·소유권/자원/증거 경계는 [S4-5 기록](../docs/s4-5-verification.md)을 참고합니다. 기존 증거 경로에서 재실행하지 않습니다.

## S4-6~8 인벤토리 통합

`verify-inventory.py`는 S4-5 Monitor와 DB 보조 도구의 명시적 인벤토리 모드를 재사용합니다. 사용자 소유 새 /tmp/hearth-s4-6.*·600초·PG 1개/Paper 순차 최대 2회·기존 RAM/RSS/CPU/포트/EULA 제한을 적용하고 결과를 보존합니다. 사전 stage와 정확한 명령·실제/미실행 범위는 [S4-8 기록](../docs/s4-8-verification.md)을 참고합니다. 기존 증거 경로에 재실행하지 않습니다.


## S4-11 수명주기 통합 시험

`verify-lifecycle.py`는 기존 S4-5 감시·DB fixture·S4-2 최초 준비를 재사용하며 새 /tmp/hearth-s4-11.* 경로만 허용합니다. 시험 설정/JVM 게이트가 필요한 콘솔 어댑터이며 기본 플러그인 부팅은 비활성입니다. [S4-11 기록](../docs/s4-11-verification.md)의 첫 실행은 watchdog 실패로 중단됐습니다. 기존 실패 경로/증거를 보존하고 사용자 재개 후 새 격리 경로에서 수정본 두 JVM·대표 실패·정상 종료를 통과했습니다. 기존 실패 경로를 재실행하지 않으며 재개 시 새 격리 경로·원격/미커밋 변경·단계별 검사·자원 예산을 먼저 확인합니다. 실행은 운영 배포나 실제 플레이어 검증이 아닙니다.


최소 세션 자동 검증은 `verify-lifecycle.py --sessions`로 기존 감시/DB 도구를 재사용합니다. 최초 포함 최대3묶음의 기록과 개발 모드 게이트·사용자 접속 준비는 [세션 기록](../docs/player-session-verification.md)을 따릅니다. `verify-s4-2.py --manual-player`는 준비와 첫 수동 시험을 합쳐 총2 JVM/240초이며 실제 행동 판정은 자동으로 만들지 않습니다. 사용자의 클라이언트 준비 대기 중 서버를 먼저 기동하지 않습니다.

세션/DB를 동반하는 인증 플레이어 시험은 기존 `verify-lifecycle.py --sessions --manual-player --operator-ready --profile PROFILE_NAME`을 사용합니다. 새 경로/DB에서 자동 실패 fixture를 제외하고 fixture/link-existing·상태 조회를 연결합니다. 준비·전체 콘솔 명령과 판정은 [수동 절차](../docs/player-session-manual-test.md)를 따릅니다. 이 모드의 실제 인증 한 명·수동 두 JVM은 부분 검증했으며 `MANUAL_CLOSED_UNVERIFIED`를 실제 행동 PASS로 취급하지 않습니다. 기존 단독 S4-3의240초와 구분해 세션 시험은600초 한도이며 사용자 준비 후에만 기동합니다.

포트 반환 검사는 종료 연결의 TIME_WAIT와 실제 리스너를 구분하도록 SO_REUSEADDR bind를 사용합니다. 실제 리스너는 여전히 거부하며 소유 PID/그룹 종료 검사도 유지합니다. 수동 행동은 자동 판정하지 않으므로 `player_actions_verdict=NOT_AUTOMATICALLY_ASSESSED`와 관측별 판정을 구분합니다.

개인 방문 퀘스트 자동 검증은 `verify-lifecycle.py --sessions --quests`로 같은 격리/자원 감시·두 JVM/DB 도구를 사용합니다. 수동 시험은 기존 `--manual-player --operator-ready --profile PROFILE_NAME`을 추가하며 사용자가 준비되기 전 서버를 실행하지 않습니다. 실제 클릭과 내부 호출의 판정·명령·콘텐츠 위치는 [개인 퀘스트 기록](../docs/personal-quest-verification.md)을 따릅니다.

2026-10-27 `--sessions --quests`는 두 콘텐츠·두 퀘스트 저장 잠금/해제·다중 장벽/stale·새 JVM 복원을 같은 실행 도구로 검사합니다. 실제 클라이언트 시험은 사용자 별도 요청 전 보류하며 준비·접속·TTY를 요청하지 않습니다. 과거 수동 명령 예시는 현재 실행 요청이 아닙니다.
