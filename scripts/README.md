# 스크립트

새 시험 기준 경로는 `/home/server/hearth-tmp`다. `python3 scripts/isolated_paths.py`로 소유권/0700·표식을 준비하고 `--prefix hearth-s4-11.`로 새 하위 경로를 만든다. 각 시험의 개별 표식과 기존 fresh/자원 한도는 유지한다. Gradle 캐시도 같은 기준 경로의 `gradle-home`을 사용한다. 기존 `/tmp` 증거는 읽기 전용 복사 원본으로 보존한다. [경로 이전 검증](../docs/test-workspace-migration-verification.md)을 따른다.

개발·검증과 Hearth 운영 보조 스크립트를 위한 공간입니다. 기존 S4-1/S4-2 도구와 별도로 S4-4 격리 DB 시험 도구를 제공합니다.

스크립트 추가 시 목적, 사전 조건, 변경 대상과 검증 방법을 문서화합니다. 비밀 정보를 포함하거나 출력하지 않고, 두 월드의 데이터 경계를 유지합니다. 운영 변경이나 파괴적 작업의 승인 요건은 `AGENTS.md`를 따릅니다.

## S4-4 PostgreSQL 시험

`verify-s4-4.py`는 준비된 JDK·Gradle·의존성 캐시를 받아 새 사용자 소유 `/home/server/hearth-tmp/hearth-s4-4.*` 경로에서만 실행합니다. 핵심 의존성 해시·자원 사전 검사 후 실제 PostgreSQL 통합 task를 실행하며 종료 시 자신의 시험 DB/포트를 확인하고 증거 데이터는 보존합니다. 운영 DB URL이나 계정 설정을 받지 않습니다. 사용 명령·라이선스/무결성 경계·시험 결과·미실행 범위는 [S4-4 기록](../docs/s4-4-verification.md)을 따릅니다.

## S4-5 Paper·PostgreSQL 통합 시험

`verify-s4-5.py`는 사전 빌드·stage된 새 사용자 소유 `/home/server/hearth-tmp/hearth-s4-5.*` 경로에서만 실행합니다. 기존 S4-2 자원/포트 검사·S4-4 해시/소유 DB 정리를 재사용하며 Paper 최대 2회 순차·PostgreSQL 1개·480초(작업 430초/정리 50초)·가용 RAM 6GiB·Paper 힙 1536MiB/RSS 2.5GiB 중단 한도를 적용합니다. 정확한 명령과 성공/미실행·소유권/자원/증거 경계는 [S4-5 기록](../docs/s4-5-verification.md)을 참고합니다. 기존 증거 경로에서 재실행하지 않습니다.

## S4-6~8 인벤토리 통합

`verify-inventory.py`는 S4-5 Monitor와 DB 보조 도구의 명시적 인벤토리 모드를 재사용합니다. 사용자 소유 새 /home/server/hearth-tmp/hearth-s4-6.*·600초·PG 1개/Paper 순차 최대 2회·기존 RAM/RSS/CPU/포트/EULA 제한을 적용하고 결과를 보존합니다. 사전 stage와 정확한 명령·실제/미실행 범위는 [S4-8 기록](../docs/s4-8-verification.md)을 참고합니다. 기존 증거 경로에 재실행하지 않습니다.


## S4-11 수명주기 통합 시험

`verify-lifecycle.py`는 기존 S4-5 감시·DB fixture·S4-2 최초 준비를 재사용하며 새 /home/server/hearth-tmp/hearth-s4-11.* 경로만 허용합니다. 시험 설정/JVM 게이트가 필요한 콘솔 어댑터이며 기본 플러그인 부팅은 비활성입니다. [S4-11 기록](../docs/s4-11-verification.md)의 첫 실행은 watchdog 실패로 중단됐습니다. 기존 실패 경로/증거를 보존하고 사용자 재개 후 새 격리 경로에서 수정본 두 JVM·대표 실패·정상 종료를 통과했습니다. 기존 실패 경로를 재실행하지 않으며 재개 시 새 격리 경로·원격/미커밋 변경·단계별 검사·자원 예산을 먼저 확인합니다. 실행은 운영 배포나 실제 플레이어 검증이 아닙니다.


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

`--npc-topics --npc-pages`를 함께 지정하면 기존 질문·일지/DB 충돌 검사와 NPC 아이템창·책 수락/거절·대화 읽기 불변 검사를 한 JVM에서 묶는다. 저장 계약이 유지되는 UI 변경용이며 새 JVM 복원은 검사하지 않는다. [최신 범위·결과](../docs/npc-dialogue-book-verification.md). `--phone-dialog`를 함께 지정하면 같은 한 JVM에서 실제 발견 장소·일지/연대기·관련 NPC의 스마트폰 연결과 조회 불변을 추가 검사한다. [연결 결과](../docs/phone-reader-links-verification.md).

`--sessions --quests --story-batch --general-npcs`는 일반 NPC·개인 일지 대화 링크·현장 조사/발견과 IDLE·두 JVM 복원을 함께 검사한다. [범위·실패 이력·결과](../docs/general-npc-verification.md)를 따른다. 실제 사용자 조작은 수행하지 않는다.

`--sessions --quests --story-batch --quest-completion`은 명시적 규칙 게이트에서 실패/재시작·보상·장 스킵과 두 JVM 복원을 검사한다. [현재 판정·범위](../docs/quest-completion-verification.md)를 먼저 확인한다.

`--sessions --quests --story-batch --branch-progression`은 확정 분기 시작 권한·선택 경로 장 스킵·스마트폰 지급/장 화면·두 JVM 지급 기록 복원을 검사한다. 실제 사용자 조작은 하지 않으며 [범위와 결과](../docs/branch-progression-verification.md)를 따른다.

`--sessions --quests --story-batch --field-encounters`는 한 JVM에서 콘텐츠 필드 조우·실제 몹 속성/사망 이벤트·진행 격리·언로드 정리를 검사한다. 정상 재로드의 완료 기록 보존·미완료 재소환 차단·저장 실패 보존/명시적 구간 리셋도 포함한다. 인원/이동은 내부 제어 입력이며 실제 플레이어 행동이 아니다. [최초 제한·결과](../docs/field-encounters-verification.md), [후속 보완](../docs/s4-offline-completion-verification.md)을 따른다.

`--sessions --quests --story-batch --combat-state`는 한 JVM에서 고정 참가 명단·일시정지/관전·조건부 부활 경합과 실제 몹 AI/사망 이벤트를 검사한다. 세션·카메라·회복 적용은 제어 포트이며 실제 Player 시험이 아니다. [범위·결과](../docs/combat-state-verification.md)를 따른다.

`--sessions --quests --story-batch --combat-players`는 실제 Player 적용기를 등록한 한 JVM/DB에서 기존 인벤토리 퇴장 저장·재접속과 전투 제어 포트 경합, [재도전 투표·기존 도전 종료](../docs/combat-retry-verification.md)와 [보스만 재도전 준비/교체](../docs/combat-restart-verification.md)를 검사한다. 실제 접속·카메라/체력/순간이동 성공은 검증하지 않는다. 수동 모드는 사용자 요청 뒤 기존 `--manual-player --operator-ready --profile` 경로를 쓰며 이 시험은 한 JVM으로 제한한다. [범위·명령](../docs/combat-player-verification.md)을 따른다.

`--sessions --quests --story-batch --gameplay-guide --phone-dialog`는 공동 조사 NPC/책/Dialog 표시 데이터와 기존 공유 단계·채팅 투표·저장 후 재입장을 한 JVM에서 검사한다. `--guide-safety-only`를 추가하면 정상 흐름을 반복하지 않고 실제 SQL 거부/HOLD와 commit 불명 응답·같은 요청 ID 확인 경로만 검사한다. 실제 클라이언트 조작은 실행하지 않는다. [결과](../docs/shared-progress-ui-verification.md).

준비된 수동 `--sessions --quests --manual-player`에 `--phone-dialog`를 조합할 수 있다. 프로필 상태는 10초마다 읽기 전용으로 조회한다. 선택 옵션 `--manual-finish-on-logout`은 시험자가 접속 후 종료하면 정리하므로 재접속을 계속 시험할 때는 사용하지 않는다. 기존 TTY·준비 확인·시간 한도는 유지한다. 수동 원시 결과는 실제 행동 자동 PASS가 아니다. [실제 관측 기록](../docs/quest-client-verification.md)을 참조한다.

