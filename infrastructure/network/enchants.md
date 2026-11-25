# Hearth 자체 인챈트 운영·설정 가이드

[원본 105종 대응표](../../docs/enchant-port-map.md)·[검증/한계](../../docs/enchant-system-verification.md)를 따른다. CrazyEnchantments 플러그인을 설치하지 않는다. 수치·책 획득처·가격은 운영 밸런스로 확정하지 않았다.

## 준비·정본

기존 survival economy RPG에 [설정 절](enchants.yml)을 병합한다. `survival-economy`, `housing`, `skills`/AuraSkills 2.4.0, `economy-rpg`가 필요하다. 기본 `hearth-enchants.enabled=false`이며 운영 서비스에 자동 적용하지 않는다. 30초 전투 이동 제한은 기존 combat-travel 설정을 사용한다.

최초 준비 때 `plugins/Hearth/enchants/`에 105종 개별 YAML과 `operations.yml`을 설치한다. 기존 파일을 덮어쓰지 않는다. 리소스의 JSON 형태도 유효한 YAML이며 일반 YAML로 편집할 수 있다. GUI와 명령은 **이 파일을 같은 정본으로 사용**한다. 기존 파일을 지워 기본값으로 되돌리는 방식으로 문제를 해결하지 않는다.

`operations.yml`의 최상위 `enabled`는 책 작업 허용, 각 APPLY/FUSE/EXTRACT/IDENTIFY의 `enabled`는 개별 작업 허용이다. 배포 예제의 성공 1/비용 0/개수 8은 개발용 시작값이며 운영 정책이 아니다. 최초 수치 설정 뒤 책 작업을 연다. 장비 효과의 활성은 정의의 `enabled`·월드/장비/슬롯·Hearth 정책이 결정하며 책 작업 중단과 별개다.

## 플레이·책 지급

- `/인챈트`: 적용·합성·회수·감정·본인 최근 결과 기록. GUI는 실제 물품을 보관하지 않고 원본 인벤토리 슬롯을 선택한다.
- 적용: 책 → 장비 → 선택 보호 주문서/증가 재료 → 성공/파괴율·비용·재료 확인 → 확정.
- 합성: 동일 ID·동일 레벨의 BOOK 두 개만 사용한다. 한 스택의 2개도 가능하다. 최대 레벨에서는 거부한다. 성공하면 다음 레벨 1개이며 실패 정책은 ALL/ONE/KEEP다.
- 회수: 장비 → 회수할 ID 직접 선택 → 확정. 성공은 같은 레벨 책 1개, 실패는 해당 ID만 소멸한다. 양쪽 모두 원본 장비·다른 특수/바닐라 인챈트·강화/PDC·외부 설명을 보존한다.
- 감정: 생성 때 정해 둔 책의 ID/레벨/확률을 공개한다. 감정으로 확률을 다시 굴리지 않는다.
- 거래: `/거래` 화면에서 가방 칸을 클릭하거나 `slot <가방칸 1~36> <수량>`을 입력해 실제 책/장비를 제안한다. 좌클릭+1·우클릭-1·Shift 좌클릭 전체다. 원본은 가방에 유지하고 양쪽에 복사본을 표시하며, 변경하면 양쪽 확인을 해제한다. 메타데이터·강화·책 확률을 보존하는 슬롯 교환을 사용한다. [구현/한계](../../docs/economy-followup-verification.md)를 따른다. 실제 동시 클릭/전투 거래 수용은 별도다.

관리 권한은 `hearth.enchants.admin`(기본 op)이다. 콘솔/관리자의 명령:

```text
/인챈트 admin
/인챈트 reload
/인챈트 create hearth:new_id hearth:ce_haste
/인챈트 clone hearth:new_id hearth:ce_haste
/인챈트 set hearth:new_id enabled true
/인챈트 set hearth:new_id max-level 5
/인챈트 set hearth:new_id slots [MAIN_HAND, OFF_HAND]
/인챈트 give <접속 이름> hearth:ce_haste 1 BOOK 1 0 0
/인챈트 give <접속 이름> hearth:ce_haste 1 UNIDENTIFIED .6 .2 0
/인챈트 give <접속 이름> hearth:ce_haste 1 PROTECTION 1 0 0
/인챈트 give <접속 이름> hearth:ce_haste 1 BOOST 1 0 .1
```

give의 마지막 세 수는 책 성공률/실패 시 파괴율/증가량(0~1)이다. 한 번에 한 개를 발급하고 GRANT도 동일 PG/native 거래로 기록한다. 실제 획득처를 자동 생성하거나 상점 가격을 확정하지 않는다. 지급 대상은 준비된 실제 접속 계정이어야 한다. `verify`는 소유 격리 표식·localhost·온라인 0명인 시험만 허용한다.

