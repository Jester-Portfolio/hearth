# 경제 RPG 시스템 설정·사용

[통합 검증과 한계](../../docs/economy-rpg-verification.md)를 기준으로 한다. 기존 단일 스폰·종류별 상점·시장 에이전트·기본/개인 거주를 유지하며 스토리 퀘스트나 스마트폰을 추가하지 않는다. 운영 환경에는 적용하지 않았다.

## 준비

기존 `survival` backend의 `survival-economy`·`housing`·`skills`를 준비하고 30초 전투 제한에는 `combat-travel`도 활성화한 뒤 [설정 절](economy-rpg.yml)을 병합한다. Hearth 데이터 폴더에 [정책](../../src/main/resources/rpg-policy.properties)·[콘텐츠](../../src/main/resources/rpg-content.properties) 파일을 복사한다. AuraSkills **2.4.0** 공개 API를 사용하며 SQL 저장 포크를 설치하지 않는다. 기본 배포의 `economy-rpg.enabled`는 false다.

정책 미정값은 운영값으로 확정하지 않았다. `drop-unit`, `xp-scope`, `xp-rounding`, 기본 거주의 사망 정책을 결정하기 전에는 해당 파괴적 처리/자원 입장이 준비 보류다. 기본/개인 거주 입장은 허용하되 사망 정책이 미정인 월드의 피해는 막는다. 호신부적은 `charm.scopes`에 명시한 범위만 보호하며 기본은 none이다. 고레벨 보상 감소는 요구 레벨 산정을 정한 뒤 활성화한다.

최초 추가 월드 준비에만 `allow-create-extras=true`를 사용한다. 초보 자원은 NORMAL, 자원 엔드는 THE_END의 NORMAL 생성기를 사용한다. 두 새 key/seed/terrain을 검토하고 준비 완료 뒤 false로 되돌린다. 기존 거주·야생·지옥 registry/그룹/realm/key를 바꾸지 않는다. 별도 `survival-extra-worlds.properties`·PG 바인딩·native UUID가 일치해야 재기동하며, 기존 월드 누락·준비 pending은 자동 재생성하지 않는다. 시험의 FLAT/seed 42는 콘텐츠 권장값이 아니다.

## 플레이

| 명령/메뉴 | 기능 |
| --- | --- |
| `/월드` | 기본 거주·초보 자원·일반 자원·자원 지옥·자원 엔드, 개인 거주 메뉴 |
| `/메뉴` | 숙련도·Home·개인 PvP·바라보는 오브젝트의 공통 편의 메뉴 |
| `/숙련도`, `/능력치` | 레벨/XP·최종 스탯·마나·현재 월드 정책, Hearth 커스텀 액티브 |
| `/hearthskill <id>` | 등록한 Hearth 커스텀 액티브. Aura 기본 액티브는 원래 입력 사용 |
| `/집설정`, `/집` | 안전한 현재 위치를 Home으로 저장/이동. 전투·최종 레벨/ACL 확인 |
| `/보호` | 바라보는 보관함/문/시설을 비공개·지정 사용자·공개로 전환하거나 해제 |
| `/보호 허용 <접속 사용자>`, `/보호 제외 <접속 사용자>` | 대상 계정의 상호작용 허용 목록 편집 |
| `/tpa`, `/tpaccept`, `/tpdeny`, `/tpcancel` | 기존 상대 수락형 이동·30초 공통 전투 제한 |

개인 PvP는 소유자만 `/메뉴`에서 변경하며 기본은 꺼짐이다. 허용한 방문자의 플레이어 간 전투는 건축 권한 없이도 가능하며, 건축물/엔티티 보호를 해제하지 않는다. 동거/방문 권한을 부여하거나 변경하지 않는다. 오브젝트 허용은 건축 멤버십을 주지 않는다. 보호된 블록은 소유자도 먼저 `/보호`에서 해제한 뒤 부순다. 큰 상자 양쪽과 문 위/아래를 묶으며 보호 상자에 다른 상자를 붙여 합치는 것을 막는다. PUBLIC은 호퍼 자동 이동도 허용한다. PRIVATE/LIST는 호퍼 반출입·피스톤·폭발·물 흐름·엔티티 변경으로 우회할 수 없도록 차단한다. 개인 거주의 오브젝트 공개는 그 월드 자체의 입장 권한까지 주지 않는다.