`--sessions --player-interactions`는 보관 슬롯 커서 사본·메타데이터·퇴장 정리·DB 저장/복원과 SQL 쓰기 실패 보존도 검사한다. 인증 플레이어 이벤트 시험은 아니다. [최신 결과](../docs/inventory-cursor-verification.md).

`--sessions --quests --story-batch --combat-players`는 종료 복귀 제어·실제 보스 정리/다음 시작과 관리 구역 체크포인트 초기화·DB 실패 보존을 같은 한 JVM에서 검사한다. 인증 플레이어 복귀나 전체 길목 재도전 시험은 아니다. [최신 범위](../docs/combat-return-verification.md).

전투 Player 모드의 같은 한 JVM 검사는 선택 `path` 콘텐츠의 집결→길목 사망자 복귀→보스 명단/난이도 확정도 포함한다. 개발 시작 후 `/hearthcombat gather`를 사용하며 실제 클라이언트 실행은 별도 요청 전 보류한다. [집결 범위와 검사](../docs/combat-gathering-verification.md).

`--sessions --quests --story-batch --combat-players --combat-paths`는 별도 공개 합성 콘텐츠로 길목 실패 재도전·보스 투표 후 길목 복귀·고정 체크포인트 이동·구역 초기화·재집결을 한 JVM에서 검사한다. 전체 기존 전투 시나리오를 중복 실행하지 않는다. [범위·실패 이력](../docs/combat-path-retry-verification.md).

`--combat-paths`는 체크포인트 참가 범위·전원 회복·V008 저장을 포함한다. 두 번째 JVM에서는 전투 모듈을 비활성화하고 기존 활성화 기록/월드/인벤토리 복원만 검사한다. 전체 전투 재시작 복구와 구분한다. [기존 결과](../docs/checkpoint-recovery-verification.md), [복수 지점·지정 재시작 결과](../docs/multiple-checkpoints-verification.md).

`--sessions --quests --story-batch --combat-players --combat-departures`는 종료 중 오프라인 참가자의 저장·복귀, 도전별 복귀 기록 공존, 같은 JVM의 월드 언로드/재로드를 한 JVM에서 검사한다. 실제 인증 접속이나 프로세스 재시작 복원 시험은 아니다. [검증 범위](../docs/combat-offline-return-verification.md).

`--sessions --quests --story-batch --combat-players --boss-patterns`는 별도 공개 합성 콘텐츠에서 보스 패턴·일시정지/복귀 보호 타이머와 실제 엔티티 피해 이벤트를 한 JVM에서 검사한다. 별도 시험 몹을 쓰며 실제 인증 플레이어 피격·회피 시험은 아니다. [결과](../docs/boss-patterns-verification.md).

`--sessions --quests --story-batch --combat-players --boss-areas`는 방향 공격·고정/현재 좌표 기준·예고 후 위치/권한 변경을 한 JVM에서 검사한다. 실제 시험 몹의 이동/피해와 참가자별 표시 데이터를 검사하며 클라이언트 입자 렌더링 성공을 뜻하지 않는다. [결과](../docs/boss-areas-verification.md).

경제 전용 통합은 새 격리 환경의 `verify-lifecycle.py --sessions --economy` 단독 경로다. 기존 제한 worker·PG 1개·순차 두 Paper JVM·600초/CPU/RAM 감시를 재사용한다. 경제 fixture와 스토리 데이터를 분리하며 NPC 클릭/인증 플레이어 시험은 실행하지 않는다. 정확한 범위와 결과는 [경제 검증](../docs/economy-verification.md)을 따른다.

경제 상점/관리 변경만 검사할 때는 `--sessions --economy --economy-desk`를 사용한다. E001 기존 행을 새 격리 DB에 준비한 뒤 E002 보존·두 상품/기록·명시적 HOLD 정리·새 내부 세션 복원을 Paper 한 JVM에서 검사한다. 이전 90일/두 JVM 전체 검사는 반복하지 않는다. [범위·관리 명령](../docs/economy-desk-verification.md)을 따른다.

시장 정보 연결은 `--sessions --economy --economy-report` 단독 한 JVM 경로로 검사한다. 기존 두 JVM 전체 검사를 반복하지 않고 공개 범위·조회 불변·경제 Tick 동시 조회·실제 운송/사건·화면 객체·다음 틱 전환을 함께 확인한다. [범위와 결과](../docs/economy-report-verification.md)를 따른다.

복수 지역 상인 변경은 `--sessions --economy --economy-merchants` 단독 한 JVM 경로다. 두 NPC·지역별 화면/거래/제련·이전 NPC 선택 거부·월드 재로드 후 중복/원본 보존을 함께 검사한다. 기존 두 JVM/90일 전체 검사는 반복하지 않는다. [실제 범위](../docs/economy-merchants-verification.md)를 따른다.

수량/확인/결과 변경은 `--sessions --economy --economy-quote` 단독 한 JVM 경로로 검사한다. 임의 수량과 네이티브 화면 객체·조회 불변·거래 재검증/중복 거부·DB 롤백/HOLD 보존을 함께 확인한다. [실제 범위](../docs/economy-quote-verification.md)를 따른다.

최근 Tick 실적/제조 진단/사건 적용은 `--sessions --economy --economy-flows` 경로다. 한 묶음의 순차 두 JVM에서 E002→E003 보존·실제 흐름/공개 화면·저장 실패 롤백·진행 격리·월드 재로드와 새 JVM 복원을 확인한다. 과거 전체 플레이 시나리오는 반복하지 않는다. [범위·결과](../docs/economy-flow-verification.md)를 따른다.

상품 공급망/공개 플레이 안내는 `--sessions --economy --economy-supply` 단독 한 JVM 경로다. 콘텐츠/공개 필터·실제 시장/제조/운송 DTO·화면 객체·조회 불변·상인/진행 격리와 기존 최신 견적 거래 연결을 검사한다. 저장 규약을 변경하지 않아 두 JVM/90일 전체 검사는 반복하지 않는다. [범위·결과](../docs/economy-supply-verification.md)를 따른다.

거점 이동 경로는 `--sessions --quests --story-batch --fast-travel` 단독 한 JVM 모드다. 시설 선택·개인 방문 해금·공유 접근 제한·최신 인벤토리 저장·DB 오류/세대 차단·기존 월드 재입장을 같은 묶음으로 검사한다. 실제 Player 이동·클릭은 실행하지 않는다. [구현/한계](../docs/fast-travel-verification.md)를 따른다. 비용/운영 소요 시간 미정인 기술 시험이며 일반 서버에서는 비활성이다.

채집 인던 자동 통합은 `verify-lifecycle.py --sessions --quests --story-batch --gathering`으로 제한 구역·V009·저장/세대/실패·두 JVM 복원을 확인한다. 기존 준비/자원 감시를 재사용하며 [범위와 결과](../docs/gathering-dungeon-verification.md)를 따른다. 이동은 제어 포트이며 실제 채집/Player 이동 성공을 뜻하지 않는다.

복수 길목 통합은 `verify-lifecycle.py --sessions --quests --story-batch --combat-players --combat-route`로 실행한다. 한 JVM에서 같은 길목 도전의 두 구간·실제 체크포인트 DB·최종 보스/지정 구간 재시작·저장 실패 보존을 기존 도구로 검사한다. [검증 범위](../docs/combat-route-verification.md)를 따르며 실제 Player 이동/전투는 미실행이다.

공개 던전 의뢰 통합은 `verify-lifecycle.py --sessions --quests --story-batch --combat-players --dungeon-play`로 기존 한 JVM/PG 도구를 사용한다. 새 격리 경로에만 공개 의뢰를 합치고 진입 선별·길목/체크포인트·실제 관리 몹 사망·개인 목표/보고·재접속과 저장 실패 보존을 검사한다. [범위](../docs/dungeon-play-verification.md)를 따르며 NPC 클릭/Player 이동은 제어 포트와 구분한다.

