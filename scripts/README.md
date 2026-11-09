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

`--npc-topics --npc-pages`를 함께 지정하면 기존 질문·일지/DB 충돌 검사와 NPC 아이템창·책 수락/거절·대화 읽기 불변 검사를 한 JVM에서 묶는다. 저장 계약이 유지되는 UI 변경용이며 새 JVM 복원은 검사하지 않는다. [최신 범위·결과](../docs/npc-dialogue-book-verification.md). `--phone-dialog`를 함께 지정하면 같은 한 JVM에서 실제 발견 장소·일지/연대기·관련 NPC의 스마트폰 연결과 조회 불변을 추가 검사한다. [연결 결과](../docs/phone-reader-links-verification.md).

`--sessions --quests --story-batch --general-npcs`는 일반 NPC·개인 일지 대화 링크·현장 조사/발견과 IDLE·두 JVM 복원을 함께 검사한다. [범위·실패 이력·결과](../docs/general-npc-verification.md)를 따른다. 실제 사용자 조작은 수행하지 않는다.

`--sessions --quests --story-batch --quest-completion`은 명시적 규칙 게이트에서 실패/재시작·보상·장 스킵과 두 JVM 복원을 검사한다. [현재 판정·범위](../docs/quest-completion-verification.md)를 먼저 확인한다.

`--sessions --quests --story-batch --branch-progression`은 확정 분기 시작 권한·선택 경로 장 스킵·스마트폰 지급/장 화면·두 JVM 지급 기록 복원을 검사한다. 실제 사용자 조작은 하지 않으며 [범위와 결과](../docs/branch-progression-verification.md)를 따른다.

`--sessions --quests --story-batch --field-encounters`는 한 JVM에서 콘텐츠 필드 조우·실제 몹 속성/사망 이벤트·진행 격리·언로드 정리를 검사한다. 인원/이동은 내부 제어 입력이며 실제 플레이어 행동이 아니다. [제한·결과](../docs/field-encounters-verification.md)를 따른다.

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