## 사망·경험치

- `drop-unit=item`: 스택의 개별 수량 판정. `stack`: 메타데이터를 가진 해당 스택 전체 판정. 슬롯별 원본을 유지하고 같은 재질의 서로 다른 아이템을 합쳐 판정하지 않는다.
- `xp-scope=vanilla_total|vanilla_progress|aura_progress|both_total|both_progress`: total은 바닐라 전체 레벨 포인트, progress는 현재 레벨 진행 포인트다. Aura는 모든 선택지에서 **현재 숙련도 레벨의 XP 진행량**을 대상으로 하며 스킬 레벨을 되돌리지 않는다. `both_total`은 바닐라 total + Aura progress다. 이 명칭을 Aura 누적 총 XP 감소로 해석하지 않는다.
- `xp-rounding=floor|ceil|nearest`: 손실량의 반올림. `lost-xp-orbs=false`는 차감분을 오브로 다시 생성하지 않는다. 자원 엔드의 바닐라 XP는 원래 사망/리스폰 흐름에 맡긴다.
- `charm.scopes=items,vanilla_xp,aura_xp`: 해당하는 잠재 페널티가 있는 사망에 한해 부적 한 개를 소비한다. 무손실 거주/초보 월드에서는 소비하지 않는다. 발급 경로/가격은 콘텐츠 단계에서 정하며 서버 내부 `RpgRuntime.charm()`으로 만든 표식을 사용한다.
- 아이템 예외는 `item-rate.minecraft\:diamond_sword=0.05`, `item-rate.pdc/hearth\:quest_item=0`처럼 Java properties의 **키 안 콜론을 이스케이프**한다. PDC가 재질보다 우선한다. 상충하는 여러 PDC 확률은 HOLD이며 순회 순서로 선택하지 않는다. 무손실 월드의 0%는 예외 설정으로 높이지 않는다.

커서·개인 제작/가공 입력도 한 번 분할한 뒤 남는 것을 소지품에 합친다. 제작 결과 슬롯과 월드 공용 컨테이너는 소유품으로 복사하지 않는다. 남는 개인 입력을 소지품에 안전하게 담을 수 없으면 사망을 취소하고 원본 스냅샷/HOLD로 보존한다. native 거래 중 사망도 원본을 보존하며 임의 재지급하지 않는다. 다른 플러그인이 사망 인벤토리를 동시에 소유하는 조합은 지원 확인이 필요하다.

## 커스텀·보상·HUD

`rpg-content.properties`의 빈 registry에 커스텀 skill/stat/gear profile을 등록한다. 커스텀 passive는 선언한 stat의 안정된 modifier, active는 HEAL/SPEED/HASTE/RESISTANCE 효과·마나·영속 PG 쿨다운을 사용한다. GUI 클릭과 명령 모두 같은 경로다. WorldGuard/월드/세션을 다시 확인하고 공개 ManaAbilityActivateEvent도 호출한다. native Aura 아이템 modifier에 같은 효과를 다시 등록하지 않는다.

강화 표식은 `hearth:upgrade` INTEGER, 커스텀 인챈트는 profile의 PDC INTEGER다. 지정 material·장비 슬롯에서만 산정하고 cap을 적용한다. 원본 아이템/바닐라 인챈트를 수정하지 않으며 장비 교체·월드 금지 시 Hearth modifier만 교체/제거한다. 강화/인챈트의 가격·획득/제작 콘텐츠는 이 bridge의 범위에 넣지 않는다.

고레벨 페널티는 `penalty.kind=specific|average|sum|all`, `penalty.skills`, `penalty.minimum`으로 결정한다. 조건 충족 시 최종 채취/사냥 아이템 50% 무드랍, 바닐라 XP 별도 50% 무드랍, 최종 Aura XP ×0.5다. Fortune/Looting 결과를 다시 계산하지 않는다. 기본/파생 XP 이벤트를 중복 구독하지 않는다. Aura 전체 벌목/광역 채굴이 보호·최종 drop 이벤트를 호출하도록 설정을 투영하며, 취소된 벌목 블록에 XP가 생기는 2.4.0 경로도 방어한다.