작업대 제작 통합은 `verify-lifecycle.py --sessions --crafting` 단독 한 JVM 모드다. 새 경로에서 순정 레시피·잔여물·41슬롯 보존·실제 DB 저장/실패와 제어 세션 재연결을 검사한다. [검증 범위](../docs/crafting-verification.md)를 따르며 실제 Player 클릭이나 새 JVM 복원 시험은 포함하지 않는다.

일괄 제작·재료 제작법 연결은 같은 `--sessions --crafting` 및 별도 새 경로의 `--sessions --quests --story-batch --crafting-quests`에 검사를 추가했다. 두 모드는 [누적 배치 최종 통합](../docs/pending-batch-review.md)에서 같은 최종 Gradle JAR로 통과했다.

후속 [제작 준비 순서·공동 재료·조달 장소 안내](../docs/crafting-preparation-verification.md)는 위 제작 모드와 기존 `--play-preparation` 경로에 검사를 추가했다. 같은 최종 통합에서 신규 Paper/PG 검사까지 통과했다.

현장 작업대의 [내 제작 의뢰·공동 준비](../docs/crafting-orders-verification.md)도 아래 제작 의뢰 모드를 재사용한다. 현재 DB 목표→준비/메뉴 객체→제작→다음 목표/보고·재연결과 기존 CAS 실패를 같은 한 JVM에서 검사한다. 실제 Player 클릭은 실행하지 않는다.

탐험·제작 의뢰 통합은 `verify-lifecycle.py --sessions --quests --story-batch --crafting-quests` 단독 한 JVM 모드다. 공개 코스의 발견·제작 후 저장·목표 전이·NPC 보고/후속 해금·개인 격리·실제 DB 충돌·월드 재입장을 함께 검사한다. [검증과 미실행 범위](../docs/workshop-quest-verification.md)를 따르며 실제 클라이언트 시험은 실행하지 않는다.

채집 재료→제작 의뢰 통합은 `verify-lifecycle.py --sessions --quests --story-batch --gathering-crafting` 단독 한 JVM 모드다. 신규 채집 템플릿, 순정 드롭/실제 아이템·제어 인벤토리, 재료 표시·두 도구 제작·보고·초기화/월드 재입장을 함께 검사한다. [실행 범위와 실패 이력](../docs/material-supply-verification.md)을 따르며 실제 Player 채굴/줍기나 운영 배치를 검증하지 않는다.

공개 시설·재료·던전 준비 조회는 `verify-lifecycle.py --sessions --quests --story-batch --combat-players --dungeon-play --play-preparation` 단독 한 JVM 모드다. 기존 모듈을 함께 준비하되 새 안내의 정보 비노출·조회 불변·제어 세션 경계만 검사하며 채집/전투 전체를 반복하지 않는다. [검증과 미실행 범위](../docs/play-preparation-verification.md)를 따른다.

현장 채집 시설은 `verify-lifecycle.py --sessions --quests --story-batch --gathering-crafting --gathering-facilities` 한 JVM 모드다. 신규 격리 fixture의 버튼·구역·메뉴 객체와 저장 지연/중복/DB 충돌·월드 재입장을 함께 확인한다. [실행 범위와 실제 행동 미검증](../docs/gathering-facility-verification.md)을 구분하며 운영 월드나 기존 시험 자료를 덮어쓰지 않는다.

공개 코스 진행판은 기존 `--sessions --quests --story-batch --quest-navigation --phone-dialog`의 객체 검사와 `--play-preparation` 경로에 연결했다. 앞선 제작·준비 배치와 함께 검사할 때는 기존 준비 안내 경로를 재사용한다. [최종 통합](../docs/pending-batch-review.md)에서 준비 안내 경로로 신규 Paper 객체 검사를 함께 통과했다. 실제 클릭/왕복 성공과 구분한다.

진행판의 장소·일지·연대기·현재 공유 정보 연결은 위 진행판/준비 안내 경로의 `courseRecordScreens`에서 실제 책·Dialog 객체와 조회 보존을 확인하도록 추가했다. [최종 통합](../docs/pending-batch-review.md)에서 실제 DB 발견 기록의 읽기 경로와 객체 검사를 통과했다. [구현 범위](../docs/course-records-verification.md)와 실제 행동 미검증을 구분한다.

[누적 배치 최종 통합](../docs/pending-batch-review.md)에 Gradle 아티팩트·관련 단위와 세 Paper/DB 모드의 결과·원시 증거를 모았다. 기존 실행 도구의 권한·자원/시도 한도는 변경하지 않는다.

[채집·던전 개인 목표 안내](../docs/facility-objectives-verification.md)는 기존 `--sessions --quests --story-batch --gathering-crafting --gathering-facilities`와 별도 새 경로의 `--sessions --quests --story-batch --combat-players --dungeon-play`를 사용한다. 개인 DB 목표/공개 범위·책/시설 메뉴 객체·조회 불변과 기존 입퇴장/전투/실패 보존을 두 한 JVM 모드에서 묶어 확인하며 실제 클릭/왕복은 검증하지 않는다.

[실패·보상·결과·복귀 통합](../docs/quest-followup-batch-verification.md)의 `--sessions --quests --story-batch --quest-completion --phone-dialog --completion-navigation`은 기존 완료 콘텐츠·DB/자원 감시를 쓰는 단독 한 JVM 모드다. 재시작/보상 실제 저장·취소 조회 불변·공통 요청 중복/결과·DB 거부 보존과 Book/Dialog 객체를 검사한다. 다른 모드 혼합/필수 플래그 누락을 거부하며 기존 완료/분기 두 JVM 복원 모드를 대체하지 않는다. 같은 최종 JAR의 작업대 의뢰·준비 안내 회귀와 실제 클릭 미검증은 연결 기록을 따른다.

[공개 코스·현재 시설 동선](../docs/public-course-integration-verification.md)은 `verify-lifecycle.py --sessions --quests --story-batch --public-course` 단독 한 JVM 모드다. 조사·공유 준비→채집/두 도구 제작·보고→공유 투표→던전 승리·보고→개인 탐험/공유 정리를 같은 격리 환경에서 잇는다. 등록 콘텐츠와 목표별 준비 안내·Book/Dialog, 개인/진행 격리·DB 실패 보존·최종 월드 재연결 후 제작 도구/인벤토리 보존을 확인한다. 나머지 모드/수동 옵션은 섞지 않으며 기존 자원·시도 상한을 유지한다. 필드 조우의 언로드 후 임의 재소환 금지를 지키기 위해 월드 재연결은 던전 뒤에 수행한다. 실제 클라이언트 행동과 운영 활성화는 검증하지 않는다.

[인물 안내·NPC 공개 경계](../docs/npc-guidance-verification.md)도 기존 `--sessions --quests --story-batch --public-course`에서 함께 검사한다. 본인 시설 기록을 확인한 상태에서 공유 개방 전/후의 NPC 위치 권한·기록 버튼/스마트폰 연결·서버 엔티티 속성·Book/Dialog와 P2 개인 기록 격리/조회 무변경을 확인한 뒤 기존 채집/제작·투표·던전·최종 복원 흐름을 잇는다. 무인 엔티티 속성 검사 동안만 대상 청크에 티켓을 두고 반납하며 실제 Player 표시/클릭으로 판정하지 않는다.

[AI 공개 자료 선택·최신 조회](../docs/phone-grounding-verification.md)도 기존 `--sessions --quests --story-batch --public-course`에서 함께 검사한다. 퀘스트·지도·기록·공유 검색과 단일 자료/결과 없음의 Mock 응답 후 실제 DB 재확인, 실제 개인 목표 갱신 중 늦은 응답 거부·초안 보존, 미존재 DB 행 읽기 실패와 원본 기록/인벤토리 보존을 확인한다. 실제 AI 서버/모델을 실행하지 않으며 자료 포함/제외·전체 본문·복귀의 Dialog 객체 검사를 실제 사용자 조작으로 판정하지 않는다.