## 관리자 GUI

`/인챈트 admin`에서 페이지별 정의·신규 생성·복제·리로드·공통 비용/확률/개수를 관리한다. 정의의 이름/활성/최대 레벨/장비/슬롯/충돌/월드/작업 override, 규칙의 트리거/조건/확률/쿨다운/지속/효과 목록을 선택하고 채팅에 YAML/JSON 값 한 줄을 입력한다. `cancel`로 취소한다. 권한·접속 토큰·설정 세대를 재검사하며 일반 채팅으로 흘려보내지 않는다.

공통 설정 버튼은 `limits.HEAD 4`, `type-limits.sword 5`, `FUSE.success .7`, `FUSE.failure ONE`, `EXTRACT.cost '100*level'`, `enabled true` 같은 `경로 값`을 받는다. 개별 작업은 각 인챈트 YAML의 `operations`에 같은 필드를 override한다. 효과 목록 예:

```yaml
[{type: potion, target: SELF, potion: SPEED, amount: 'level-1', duration: '60'}, {type: potion, target: SELF, potion: HASTE, amount: 'level-1', duration: '60'}]
```

문법·ID·슬롯/장비·알 수 없는 효과/포션/소환·수식·세대를 검증한 뒤 원자적으로 파일을 공개한다. 단일 정의 편집은 다른 104개를 다시 읽지 않는다. 전체 reload는 새 세대를 완성한 뒤 교체하며 오류면 이전 런타임을 유지한다. 외부에서 같은 파일이 바뀌면 GUI 편집을 거부하므로 reload 후 다시 확인한다. 실패한/누락 파일을 기존 물품에 자동 재발급/삭제로 반영하지 않는다.

## 정의·제한·수식

[신규 템플릿](enchant-template.yml)을 새 ID 파일로 복제한다. ID는 `hearth:[a-z][a-z0-9_]{0,48}`이며 파일명은 콜론 뒤 ID와 같아야 한다. 표시 이름을 바꾸어도 ID/아이템 버전은 바꾸지 않는다. 신규 복제는 기본 비활성이다.

- `equipment`: armor/helmet/boots/sword/bow/axe/pickaxe/tool/hoe/damaged-items/any. `slots`: MAIN_HAND/OFF_HAND/HEAD/CHEST/LEGS/FEET. 두 조건을 모두 만족해야 한다.
- `limits`는 슬롯별 최대 특수 인챈트 수, `type-limits`는 장비 종류별 별도 상한이다. 겹치면 낮은 상한을 적용한다. 바닐라 인챈트는 세지 않는다. 장비를 다른 슬롯으로 옮겨 상한을 우회하지 않으며 기존 데이터가 새 제한을 넘으면 삭제하지 않고 효과를 막는다.
- `worlds`는 Minecraft world key 목록이다. 비어 있어도 Hearth의 입장·능력 제한을 우회하지 않는다. `conflicts`는 서로 충돌하는 특수 ID 목록이며 양방향으로 검사한다.
- trigger: EQUIP/TICK/ATTACK/DEFEND/SHOOT/ARROW_HIT/ARROW_IMPACT/BREAK/DROP/INTERACT/KILL/DEATH/ALLY_DEATH/BLOCK_XP/MOB_XP.
- target: SELF/OTHER/ENEMIES/ALLIES/BLOCK. 친구는 자신의 소환수, 같은 scoreboard team, 같은 허용 개인 거주의 구성원을 기준으로 한다. Factions 플러그인을 요구하지 않는다.
- 수식: `+ - * /`, 비교, `&& || !`, 괄호, min/max/clamp/abs/floor/ceil. 변수는 level/damage/health/max_health/target_health/sneaking/xp/distance/allies(4블록 내 친구)다. damage는 현재 최종 피해다. chance는 0~1, cooldown은 밀리초, duration은 tick이다. 코드/명령/스크립트 실행은 없다.

수식은 한 번 파싱하고 장착 ID만 조회한다. 105종 전체를 플레이 이벤트마다 읽지 않는다. PDC 문자열도 bounded cache를 사용한다. 0 나누기·알 수 없는 변수·비정상 수/과도한 복잡도는 거부한다. 룰 변경은 기존 cooldown을 지우지 않는다. 같은 플레이어/ID의 장비 복사·다른 트리거도 쿨다운을 공유하며 정상 재시작은 PG 묶음 저장/복원으로 유지한다.

## 전체 효과 모듈