추가 커스텀 드랍·특수 인챈트 등 보상 생산자는 같은 행동 ID로 `RpgRewards.deliver(action, producer, finalItems, giveOrSell)`에 최종 결과를 제출한다. 같은 producer는 한 번만 지급/판매한다. 실패/불명 후 재지급하지 않는다. 정상 숙련도 획득은 `gainXp`를 거쳐 이벤트에 제출한다. 임의 플러그인의 raw XP·직접 지급/직접 판매까지 Bukkit listener 하나로 통제한다고 보장하지 않는다.

`hud-mode=actionbar|betterhud|off`. 기본은 추가 팩이 필요 없는 actionbar다. 선택 BetterHud는 고정 버전 2.1.0-SNAPSHOT-448·`merge-boss-bar: false`에서 공개 문자열 `[hearth_rpg]`를 제공한다. 운영자가 준비한 `hearth_rpg` HUD는 `default: false`로 둔다. 클라이언트의 팩 성공·실제 actor가 확인된 경우만 표시하고 세션/TTL을 확인한다. 정의/팩/호환 조건이 안 되면 actionbar로 돌아간다. 기존 HUD/팩 파일을 덮어쓰거나 자동 BetterHud reload를 하지 않는다. Aura 기본 actionbar/bossbar는 중복 방지를 위해 끈다.

[공통 플랫폼](platform.md)은 강화 실제 작업/정산·자동 판매·장비/패시브/인챈트 출처 합산·설정/정지 정책 이전·자원 세대를 이 기반에 추가한다. bridge와 실제 작업의 범위를 구분하고 같은 저장/보호 정본을 사용한다.

## 보존·백업

Aura의 `blocked_worlds`/`disabled_worlds`에는 동적 개인 거주를 포함한 정확한 월드 이름을 투영한다. Hearth가 추가한 이름만 ledger에 기록해 제거하며 외부 항목을 보존한다. 원본 `.hearth-before`·변경 pending을 남기고 native reload를 거친다. pending을 지워 기동 오류를 덮지 않는다.

E010은 추가 월드 UUID/seed·개인 PvP·Home·오브젝트 ACL/삭제 tombstone·액티브 쿨다운을 PG에 보존한다. 세대/CAS·발급 context·소유권을 확인한다. 사망은 `rpg-death-pending/<UUID>.pending`과 0600 `.before` 원본을 남기며, native 저장/확인 후 완료 표식을 정리한다. 중단 결과는 HOLD/수동 확인이며 고비용 무손실 자동 복구를 추가하지 않는다. 신규 사망 코드와 native world entity 저장 사이의 전원 차단까지 원자적이라고 보장하지 않는다.

백업은 기존 `scripts/offline_backup.py`의 **정상 drain/정지한 Paper 전체 루트 + PG 전체 루트**를 같은 manifest로 묶는 절차를 사용한다. Paper 안의 Aura YAML·Hearth metadata/표식·WorldGuard·native 월드/플레이어 데이터를 모두 포함한다. Aura 폴더/PG 몇 행만 다른 시점에서 따로 섞지 않는다. 도구는 실행 중 월드 락/PG PID, symlink·변경 중인 원본을 거부하고 checksum을 확인한다. 이 배치에서 운영 백업/배포/서비스 재시작을 실행한 것은 아니다.

## 사망 원본 저장·정리

사망 원본/표식 I/O는 worker, 인벤토리·Aura·native save·세계 드랍은 owner 스레드로 분리한다. 커서/작업대 입력까지 원본 native 슬롯에 담을 수 없으면 전체 입력을 worker에 보존하고 개인 HOLD·퇴장으로 전환한다. 사망 취소를 반복 가능한 무적으로 사용하게 하지 않는다. 결과 공개 전 원본과 결과 저장을 확인하며 그동안 이동/거래/인벤토리와 리스폰 admission을 열지 않는다. 정리 의도 `.done`이 남으면 효과 재실행 없이 정리만 재개한다. 독립 `.before`와 불명/충돌 결과는 개인 HOLD다. 콘솔 `/hearthsurvivaladmin death-status`는 보호/보류 UUID를 조회하며, 이를 원본 재지급 명령으로 사용하지 않는다. [보완 검증](../../docs/economy-followup-verification.md)을 따른다.