[이어 하기·재접속 안내](../docs/course-resume-verification.md)는 기존 `--sessions --quests --story-batch --public-course`와 별도 신규 경로의 `--sessions --quests --story-batch --quest-completion --phone-dialog --completion-navigation`을 같은 JAR로 순차 검사한다. 공개 코스는 첫 도구 제작 뒤 다른 구성원을 남겨 월드를 유지한 채 제어 세션을 재접속하고 목표/추적·전체 인벤토리/세대·새 접속 한 번/이전 표식 거부를 확인한다. 완료 경로는 실제 실패→재시작→PENDING 보상→지급 뒤 목록 제거/이전 선택 거부와 기존 CAS 실패 보존을 확인한다. `/hearthresume`은 플레이어용 읽기 안내이며 실제 클라이언트 시험은 계속 보류한다.

[개인 호위·코스 결과 통합](../docs/s4-gameplay-verification.md)은 새 격리 경로의 `--sessions --quests --story-batch --s4-gameplay`로 실행한다. 이 단독 묶음이 기존 공개 코스·개인 실패/보상 게이트와 기본 비활성 호위 콘텐츠를 함께 설정한다. 순정 Villager/Pathfinder 이동·사망, 개인/진행 격리·체크포인트/제어 재접속·실제 PG CAS 경합 HOLD·원본 보존·1회 보상·최종 결과/기록 객체를 검사한다. 기존 `--completion-navigation`은 별도 새 경로에서 같은 JAR로 순차 검사한다. 시간/CPU/RAM/포트/EULA 경계와 실제 클라이언트 보류는 유지한다.

[임시 제작·월드 보관함](../docs/inventory-workspaces-verification.md)은 새 격리 경로의 `--sessions --inventory-workspaces` 단독 모드다. 기존 `interaction-play`·SQL 실패 보존에 순정 제작 계산/반환·실제 보관 블록/WorldGuard·개인 dirty/CAS 경합을 함께 검사하고, 같은 PG/월드를 두 번째 Paper JVM으로 복원한다. 기존 `--sessions --player-interactions` 한 JVM 선택과 자원/시간/포트/시도 제한은 유지한다. 실제 인증 Player 이벤트는 생성하지 않는다.

[S4 개인 기능 통합](../docs/s4-features-verification.md)은 새 격리 경로에서 `--sessions --quests --story-batch --s4-completion` 단독 선택으로 실행한다. 기존 공개 코스·호위와 승인된 컷신·개인 엔더·스마트폰·전투 복귀 경계를 같은 PG/월드의 두 Paper JVM에서 검사한다. 시작·종료 최대 2회와 기존 CPU/RAM/시간/localhost/EULA 한도는 유지한다. 실제 Player·클라이언트 이벤트를 생성하지 않으며 운영 환경에는 적용하지 않는다. 리소스팩의 미니맵 Glyph JSON·PNG·참조는 `python3 scripts/build-phone-pack.py`로 검사하고 로컬 ZIP만 생성한다.

[HUD/선택형 BetterHud](../docs/hud-display-verification.md)는 각각 새 격리 경로의 `--sessions --quests --story-batch --hud-display` 또는 `--sessions --quests --story-batch --betterhud --betterhud-plugin /검증한/BetterHud.jar` 단독 모드로 한 JVM에서 검사한다. BetterHud 아티팩트는 [공식 고정값](../verification/betterhud-artifact.json)과 일치해야 하며 운영 설치는 하지 않는다. 생성 통합 팩은 `check-betterhud-pack.py`의 앵커/폰트·스마트폰 보존·팩 ID 검사로 확인한다. 실제 인증 클라이언트·팩 전송/호스팅은 보류한다.

관리자 복구 진단은 `verify-lifecycle.py --sessions --quests --story-batch --recovery-inspection --accept-eula` 단독 모드로 새 격리 DB/Paper 한 JVM에서 콘솔 등록·검증 연결 제한·단일 SELECT의 동시 41/27슬롯 저장 관측·실제 SQL 실패·CAS 원본 보존·읽기 한도를 검사합니다. 실행 경로/아티팩트/자원 한도는 기존과 같으며 [명령 및 검증 기록](../docs/recovery-inspection-verification.md)을 따릅니다. 진단 명령 자체는 조회만 수행하며 복구/재지급/초기화 명령을 추가하지 않습니다.

실제 S4 통합 수용은 `--s4-client --sessions --quests --story-batch --manual-player --operator-ready --profile PROFILE --betterhud-plugin PATH --accept-eula`로 준비합니다. 준비된 실제 인증 클라이언트가 필요하며 [실제 수용 절차/진행 기록](../docs/s4-client-acceptance.md)을 따릅니다. 사용자 명시 요청이 있는 이번 수동 시험에만 `--manual-no-time-limit`로 전체 경과 시간 제한을 해제합니다. 자동 모드의 600초 기본 한도, CPU/RAM·개별 대기·정리 한도는 유지합니다. `stop`은 다음 JVM으로, 연결 종료 후 `finish`는 전체 시험 정리로 이어집니다. `scripts/test_manual_s4.py`는 팩 제공 범위·경과 시간 예외와 자원 방어·수동 명령 제한을 검사하며 실제 플레이 PASS를 만들지 않습니다.

## S5 고정 로컬 AI 격리 시험

선택형 `ollama` 어댑터의 `hearthai prepare`는 서버 콘솔의 명시적 준비 요청이며 `hearthai status`는 계속 집계 조회만 한다. 준비는 같은 고정 모델/공개 노트의 초기 연산까지 최대 90초로 확인하고, 질문은 준비 성공 표식/적재 상태가 있어야 기존 6초 안에서 처리한다. 설치/운영 배포·자동 준비/keep-alive 연장이 아니다. [최종 구현과 첫 콜드 실패·재시험 경계](../docs/s5-ai-preparation-verification.md)를 따른다.

`s5_ai_trial.py`는 [ADR-0018](../docs/decisions/0018-local-ai-runtime-and-trial-budget.md)의 명시적 모델/예산 승인이 있는 새 사용자 소유 `/home/server/hearth-tmp/hearth-s5-trial.*`·전용 표식 경로만 사용한다. 실제 파일 해시·manifest 검증, libzstd 안전 해제, 독립 프로필/모델·루프백/클라우드 비활성·소유 프로세스 트리와 GPU/RAM/디스크/시간 감시를 수행한다. `--java`와 고정 JAR/fixture/Gson의 `--classpath`를 받는다. 일반 실행이 모델 설치/운영 변경 승인을 대신하지 않는다.

`--research`는 `--grounded`/`--focused`/`--knowledge`와 배타적인 고정 과학 노트 질의다. 같은 노트의 후속·다른 노트/일반 게임 상태 부재·이력 제외/캐시를 기존 시간·자원 한도 안에서 관측한다. 답변의 키워드 assertion 외에 원문을 검토하며, [고정 노트 범위 성공과 일반 과학 계산 보류](../docs/s5-science-reading-verification.md)를 구분한다. 런타임에 웹/원문을 가져오지 않고 앱과 같은 고정 노트를 사용한다.

`--calculation`은 다른 품질/준비 모드와 배타적이다. 직접 입력한 합성 계산 스냅샷과 고정 노트 2개만 보내 지렛대 100N→200N·에너지 500J·직렬 저항 5Ω, 같은 계산 후속/이력 제외·캐시, 원본 삭제 후 전송 거부를 기존 한도 안에서 관측한다. 원문 검토와 [첫 품질 실패·최초 시계 안의 1회 재확인](../docs/s5-calculation-help-verification.md)을 따른다. 전체 과학 정답률이나 실제 클라이언트 수용을 뜻하지 않는다.

`--preparation`도 위 품질 모드와 배타적이며 기존 사전 예열을 생략한다. 새 소유 런타임에서 준비 전 질문의 로딩 금지→명시적 준비→질문/캐시→시험 소유 모델 해제 후 진입을 개별 90초 probe 안에서 관측한다. 운영 서버/다른 모델에 해제 요청을 보내지 않는다. 실패한 시간/HTTP 시험을 새 경로/시계로 자동 재시도하는 예외는 추가하지 않았다.