| type | 주요 매개변수·행동 |
| --- | --- |
| potion | potion, amount(Bukkit amplifier), duration. 동일 포션은 합산하지 않고 강한 것을 존중한다 |
| heal | amount. 현재 최대 HP로 제한 |
| food / saturation | amount. 0~20 제한 |
| damage_multiplier / cancel_damage | 현재 실제 피해 이벤트 배율/취소 |
| damage / fire | amount. 적합한 현재 대상/PvP·WG·재귀 방어 |
| cleanse | 일반 부정 포션 제거 |
| knockback / pull | amount, vertical. 방향 계산·속도 상한 |
| lightning_visual | 번개 시각 효과 |
| explosion | amount(power), break_blocks. native explosion, WG/PvP·재귀 방어. 지형 파괴 기본 false |
| fall_guard | duration. 자신의 탈출/비행 해제 추락 보호 |
| durability | amount, random_armor. 음수는 수리, 다른 메타데이터 보존 |
| disarm / shuffle_hotbar | 대상의 슬롯 이동/핫바 순서 변경. 물품을 복제하지 않음 |
| xp_transfer | amount. 실제 존재하는 바닐라 XP까지만 이전, Aura XP와 분리 |
| rage | increment/maximum/expiry. 연속 공격 배율 |
| leadership | radius/amount. 실제 주변 친구 수 기반 추가 피해 |
| xp_add / xp_multiplier | 바닐라 채집/몬스터 XP. 최종 페널티 전 처리 |
| head_drop | 바닐라 지원 7종 머리·플레이어 프로필. 최종 보상 결정 준수 |
| multi_arrow | amount. 발사 당시 장비/월드, 피해·치명타·화염 보존. 추가 화살 회수 금지 |
| smelt | bonus. 원래 드랍 변환, 초과 보너스는 같은 행동의 RewardCoordinator로 별도 제출 |
| collect | 최종 허용된 실제 드랍만 인벤토리로 이동. 넘치는 원본은 월드에 유지 |
| area_break | mode=tree/vein/blast/harvest, amount. 정상 break·WG·입장/세션·loaded cell 검사 |
| farm | mode=grow/till/plant. WG/소유권, 씨앗 소비, 내구도 |
| temporary_block | material/radius/duration. 기존 블록 제외, 보호 검사, 복원·파밍 방지 |
| summon | entity/amount/lifetime. 원본 6종 몹, 소유자/적/수명/드랍 금지·로드 해제 관리 |
| flight | 허용 개인 거주의 건축 권한·전투/월드 정책과 자신의 flight lease |
| aura_stat | stat/amount. EQUIP 전용 공개 Aura nonpersistent modifier, 안정 이름·차이 갱신 |

안전 상한은 별도 운영 수용 전 제거하지 않는다. 정의 최대 512, 아이템 특수 ID 64, 각 레벨 1~1000, 수식 512자/128 node, proc cooldown cache 16384, 추적 화살 4096, 임시 블록 256, 소환수 128, 한 번의 광역 채굴 256 및 loaded cell만 처리한다. 원본 수치 전체의 장기 부하 수용을 뜻하지 않는다.

## Java 확장·Hearth 연동

완전히 새 효과는 `EnchantExtension` provider를 다른 Java 모듈의 onLoad에서 Bukkit ServicesManager에 등록하고 `install(EnchantEffects)`에서 `register(id, module)`한다. 설정을 읽기 전에 모듈이 등록되어야 한다. 모듈은 제공된 현재 context 및 `EnchantRuntime.allowed/damage/build`, `RpgRewards.deliver`를 사용한다. raw 명령 실행/임의 지급은 엔진이 대신 허용하지 않는다.

WorldGuard·개인 거주 ACL·월드 입장·PvP·전투 이동·사망/부적은 Hearth가 계속 소유한다. 자기폭발은 사망 드랍/부적 결과를 지우지 않는다. Wings는 허용 개인 거주/건축/전투 조건에서 자신의 lease만 관리하며 다른 Fly 플러그인의 권한을 압수하지 않는다. 임의 외부 Fly 시스템 전체를 통제하는 기능으로 해석하지 않는다.

강화·바닐라 인챈트를 다시 계산하지 않는다. CE PDC와 Aura 장비 bridge의 원본 키를 분리하고, aura_stat는 자신의 modifier만 안정 ID로 교체/제거한다. 포션 역시 타 소스와 무조건 중첩하거나 삭제하지 않도록 관리한다. 실제 복합 효과/외부 플러그인 조합은 실제 수용 때 확인한다.

