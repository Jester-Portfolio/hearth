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

읽기 전용 목록은 `verify-lifecycle.py --sessions --quests --quest-list`로 같은 도구에서 목록 전용 fixture·상태/revision 불변·읽기 지연/stale·SQL 조회 오류·두 JVM 복원을 검사합니다. 기존 완료 흐름 전체를 반복하지 않으며 실제 클라이언트는 사용하지 않습니다.

상세 조회의 최소 자동 연동 검사는 `verify-lifecycle.py --sessions --quests --quest-info`를 사용합니다. 같은 도구에서 Paper 한 JVM만 실행해 상세/목록 클릭 Component·개인 격리·DB 불변·조회 오류/stale를 검사합니다. `--quest-list`와 동시에 사용하지 않으며 복원 경계가 그대로인 두 번째 JVM 전체 검사를 반복하지 않습니다. 실제 클라이언트는 사용하지 않습니다.

자동/보고 완료의 최소 검사는 `verify-lifecycle.py --sessions --quests --quest-auto`로 실행합니다. 기존 격리 도구에서 한 Paper JVM으로 목표 도달 제어 경계·revision·중복/SQL 실패·세션 교체·목록/상세를 확인합니다. `--quest-info`/`--quest-list`/수동 모드와 함께 사용하지 않으며 실제 플레이어 시험이 아닙니다.

한 NPC의 다중 퀘스트 검사는 `verify-lifecycle.py --sessions --quests --quest-npc`로 실행합니다. 단독 모드이며 한 JVM에서 두 선택지·동작별 nonce·개인 상태/DB 불변·지연 응답·자동/보고 완료·NPC 언로드/재로드를 확인합니다. 무접속 조회 동안에만 시험용 NPC 청크 티켓을 유지하고 해제합니다. 실제 클릭/채팅/거리 행동 시험은 아닙니다.

지역 자동 시작 검사는 `verify-lifecycle.py --sessions --quests --quest-start` 단독 모드를 사용합니다. 한 JVM에서 시작 구역·NPC 수락 차단·중복/SQL 실패·세션 교체·두 완료 방식 조합을 제어 경계로 확인하며 실제 이동 시험은 아닙니다. 기존 `--quest-list`/`--quest-info`는 새 경로에 과거 두 퀘스트 fixture를 준비하고, 세 퀘스트 목록/상세는 시작 검사의 마지막 단계에서 확인합니다.

개인 선행 조건 검사는 `verify-lifecycle.py --sessions --quests --quest-requires` 단독 모드를 사용합니다. 새 후속 콘텐츠를 포함한 한 JVM/DB에서 비동기 잠금 필터·NPC/지역 시작·개인 격리·조회 실패/HOLD·stale를 확인합니다. 이전 자동 모드는 새 경로에서 기존 고정 fixture를 유지하고 수동 모드는 새 콘텐츠 전체를 사용합니다. 실제 클라이언트 시험은 아닙니다.

통합 배치는 `verify-lifecycle.py --sessions --quests --quest-batch` 단독 모드입니다. 새 DB에 V003 fixture를 준비한 뒤 V004·두 JVM 단계/추적/알림/설정 복원·GUI/HUD 객체/표시 데이터·대표 실패를 검사합니다. 기존 고정 콘텐츠 모드와 혼합하지 않습니다. [실제 시험 범위](../docs/s4-quest-batch-verification.md)를 따르며 실제 클라이언트/GUI 클릭은 보류입니다.


탐험 통합은 `verify-lifecycle.py --sessions --quests --explore-batch` 단독 모드로 V004 fixture→V005·개인 발견/지도 DTO·개인/공유 일지·알림·설정·GUI 객체·두 JVM 복원·대표 실패를 검사합니다. 새 소유 경로와 기존 stageLifecycleRuntime/아티팩트/자원 한도를 재사용합니다. 실제 플레이어 접속·클릭을 자동 성공으로 판단하지 않으며 [탐험 배치 기록](../docs/s4-exploration-batch-verification.md)을 따릅니다.


공유 단계 통합은 `verify-lifecycle.py --sessions --quests --story-batch` 단독 모드입니다. 새 DB의 V005 fixture→V006·온라인 조건/채팅 투표 제어 경계·실제 CAS/receipt·DB 대기/실패/불명 응답·공유 탐험/지역 접근·두 JVM 복원을 검사합니다. 기존 stage/자원 감시·새 소유 경로만 재사용하며 다른 자동/수동 모드와 혼합하지 않습니다. 투표 만료는 제어 시계, 실제 채팅·이동/텔레포트는 미실행입니다. [공유 배치 기록](../docs/s4-shared-story-batch-verification.md)을 따릅니다.

## 공개 시험 코스 통합 모드

`verify-lifecycle.py`의 `--sessions --quests --story-batch --playthrough`는 새 격리 경로에서 기존 개인 퀘스트·공유 진행·탐험을 연결하고 순차 두 JVM에서 안내/코스를 복원한다. [범위·결과](../docs/public-playthrough-verification.md)를 따른다. 기존 자원 상한과 콘텐츠/월드 보존 정책을 유지한다. 실제 사용자 조작은 별도 요청 전 보류한다.

`--sessions --quests --story-batch --npc-topics`는 조건부 NPC 질문·중요 개인 일지·새 JVM 복원과 DB 충돌 보존을 한 묶음으로 검사한다. 실제 채팅/클릭은 실행하지 않는다. [범위·결과](../docs/npc-topics-verification.md)를 따른다.

`--sessions --quests --story-batch --general-npcs`는 일반 NPC·개인 일지 대화 링크·현장 조사/발견과 IDLE·두 JVM 복원을 함께 검사한다. [범위·실패 이력·결과](../docs/general-npc-verification.md)를 따른다. 실제 사용자 조작은 수행하지 않는다.

`--sessions --quests --story-batch --quest-completion`은 명시적 규칙 게이트에서 실패/재시작·보상·장 스킵과 두 JVM 복원을 검사한다. [현재 판정·범위](../docs/quest-completion-verification.md)를 먼저 확인한다.

`--sessions --quests --story-batch --branch-progression`은 확정 분기 시작 권한·선택 경로 장 스킵·스마트폰 지급/장 화면·두 JVM 지급 기록 복원을 검사한다. 실제 사용자 조작은 하지 않으며 [범위와 결과](../docs/branch-progression-verification.md)를 따른다.

`--sessions --quests --story-batch --field-encounters`는 한 JVM에서 콘텐츠 필드 조우·실제 몹 속성/사망 이벤트·진행 격리·언로드 정리를 검사한다. 인원/이동은 내부 제어 입력이며 실제 플레이어 행동이 아니다. [제한·결과](../docs/field-encounters-verification.md)를 따른다.

`--sessions --quests --story-batch --combat-state`는 한 JVM에서 고정 참가 명단·일시정지/관전·조건부 부활 경합과 실제 몹 AI/사망 이벤트를 검사한다. 세션·카메라·회복 적용은 제어 포트이며 실제 Player 시험이 아니다. [범위·결과](../docs/combat-state-verification.md)를 따른다.