`--continue-after-review`는 실패 원본을 보존하고 같은 최초 600초의 남은 시간만 사용하는 한 번의 관련 재확인이다. 새 시간 예산·모델/상한 확대가 아니며 원래 아티팩트 해시를 다시 확인하고 모델/프로필/로그/결과는 분리한다. 최초 표식의 경과 시간이 450초 이상이거나 재확인 결과가 이미 있으면 거부한다. 준비는 `OllamaTrialWarmup`, 실제 제한 흐름은 `OllamaTrialProbe`를 사용하며 Minecraft Player를 생성하지 않는다. [실제 결과·실패·최종 코드 미재실행 범위](../docs/s5-local-ai-verification.md)를 반드시 구분한다.

S5 후속 `--artifact-source`는 기존 `/tmp/hearth-s5-trial...` 또는 새 기준 경로에 보존된 사용자 소유 시험의 고정 해시 아카이브/blob만 새 경로로 검증 복사한다. 다운로드 대신 복사해도 최초 디스크 여유 16GiB와 새 경로/프로필·모든 기존 자원/시간 조건을 유지한다. `--grounded`는 자료 번호/접속 캐시·동일 본문의 다른 세션·stale/한도 흐름과 대표 자료 프롬프트 예열을 준비한다. 이전 로그/프로필/실행 중 서비스는 입력으로 사용하지 않는다. [실행·미실행 범위](../docs/s5-grounded-cache-verification.md)를 따른다.