보상 변환은 Hearth 최종 페널티 전에 처리한다. 추가 보너스는 같은 action/producer ID로 RewardCoordinator에 제출하며 이벤트 취소·페널티를 다시 검사한다. `penalty-worlds=novice_resource`로 초보 자원 월드에 고레벨 감소를 지정할 수 있다. 새 rpg-policy 예제는 이 범위이며, 키가 없는 기존 설정은 기존 전체 역할 정책을 유지한다.

`deliveryObserver`는 Telepathy가 **실제로 인벤토리에 넣은 양**과 action/producer를 main thread에서 알린다. [공통 플랫폼](platform.md)의 자동 판매는 이를 검사 예약으로 연결하고, 최신 native 물품을 기존 PG/native 거래로 소비하며 영수증 ID로 중복 지급을 막는다. 알림만으로 화폐를 지급하거나 callback 실패 후 무조건 재시도하지 않는다. Root RewardCoordinator의 API도 추가 생산자/판매 연동 경계로 유지한다.

## 기록·실패·복구

아이템에는 안정 ID/레벨/책 상태·확률/버전만 저장한다. 설명은 자신의 lore 구역만 갱신한다. E011은 요청·소유자·작업·확정 난수/결과·원본/결과 hash·비용/리비전과 proc cooldown을 저장한다. 기존 native shadow 한 개를 재사용하고 매 영수증에 전체 아이템 snapshot을 추가 저장하지 않는다.

책 작업은 원본 저장 → native pending/freeze → PG 비용/재료 shadow/결과 원자 확정 → 원본/세션 검사 → native 적용·저장 → 표식 정리다. replay는 자동 적용할 payload를 돌려주지 않는다. 불명 결과·로그아웃·CAS/세대 충돌을 임의 재지급/확률 재판정으로 해결하지 않는다.

`heartheconomy pending`과 `heartheconomy inspect <요청 UUID>`로 ENCHANT도 읽기 전용 진단한다. 기존 정상 drain/정지한 **Paper 전체 + PG 전체** 백업에 설정·PDC·표식·영수증을 함께 포함한다. E011 이전 JAR만 되돌려 새 DB와 섞지 않는다. 운영 배포/마이그레이션/복구 실행은 별도 승인 범위다.

임시 지형은 `.jsonl` 원본/복원 위치 기록을 남기고 loaded chunk 언로드/정상 종료에서 복원한다. unclean startup은 기존 native/RPG HOLD를 유지하며 표식을 지워 자동 재생성하지 않는다. 전원 차단 사이의 완전 원자 월드 복구나 무손실 자동 재지급을 보장하지 않는다. 기존 인벤토리 성능·수동 복구 원칙을 따른다.

## 출처·라이선스·재현

기준은 [CrazyEnchantments 커밋 6ecea9a](https://github.com/Crazy-Crew/CrazyEnchantments/tree/6ecea9a19affa811a8e23dd47fe647482507b82c)이다. MIT·Copyright (c) 2016-2023 CrazyCrew를 [번들 라이선스](../../src/main/resources/META-INF/licenses/CrazyEnchantments-MIT.txt)와 JAR에서 보존한다. 외부 플러그인/원본 바이너리는 배포하지 않는다.

`python3 scripts/import_crazy_enchantments.py --source <깨끗한 고정 source checkout> --output <새 후보 폴더>`는 동일 105종과 manifest를 재생성한다. 기존 출력/운영 설정 덮어쓰기를 거부한다. 후보를 diff로 검토해 반영하며 원본 업데이트를 자동 채택하지 않는다.

[공통 플랫폼](platform.md)의 자동 판매는 이 최종 수집 알림을 실제 인벤토리 검사 예약으로 연결한다. 장비/강화·인챈트의 스탯은 출처별 공통 합산을 사용하고, 자원 세대가 바뀌어도 원래 논리 world key 또는 `role:<role>` 제한을 유지한다.

## 기록·설정 안전 보완

[보완 검증](../../docs/platform-hardening-verification.md)에 따라 임시 블록 CREATE를 전용 worker에 저장한 뒤 세션/월드/권한/원본을 재확인해 설치한다. 기록은 `enchants-temporary-<실행 UUID>-<일련번호>.jsonl`로20,000개마다 회전하며 삭제하지 않는다. IO 실패/큐 거부는 새 임시 효과만 보류하고 원본 증거를 남긴다. 운영자는 디스크와 로그를 점검해야 하며 자동 과거 효과 재실행/무손실 복구는 제공하지 않는다.

설정 저장 전 효과별 재료·mode·불리언·알 수 없는 키와 지원 트리거를 검증한다. Java 확장은 `register(id, module, validator)`로 고유 인자 검증을 추가한다. 실제 효과의 개별 활성 설정과 책 작업 허용 설정의 구분은 유지한다.