디스크 전제가 부족하면 `s5_ai_trial.py`가 비활성 AI 시험의 동일 아카이브/blob/런타임 블록을 자동 공유하고 다시 16GiB를 확인한다. 독립 실행은 `python3 scripts/space_reclaim.py`다. 삭제 없이 원본 해시/경로/수정 시각을 유지하며, 지원 실패/공간 부족은 시작 전 차단한다. [자동 공간 확보 절차](../docs/DEVELOPMENT_WORKFLOW.md#시험-디스크-공간)와 [실제 자료/캐시 재시험](../docs/s5-grounded-live-verification.md)을 따른다. 재개는 기존 품질 거부 외에 명시적으로 완료된 준비 응답의 `INVALID_REFERENCE`/`OUTPUT_LIMIT` 품질 거부만 허용하며, 자원/정리/시간 실패를 허용하지 않고 최초 시간 예산·원본을 유지한다.

`--focused`는 긴 합성 공개 본문의 같은 자료로 다른 질문·방금 답변 참조·자료 4개/제외 후 번호 재지정·이력 전송 제외/캐시와 게임 상태 부재를 같은 승인 예산에서 검사한다. 실제 전송 미리보기/고정 자료 Plan을 사용하며 [이어 묻기 배치의 최신 관측](../docs/s5-grounded-followup-verification.md)을 따른다. `--grounded`와 상호 배타적이며 모델/자원·시간/정리 한도는 유지한다. [질문 관련 도움 검증](../docs/s5-focused-help-verification.md)을 따른다.

`--knowledge`는 일반 지식 목적의 준비/질의와 기본 과학 계산·후속 대화·게임 자료 분리를 같은 승인 한도에서 관측한다. 다른 품질 probe와 상호 배타적이다. [일반 지식 경계 검증](../docs/s5-knowledge-boundary-verification.md)의 실제 과학 수용 실패와 최종 코드의 원문 수식 모순 거부를 구분한다. `OllamaArithmeticReplay`는 기록 원문을 표시 검사에 재생할 뿐 새 모델 추론/정답 판정이 아니다.

`--concurrent`는 다른 AI 프로필과 배타적으로 준비 후 순차 기준·두 논리 요청의 겹침/자료 분리·취소 후 재요청·캐시 수용/검증 거부와 서버 수용 집계를 같은 고정 예산에서 검사한다. [최종 계측·실제 모델 관측](../docs/s5-ai-observation-verification.md)을 따른다. 논리 Chat 객체이며 실제 4명 접속이나 Paper/PG/AI 합동 부하가 아니다.

`offline_backup.py create/verify/restore`는 정상 종료한 Paper/전용 PG 전체의 해시 묶음과 새 경로 복원 도구다. [운영 전제·명령·수동 대응](../infrastructure/backup/README.md)을 먼저 따른다. `verify-lifecycle.py --backup-restore`는 기존 자동 시나리오 정상 종료 후 같은 시계에서 묶음/복원과 복사 PG의 원본 테이블 비교를 확인한다. 운영/기존 데이터 덮어쓰기나 실제 Paper 복원 행동 검사가 아니다.

`--sessions --economy --economy-runtime`은 첫 경제 fixture 준비 후 둘째 JVM에서 verification을 끈 경제 전용 정상 기동·계정/기존 저장 복원·drain을 검사한다. `--economy-runtime-recheck`는 실패 보완 때 새 최소 복원 입력만 만들고 이미 통과한 전체 거래 시나리오를 반복하지 않는다. [최초 실패와 최종 영향 검사](../docs/economy-runtime-verification.md)를 따른다. 실제 플레이어 로그인/상인 클릭은 판정하지 않는다.

`--sessions --quests --story-batch --joint-ai --ai-artifact-source <검증된_S5_원본>`은 Paper/PG와 고정 모델을 하나의 최초 시계·합산 RSS 6GiB 감시에서 실행한다. `joint_ai.py`는 기존 해시 검증 복사/해제·AI 정리를 재사용하고 AI 트리/전체 트리·PID 세대를 구분한다. [실제 합동 관측과 한계](../docs/s5-joint-observation-verification.md)를 따른다. 네 논리 세션이며 실제 네 플레이어 접속/용량 시험이 아니다.

### 정식 스토리 실행의 영향 검사

`verify-lifecycle.py --sessions --story-runtime`은 기존 CPU/RAM/시간 한도에서 불변 PG 연결·계정 경합/실패·두 JVM의 정상 스토리 초기화/복원과 저장 종료를 검사한다. 실제 Player/AI 추론은 생성하지 않는다. `--story-runtime-recheck`는 성공한 SQL 시나리오를 반복하지 않고 새 최소 복원 입력·수정된 정상 월드 준비/종료만 검사한다. 같은 workspace/이전 증거에 재실행하지 않는다. [범위·실패 원본과 제어기 로그 재검사](../docs/story-runtime-verification.md)를 따른다.

`--story-runtime --story-runtime-recheck --story-modules --sessions`는 같은 검토 콘텐츠로 제작/채집/호위/던전/전투·거점/준비 안내를 정상 모드에 구성하고, 채집 준비 단일 작업·실제 INSERT 실패/개인 원본 보존·미로드 다른 진행 거부와 저장 종료를 묶어 확인한다. 기존 계정 SQL 시나리오는 반복하지 않는다. [정식 모듈 통합 결과](../docs/story-modules-verification.md)를 따른다.

`verify-lifecycle.py --story-preparation --template-source <완료한 소유 S4-11 증거>`는 verification을 끈 준비/스토리 두 JVM에서 템플릿·WorldGuard 복제/새 UUID·등록 경합/실패 HOLD와 저장 복원을 검사한다. 템플릿 입력은 비활성 PASS 원본에서 새 경로로 복사하며 원본에 쓰지 않는다. `--story-preparation-recheck`는 통과한 SQL 경합/실패를 생략하고 변경된 준비/다음 JVM 경로만 확인한다. 실제 접속/AI 추론·운영 변경은 없다. [구현/검증 기록](../docs/story-preparation-verification.md)을 따른다.

`verify-lifecycle.py --sessions --story-runtime --story-runtime-recheck --story-operations`는 기존 등록 경합을 반복하지 않고 두 JVM의 정상 스토리 효과·저장/재로드·다른 진행/충돌 보존과 읽기 전용 PG/복구 콘솔/HOLD 초기화를 함께 검사한다. 실제 계정·클라이언트 동작이나 운영 장애 수용으로 해석하지 않는다. [검증 기록](../docs/story-operations-verification.md)을 따른다.

`verify-lifecycle.py --sessions --economy --economy-runtime --economy-runtime-recheck --peer-trades`는 기존 전체 NPC 거래 시나리오를 반복하지 않고 E003 보존 업그레이드·직접 거래 경합/실패/영수증과 다음 정상 경제 JVM의 저장 복원을 묶어 검사한다. 실제 Player는 만들지 않는다. [결과/미검증](../docs/economy-peer-trade-verification.md)을 따른다.

`verify-lifecycle.py --sessions --economy --economy-runtime --economy-runtime-recheck --economy-gathering`은 E004 보존 업그레이드와 경제/스토리 채집의 같은 UUID·ID 분리, 실제 제한 초기화/저장·정상 경제 타이머/IDLE 언로드와 원본 보존을 두 JVM에서 확인한다. 기존 엔진/직접 거래의 성공 행렬을 반복하지 않으며 실제 채굴/시설 클릭 검사가 아니다. [결과](../docs/economy-gathering-verification.md)를 따른다.

`verify-lifecycle.py --sessions --story-runtime --story-runtime-recheck --story-community`는 기존 등록 경합 행렬을 반복하지 않고 전체 저장 명단의 정상 기동 복원·다른 진행/미가입 제외·새 admission 확정/실패·읽기 실패와 원본 보존을 두 JVM에서 확인한다. 실제 접속/투표를 만들지 않는다. [결과](../docs/story-community-verification.md)를 따른다.

`runtime_health.py --log <고정 로그> --boot-file <현재 기동 nonce 파일> --mode story|economy --max-age <초>`는 읽기 전용 집계 관측이다. 현재 READY/비준비/관측 불가를 각각 종료 코드 0/1/2로 구분하며 모델/DB 호출을 하지 않는다. `--runtime-safety`는 `--sessions --story-runtime --story-runtime-recheck`의 새 두 JVM에서 소유 누락/원본/관측을, `--owned-economy-world`는 `--sessions --economy --economy-runtime --economy-runtime-recheck`에서 기존 소유 표식의 정상 복원을 확인한다. 실제 플레이·운영 수용을 대체하지 않는다. [검증 기록](../docs/runtime-operations-verification.md), [오프라인 감사/배포 후보 사용법](../infrastructure/deployment/README.md)을 따른다.

`network_config.py`는 [로비·경제 네트워크 설정](../infrastructure/network/README.md)을 새 private 폴더에 준비한다. 기존 데이터/서비스를 수정하지 않는다. `verify_network.py --workspace <새 hearth-network-run.*> --java <Java25> --plugin <최종 JAR> --velocity <고정 공식 JAR> --prepared-source <통과/비활성 hearth-s4-11.*> --accept-eula`는 새 복사 PG·프록시/두 Paper의 인증 구성·소스 저장/거절/실패/CAS·reload HOLD와 원본 보존을 한 시계에서 검사한다. 먼저 `stageNetworkRuntime -PnetworkWorkspace=<새 경로>`로 fixture를 준비한다. marker `Hearth network isolated v1\n`, 기존 CPU2/합산RSS6GiB·600/550초·최대Paper2/PG1을 요구하며 기존 경로 재사용/운영 연결을 하지 않는다. 실제 로그인/서버 이동은 합성하지 않는다. [검증 기록](../docs/network-verification.md)을 따른다.

## 일반 생존 3월드 설정·격리 복원

`survival_config.py --output NEW_FILE [--group-id UUID] [--allow-create]`는 새 0600 YAML만 만든다. 기본 생성 비활성·스토리/PG 경제 자동 변환 없음이며 [설정 안내](../infrastructure/network/survival.md)를 따른다.

`verify_survival.py --workspace FRESH_ROOT --java JAVA --plugin FROZEN_JAR --prepared-source PASSED_INACTIVE_S4_11 --accept-eula`는 소유 `hearth-survival-run.*`·정확한 `.hearth-survival-isolated` 표식(`Hearth survival isolated v1` + 개행)에서만 실행한다. 완료한 비활성 S4-11의 공식 Paper/WE/WG와 기본 차원을 읽기 전용 복사하고 기존 개인 파일/PG/Hearth 경제 매핑은 채택하지 않는다. 새 Paper 두 JVM에서 3월드/안전 입장·메뉴 ItemStack 객체·RPG listener 부재·재시작 동일 식별자/원본 보존을 함께 확인한다. 일반 설정은 NORMAL, 시험 새 야생만 표준 FLAT 생성기를 사용한다. CPU2/nice10·600초/작업550초·Paper heap1536MiB/RSS2560MiB·합산RSS6GiB·호스트 가용6GiB·최대 두 Paper/localhost를 유지한다. 정상 종료/소유 PID·포트 정리와 결과는 `survival-result.json`에 보존한다. 실제 Player·클릭·이동·인벤토리 동작은 합성하지 않으며 수용 성공을 주장하지 않는다.

## 기존 경제의 일반 생존 연결

`survival_config.py --with-economy`는 기존 새 파일 생성 규칙을 유지하면서 PG 연결/realm/content/env 이름을 명시한 YAML을 준비한다. `--database-profile infrastructure/deployment/hearth-postgres.json`은 [현재 전용 PG 정본](../infrastructure/deployment/project-postgres.md)의 연결값을 사용하며 직접 DB 옵션과 혼용하면 거부한다. 새 realm은 `--allow-new-realm`을 별도로 명시한다. [설정/예제/관리](../infrastructure/network/native-economy.md)를 따르며 운영에 적용하지 않는다.

`project_database.py --profile infrastructure/deployment/hearth-postgres.json [--output-env NEW_FILE]`은 비밀 없는 PG 프로필을 검사하고 선택적으로 새 0600 Compose 환경 파일을 준비한다. 비밀번호·운영 승인 표식을 읽거나 생성하지 않으며 서비스/DB를 실행하지 않는다. 관련 검사는 `test_project_database.py`, `test_survival_config.py`다.

`verify_native_economy.py --workspace FRESH_ROOT --java JAVA --plugin FROZEN_JAR --prepared-worlds PASSED_SURVIVAL_ROOT --prepared-database PASSED_S4_11_ROOT --accept-eula`는 새 소유 `hearth-native-economy-run.*`와 정확한 `.hearth-native-economy-isolated` 표식(`Hearth native economy isolated v1` + 개행)을 요구한다. 기존 승인된 비활성 시험 세계/PG만 복사하고 E006 소유권/경합/실패 보존·기존 행 불변과 실제 Paper 두 JVM의 상인/코덱·PG 거래/생산/영수증/HOLD 복원을 함께 검사한다. 실물 Player를 만들지 않으며 제어 Port의 native 저장 호출은 모형이다.

최종 순수 제어/GUI 보완 후에는 같은 소스의 두 JVM 검사를 반복하는 대신 `--restore-source PASSED_NATIVE_ECONOMY_ROOT`로 이미 통과한 비활성 경제 시험을 새 소유 경로에 복사해 기존 상태의 한 JVM 복원을 확인할 수 있다. 이 모드에서는 신규 realm 생성/DB 경합 시나리오를 반복하지 않는다. 두 모드 모두 localhost·CPU2/nice10·600초/작업550초·Paper heap1536MiB/RSS2560MiB·합산RSS6GiB·호스트 가용6GiB·최대 두 Paper/단일 PG와 소유 PID/포트 정리를 유지한다. 결과는 `native-economy-result.json`이며 실제 클라이언트·Player.saveData·아이템 이동·운영 수용은 별도다.

## 시장 자동 운영 검증

`verify_market.py --workspace FRESH_ROOT --java JAVA --plugin FROZEN_JAR --prepared-worlds PASSED_SURVIVAL_ROOT --prepared-database PASSED_S4_11_ROOT --accept-eula`는 위 native 소유 표식과 `.hearth-market-isolated` (`Hearth market isolated v1` + 개행)를 모두 요구한다. 먼저 `stageNativeEconomyRuntime -PnativeEconomyWorkspace=FRESH_ROOT`로 helper를 준비한다. E007의 실제 PG 잠금/CAS/롤백/commit 불명·기존 행 보존과 실제 Paper 예약 틱·규칙 모드 전환·다음 JVM의 정확한 컨트롤러/이력 복원을 묶어 검사한다. 합성 Player·실제 클릭/거래는 생성하지 않는다.

`--restore-source PASSED_MARKET_ROOT`는 정리 완료·algorithm PASS인 이전 비활성 시장 시험을 새 소유 경로로 복사해 변경된 최종 코드의 한 JVM 복원을 검사한다. 최초 전체 시험에서 선택형 모델만 실패한 PARTIAL 원본도 algorithm PASS/정리 완료를 확인하면 복사할 수 있다. 이는 모델 성공 판정에 해당하지 않는다. 이미 통과한 DB 신규 생성/경합은 반복하지 않는다.

기본은 LLM 없는 경제 에이전트 검사다. 별도 승인된 한 묶음에만 `--ai-source VERIFIED_INACTIVE_AI_ROOT`를 추가하면 기존 고정 모델을 읽기 전용 검증 복사하고 명시적 준비/실제 모드 후보 응답을 확인한다. CPU2/nice10·최초 600초/작업550초·준비90초/질의6초·Paper heap1536MiB/RSS2560MiB·AI RSS4GiB/합산RSS6GiB/GPU5GiB·호스트 가용6GiB·localhost·Paper 최대2/PG1을 유지한다. 기존 시간을 새 예산으로 재개하지 않으며 새 실제 AI 시험은 기존 시도 승인 경계를 따른다. `market-result.json`은 알고리즘/선택형 AI 결과를 구분하고 실패해도 원본·후속 독립 검사·소유 프로세스 정리를 보존한다. [검증 기록](../docs/market-automation-verification.md)을 따른다.

### 단일 스폰 상점

`verify_native_economy.py --spawn-market`는 새 단일 스폰 예제/realm을 사용하고, `.hearth-spawn-shops-isolated` (`Hearth spawn shops isolated v1` + 개행) 표식을 추가한다. 정상 모드와 같은 소유/localhost/자원 한도에서 새 복사본의 NPC 바닥만 준비한다. 기존 세계 원본/운영 지형을 바꾸지 않으며 실제 Player를 만들지 않는다. fixture 완료 표식은 다음 JVM에서 유지한다. 이미 검증된 E006 신규 연결 행렬은 반복하지 않고 helper의 restore 경로에 새 대상 realm ID를 먼저 기록하여 기존 행을 모두 비교한다. 실제 PG 거래·실패/HOLD·같은 스폰의 종류별 실제 NPC·다음 JVM 복원을 함께 검사한다. [단일 스폰 결과/실패 보완과 한계](../docs/spawn-shops-verification.md)를 따른다.

### 거주 시스템

`verify_native_economy.py --housing`는 새 native 소유 경로에 `.hearth-housing-isolated` (`Hearth housing isolated v1` + 개행)와 기존 스폰 시험 표식을 준비한다. 단일 스폰 예제와 거주 테스트용 32/64 크기·100/400 가격을 새 realm에만 사용한다. E008 실제 PG 경합/실패/원본 보존과 실제 Paper/WG의 개인 월드·차액 확장/블록 보존·역할/구획·두 JVM 복원을 묶어 검사한다. Player/클릭/인증을 합성하지 않으며 기존 localhost/자원/시간 한도를 유지한다.

이미 통과한 PG 행렬 뒤 Paper 보완만 확인할 때는 새 소유 경로에서 `--housing --housing-recheck`를 사용한다. 최종 순수 제어/보호 보완의 복원은 `--housing --restore-source PASSED_HOUSING_ROOT`로 한 JVM만 검사한다. 원본 결과의 유일한 실패가 종료 후 OWNED_PORT_REMAINS였을 때만, 소유 경로의 `--cleanup-followup FILE`로 원본 SHA-256/경로·기능/프로세스 정상 종료·독립된 포트/PID 확인 증거를 명시할 수 있다. 일반 실패를 PASS로 바꾸거나 원시 결과를 덮지 않는다. 포트 종료 확인은 bind 실패 시 ECONNREFUSED를 검사해 TIME_WAIT와 실제 listener를 구분하며 `test_native_ports.py`로 회귀한다. [실행 결과/제한](../docs/housing-system-verification.md)을 따른다.

## AuraSkills 기술 검증 전용

[기술 검증 정본](../docs/auraskills-technical-verification.md)을 따른다. `src/auraModel`은 순수 정책 proof, `src/auraProbe`는 별도 격리 콘솔 JAR이다. 정식 Hearth 기능/명령이나 운영 설치를 만들지 않는다. 모델/fixture의 오프라인 데이터는 Player 행동 시험을 대체하지 않는다.

```bash
# JAVA_HOME/GRADLE_USER_HOME/TMPDIR는 기존 /home/server/hearth-tmp 시험 설정을 사용한다.
# AURA_JAR는 정본의 공식 2.4.0 SHA-256과 일치하는 외부 다운로드 경로다.
gradle test --tests '*PolicyProofTest' auraProbeJar -PauraSkillsJar="$AURA_JAR"
python3 scripts/verify_auraskills.py --help
```

`verify_auraskills.py`는 새 사용자 소유 `hearth-native-economy-run.*` 경로와 `.hearth-native-economy-isolated` 표식(`Hearth native economy isolated v1` + 개행), 기존 EULA 승인 범위의 `--accept-eula`를 요구한다. 인자는 `--workspace`, `--source`, `--java`, `--plugin`, `--probe`, `--aura`, `--aura-config`다. source는 이전 native PASS·정리 완료·비활성 Paper/PG root이며 fixture-classes/resolved-jars를 포함한다. probe는 `build/libs/hearth-0.1.0-SNAPSHOT-aura-probe.jar`, aura-config는 고정 upstream `common/src/main/resources/config.yml`이다. 설정은 복사본에서만 수정한다.

기본은 기준선/Aura 두 Paper다. 이미 성공한 기준선 이후 영향 재검사는 `--aura-only`로 Aura만 실행한다. 새 JVM 복원은 이전 **Aura trial 전체 PASS** root를 source로 사용해 `--restore --betterhud FIXED_HUD_JAR`로 한 Paper만 실행한다. 복원은 같은 probe로 custom 등록을 다시 수행하고 저장된 level/XP/mana/modifier와 BetterHud enable을 확인한다. client·가짜 Player·운영 접속은 사용하지 않는다.

CPU 2개/nice 10, 기존 localhost/heap/RSS/600초·550초 작업·Paper 최대 2회 한도를 상속한다. 소유 PG 복사본의 기존 스토리/다른 경제 행 보존, 원본 파일 hash와 종료·포트 정리를 검사한다. 결과의 baseline phase PASS와 trial 전체 FAIL을 구분하고, 중간 실패 로그를 덮지 않는다. 실행 증거와 실측 한계는 정본에 한 번만 기록한다.

`verify_auraskills.py --admission`은 [정식 접근 통합](../docs/auraskills-admission-verification.md)의 별도 off/on 두 JVM 검사다. 새 native root에 `stageNativeEconomyRuntime -PnativeEconomyWorkspace=ROOT`로 **현재** 통합 fixture를 먼저 준비한 뒤 기존 `--source` native PASS 복사본·현재 `--plugin`과 기존 고정 Aura/probe를 사용한다. PG `skills` 모드는 거주 경합/실패/권한 철회와 정식 admission 정책을 연결해 검사한다. Player/행동 이벤트는 만들지 않고 실제 로그인/teleport 성공으로 기록하지 않는다. 기존 자원/종료/원본 보존 한도를 유지하며 `--restore`/`--aura-only`와 함께 쓰지 않는다.

`verify_auraskills.py --travel`은 [전투/TPA](../docs/combat-travel-verification.md)의 E009 실제 PG 경합/실패/원본 보존과 Paper prepare/resume 두 JVM 복원이다. native PASS source·새 소유 root·현재 `stageNativeEconomyRuntime` fixture를 사용한다. `--travel-final`은 PG 행렬 재실행 없이 기본 off/최종 on 두 JVM만 확인한다. 기존 제한을 상속하며 다른 trial 모드와 섞지 않는다. 실제 Player/피해/수락/텔레포트 이벤트를 만들지 않는다.

### 경제 RPG 일괄 통합 검증

`verify_auraskills.py --rpg`는 기존 native PASS의 비활성 cold 복사본에서 E010 PG 경합/실패·공용 5월드와 커스텀 skill/stat/gear/UI를 함께 준비하고 두 Paper JVM에서 복원한다. 현재 `stageNativeEconomyRuntime`으로 fixture를 먼저 준비한다. `--rpg-final`은 RPG PASS cold 복사본에서 최종 변경 코드/PG 행렬과 복원 한 JVM만 실행한다. `--rpg-events-final`은 마지막 방문자 PvP/provider·이벤트 연결만 한 JVM에서 확인한다. `--rpg-db-evidence <이전 root>`는 RpgStore/E010/DB fixture hash가 같고 PG 검사 13개·정상 종료가 확인될 때만 DB 검사를 재사용한다. 코드가 달라지면 재사용을 거부한다. fixture 정책/밸런스 값은 운영값이 아니다. 기존 CPU/RSS/600초/최대 두 JVM·localhost·소유 경계와 실제 Player 합성 금지를 유지한다. 상세 증거는 [일괄 통합 검증](../docs/economy-rpg-verification.md)을 따른다.

## 자체 인챈트 이식·검증

`import_crazy_enchantments.py --source CLEAN_PINNED_CHECKOUT --output NEW_CANDIDATE`는 고정 MIT 소스의 105개 정의/manifest/라이선스를 새 후보에 생성한다. dirty/wrong commit/기존 출력은 거부하며 운영 설정을 덮어쓰지 않는다. [대응표](../docs/enchant-port-map.md)와 diff를 검토한다.

`verify_auraskills.py --enchants`는 새 소유 native root에 현재 `stageNativeEconomyRuntime -PnativeEconomyWorkspace=ROOT` fixture를 준비한 뒤 기존 Aura PASS cold source/고정 JAR를 받아 E011 PG 13개와 준비/복원 두 Paper를 실행한다. `--enchants-effects-final --enchants-final-phase effects|arrows|hardening`는 같은 계약의 PASS source에서 변경된 임시 블록/화살 API만 한 JVM으로 재검사하며 DB 회귀를 반복하지 않는다. 기존 필수 인자·소유 표식/localhost·CPU/RSS/600초·최대 두 JVM/원본 보존을 유지한다. Player를 만들거나 실제 행동을 대체하지 않는다. 상세 실행 증거와 한계는 [검증 기록](../docs/enchant-system-verification.md), 설정/명령은 [운영 가이드](../infrastructure/network/enchants.md)를 따른다.

## 경제 공통 플랫폼

`verify_auraskills.py --platform`은 기존 Aura/native PASS cold source에서 E012 PG 행렬과 준비/복원 두 Paper를 실행한다. `--platform-db-evidence ROOT`는 PG15개/정상 종료와 관련 store/DDL/fixture 바이트가 같은 경우만 기존 DB 증거를 재사용하고 신규 정지 정책 CLI5개는 실행한다. `--platform-contexts`는 실제 전체 백업에서 복원한 cold source에서 활성+후속 pending 세대/논리 월드 제한을 두 JVM으로 확인한다. `--platform-final`은 이후 Player 접근 경계 등 좁은 변경의 복원 한 JVM이며 기존 DB/전체 행렬을 반복하지 않는다. 공통 필수 인자·소유/localhost/EULA·CPU/RSS/600초·최대 두 Paper와 실제 Player 합성 금지를 유지한다.

`verify_platform_backup.py --source PASSED_ROOT --workspace OWNED_PLATFORM_ROOT --restored-source FRESH_NATIVE_ROOT`는 기존 정지 전체 Paper/PG 백업 도구의 CPU2/90초/12GiB 한도에서 bundle/manifest 검증·복원본을 만든다. 서비스를 시작하지 않으며 복원본 JVM 수용은 위의 별도 context 시험으로 기록한다. [통합 증거](../docs/economy-platform-verification.md)와 [명령/설정·정지 정책 도구](../infrastructure/network/platform.md)를 따른다.

## 영속 예약 작업

`verify_auraskills.py --jobs`는 기존 플랫폼 PASS cold 복사본에서 E013 PG 행렬과 jobs-prepare/resume 두 Paper를 검사한다. `--jobs-db-evidence ROOT`는 독립적으로 통과/정상 종료한 PG16개와 JobStore/EconomyStore/JobPolicy·DDL·fixture 바이트가 동일할 때만 재사용한다. `--jobs-final`은 이후 순수 준비 경계 변경의 jobs-ready 한 JVM이며 기존 DB/전체 행렬을 반복하지 않는다. 기존 소유/localhost/EULA·CPU/RSS/600초·최대 두 Paper와 Player 합성 금지를 유지한다. [상세 증거](../docs/platform-jobs-verification.md)와 [설정/작업센터](../infrastructure/network/platform-jobs.md)를 따른다.

## 설정 작업대·중단 복구

`verify_auraskills.py --config-workbench`는 완료된 예약 작업 cold 복사본과 jobs-expected.properties를 사용해 설정 PG10개와 config-prepare/resume 두 Paper JVM을 검사한다. stageNativeEconomyRuntime으로 현재 fixture를 준비하고 다른 시험 모드 없이 실행한다. 기존 자원 교체/전체 RPG 행렬은 반복하지 않는다. 원본 보존·정품/Player 합성 금지·localhost·CPU/RSS/600초/두 Paper 한도는 기존과 같다. [검증 기록](../docs/platform-configuration-verification.md)을 따른다.

## 공통 플랫폼 운영 후속

`verify_auraskills.py --platform-operations`는 E014 PG12/정지 정책 CLI5·실제 retired 월드 보관/복원과 두 JVM 보류를 검사한다. 완료한 복사본의 `--platform-operations-final`은 변경된 준비/공통 진입/보관 조회를 한 JVM으로 확인하고 실제 format2 보고서를 약12초 관측한다. 기존 CPU/RSS/시간·정품/Player 합성 금지 경계를 유지한다.

`network_release.py plan|inspect`는 준비된 4서비스의 불변 파일/예산/백업·수용 상태를 묶고, `platform_soak.py`는 현재 실제 상태 보고만 bounded 관측한다. 서비스를 시작하거나 부하/Player를 합성하지 않는다. 사용법/미검증은 [운영 가이드](../infrastructure/network/platform-operations.md), 상세 증거는 [검증 기록](../docs/platform-operations-verification.md)을 따른다.

## 네트워크 전체 복구

`network_recovery.py create|verify|restore`는 기존 network_release 후보와 proxy/lobby/economy/전체 PG를 한 snapshot에 묶고 새 경로로만 복원한다. 기존 단일 Paper backup-v1도 유지한다. `verify_network_recovery.py`는 성공/정지한 소유 경제·로비 자료로 준비한 묶음을 복원해 실제 Velocity1/Paper2/PG1을 기존 CPU/RSS/600초·localhost/EULA/Player 합성 금지 한도에서 검사한다. 서비스를 자동 배포하거나 부하/Player를 합성하지 않는다. [사용법](../infrastructure/network/network-recovery.md)과 [증거](../docs/network-recovery-verification.md)를 따른다.

## 네트워크 수용 기록

`network_acceptance.py init|status|record|resume|rollback|seal`은 같은 검토 범위의 운영자 보고·증거 SHA·검증한 네트워크 롤백을 CAS 저널로 묶는다. 실제 시험/운영 승인을 대신하지 않으며 미실행/실패·pending·변경된 head는 신규 format3 완료 후보/기동을 차단한다. [절차](../infrastructure/network/network-acceptance.md)와 [검증 결과](../docs/network-acceptance-verification.md)를 따른다.

`--enchants-final-phase hardening`은 105종의 새 스키마 로드·잘못된 설정의 원본 보존·worker journal 회전/순서를 한 격리 Paper에서 검사한다. Player/행동을 합성하지 않는다. [보완 검증](../docs/platform-hardening-verification.md)을 따른다.

`verify_auraskills.py --followup`은 PASS cold source와 stageNativeEconomyRuntime fixture에서 메타데이터 peer PG8개·실제 ItemStack/개인 월드·사망 증거의 두 JVM을 검사한다. 다른 시험 프로필과 배타적이다. `--followup-db-evidence PASS_DB_PHASE_ROOT`는 fixture·모델/DB/DDL 바이트가 같고 해당8개/정상 정리가 확인된 DB 단계만 재사용한다. `--enchants-effects-final --enchants-final-phase followup-resume`은 통과한 후속 source의 원본/metadata 복원만 한 JVM으로 검사한다. 기존 CPU/RSS/600초·localhost·EULA·최대2 Paper/1 PG와 Player 합성 금지 경계를 유지한다. [검증](../docs/economy-followup-verification.md)을 따른다.
