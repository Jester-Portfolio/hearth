# 경제 RPG 잔여 시스템 일괄 통합·검증

날짜: 2026-11-20. 사용자는 기존 AuraSkills 후속 작업을 나눠 중단하지 않고 한 배치로 개발하도록 요청했다. 기존 전투/TPA·입장 제어를 재사용해 추가 물리 월드, PvP/사망/부적, 개별 오브젝트 권한, 최종 보상, 장비/커스텀 능력과 편의 UI를 연결했다. 구체 콘텐츠·레벨/밸런싱과 실제 클라이언트 시험·운영 배포는 계속 보류한다. 이 문서는 이번 시스템 구현의 정본이며 [설정/명령 안내](../infrastructure/network/economy-rpg.md)와 [구조화 증거](../verification/economy-rpg-result.json)를 따른다.

## 1. 정상 사용 가능 여부

Paper 26.2 build 134·Java 25.0.5·공식 AuraSkills 2.4.0·WorldEdit/WorldGuard·선택 BetterHud 2.1.0-SNAPSHOT-448에서 Hearth 경제 RPG를 실제 enable했다. 공용 5월드와 기존 개인 거주 월드를 로드하고, 두 JVM 및 최종 코드의 추가 한 JVM에서 API·PG/YAML/월드 UUID 복원을 확인했다. 원본 플러그인 수정/인증 우회/가짜 Player는 사용하지 않았다.

이는 **서버 기동·데이터/공개 API·등록/정책 경계의 정상 사용 가능 증거**다. 실제 플레이어의 채취/죽음/액티브·TPA·화면/클릭까지 성공했다는 뜻은 아니다. 실제 시험은 사용자 요청으로 보류 중이다.

## 2. 요구별 구현

| 요구 | 최종 시스템 구현 | 이번 실행 증거·한계 |
| --- | --- | --- |
| 6종 월드 역할 | 기본 거주/개인 거주/초보·일반 자원/지옥/엔드. 공용 5월드 메뉴와 포탈·Home·공통 admission | 물리 추가 2월드 준비와 UUID/seed·입장점 새 JVM 복원. 실제 이동 미실행 |
| 월드 PvP·개인 소유자 설정 | public/novice 금지, 일반/지옥/엔드 허용, 개인은 소유자 PG CAS 토글·기본 off. native world와 WG 플래그 병행 | 실제 월드 역할/PG 복원·권한/CAS. 실제 피해·소유자 클릭 미실행 |
| 사망 확률·스택 | 0/5/30%와 item/stack 판정, 재질/PDC 예외, 슬롯/메타데이터별 수량 보존 | 순수 모델·native ItemStack roundtrip·개인 입력 포장/초과 HOLD. PlayerDeathEvent는 등록만 확인 |
| 부적·독립 XP | 부적 1개/선택 범위, 무손실 월드 미소비. 바닐라 전체/진행 포인트와 Aura 진행 XP 분리, 엔드는 바닐라 | 정책/곡선/수량 모델, API YAML 저장. 실제 부적 소비·XP 오브 미실행 |
| 복사/유실 방어 | 1회 분할→0600 원본 snapshot/pending→native 저장→드랍 공개, 다음 tick native 확인·저장 후 정리. 불명/거래 중 사망 HOLD | 실제 직렬화·새 journal 인스턴스 HOLD/정리. 전원 차단 무손실 보장은 하지 않음 |
| WG 구획·오브젝트 | 기존 WG 기본 건축 보호 + 개별 PRIVATE/LIST/PUBLIC. named interaction은 build 권한과 분리. 큰 상자/문, 호퍼·피스톤·폭발·흐름·엔티티 변경·상자 합치기 차단 | 실제 WG 영역 query에서 허용 사용자 chest ALLOW/build DENY, 외부 chest DENY. 실제 간접 블록 행동 미실행 |
| 레벨 입장·우회 방어 | 기존 특정/평균/합계/복수/커스텀 조건을 공통 admission에 재사용. Home/메뉴/포탈/최종 TP/리스폰/접속 안전 위치도 연결 | 기존 접근/전투 증거 유지, 이번 실제 End 역할·private ACL·Home PG 복원. 정품 접속·외부 플러그인 이동 미실행 |
| 고레벨 최종 보상 | Fortune/Looting 이후 아이템/바닐라 XP 각각 50% 무드랍, 최종 Aura XP ×0.5. Aura 추가 loot와 AoE·벌목 경로 연결 | 결정/중복 producer 방지 모델·실제 Aura 옵션 투영/재로드·리스너 등록. 채취/사냥 이벤트는 합성하지 않음 |
| 미래 드랍/자동 판매 | 같은 action/producer ID의 최종 아이템을 coordinator에 제출. raw 지급/XP는 협조 API를 거쳐야 함 | 10초 행동 수명 내 1회/만료/용량 경계 모델. 자동 판매 콘텐츠·임의 외부 플러그인 전체 제어를 구현했다고 주장하지 않음 |
| 공통 전투·TPA·Home | 기존 30초 갱신/PG/재접속 제한과 nonce 수락·최종 전투/레벨/ACL 검사. 새 Home도 공통 이동 경계 사용 | [공통 전투 검증](combat-travel-verification.md) 재사용, Home PG 경합/복원. 실제 전투/수락 미실행 |
| 커스텀 숙련도/스탯/능력 | 공개 registry, stat→trait, passive modifier, mana active 효과·PG 쿨다운·공개 활성 이벤트. 기본 Aura 능력은 기존 입력 유지 | 실제 custom skill/passive/active 메타데이터·level/XP YAML 새 JVM 복원, PG cooldown 동시 예약 1승자. 효과 발동 미실행 |
| 강화/커스텀 인챈트 | 장비 슬롯/material/PDC·계수·cap → 안정된 nonpersistent modifier. native 아이템 modifier와 별도 소유 | 실제 강화/PDC+Sharpness ItemStack에 값 계산·잘못된 슬롯 제외·원본 bytes 불변 |
| 월드 능력/XP 제한 | native Aura 정확한 world-name 투영 + Hearth XP/active/gear 제한. 동적 개인 월드도 포함 | 설정 파일·native reload·PG house world 실제 로드. 실제 기본 Aura passive/active 효과 제한은 클라이언트 후속 |
| UI/HUD | `/메뉴`·숙련도/스탯·액티브·Home·PvP·보호. actionbar/optional BetterHud cached placeholder·actor/session·TTL·팩 gate·fallback | 실제 API placeholder 등록. 메뉴 클릭·글꼴·팩/배치·표시는 미실행 |

일정 레벨·평균에 포함할 숙련도·기본 거주 사망·확률 단위·XP 대상/반올림·부적 범위는 설정으로 분리했다. 미정 정책은 무조건 0/바닐라로 선택하지 않고 준비 보류한다. 시험에서는 실행 가능성을 확인하기 위한 명시적 값만 사용했다.

## 3. Aura 기본 기능/API 활용

기본 레벨/XP·스탯/trait·기본 passive/mana ability와 YAML 저장은 Aura가 소유한다. 공개 registry/SkillsUser/StatModifier/XP·loot·mana 이벤트를 사용하며 reflection, 내부 DB provider 대체, 원본 JAR fork를 만들지 않았다. 강화 bridge는 아이템을 다시 쓰지 않고 Hearth modifier 이름만 교체한다.

[공식 API/소스의 고정 근거](auraskills-technical-verification.md)는 그대로 유지한다. 커스텀 mana 선언의 `mana_abilities`/`mana_ability` 키 차이는 별도 콘텐츠 `skills.yml`의 singular 키로 우회한다. native reload 명령은 SkillsLoadEvent를 다시 내지 않으므로 투영 직후 Hearth가 콘텐츠 유효성을 재검사한다.

## 4. Hearth 자체 구현

`survival/rpg`가 한 실행 수명주기에서 준비·보상·사망·ACL·장비·UI를 묶는다. E010/RpgStore는 발급 context·기존 native realm lock·소유권·원본 월드/앵커·revision CAS를 검증한다. PG 작업은 worker에서 수행하며 Bukkit 메인 스레드에서 DB future를 기다리지 않는다. 액티브는 PG 쿨다운 예약 후 최신 세션/마나/월드를 검사하고 효과를 적용한다. DB 불명 결과를 재시도하여 효과를 중복 발동하지 않는다.

기존 3월드 registry/PG fingerprint를 확장·재작성하지 않는다. 추가 두 월드는 별도 registry/PG immutable binding·pending·native metadata UUID로 관리한다. 신규 월드에는 기존과 같은 자원 입장점 WG 보호를 적용한다. 개인 월드는 기존 구매/공동 거주/언로드 수명주기를 사용한다.

## 5. 충돌·한계

- Aura 전체 벌목/광역 채굴의 기본 설정은 일부 drop/break 이벤트를 생략한다. 고정 2.4.0의 세 옵션을 true로 투영했다. 취소된 Treecapitator child break 이후에도 source XP를 호출하는 upstream 경로에는 같은 tick의 해당 플레이어 foraging XP 1회 차단을 둔다. 원본 소스가 바뀌는 버전은 그대로 허용하지 않는다.
- 파생 XP/drop 이벤트가 기본 HandlerList를 상속하므로 기본 이벤트를 한 번만 구독한다. Aura extra loot가 인벤토리로 직접 들어가는 경우에도 loot 이벤트에서 같은 행동 결정을 사용한다. 드랍 확률을 매 producer마다 다시 굴리지 않는다.
- 명시적 보상 계약은 메인 스레드의 신뢰하는 생산자 대상이다. 다른 플러그인의 무조건적인 MONITOR 변경, raw XP, 직접 인벤토리/월드 지급·직접 PG 판매까지 자동 수용하지 않는다. 새 생산자는 같은 coordinator와 기존 PG 거래 영수증을 통합해야 한다.
- 사망의 유일한 인벤토리 처리 소유자는 Hearth다. 개인 입력의 소지품 포장이 불가능하거나 다른 keep-items/거래 처리와 충돌하면 원본/HOLD를 보존한다. vanilla/WG/PG/native entity 저장을 하나의 전원 차단 원자 트랜잭션으로 만들지 않았다. 관리자 확인 없이 snapshot을 재지급하지 않는다.
- Aura YAML와 PG는 이중 정본이 아니다. 현재 XP는 Aura, 월드/소유권/전투/쿨다운은 PG다. 사망 중 양쪽 저장 결과가 불명확하면 수동 점검하며 고비용 자동 무손실 복구는 요구하지 않는다.
- BetterHud 기본 `merge-boss-bar: true`를 그대로 사용하면 전용 연동을 거부하고 actionbar로 돌아간다. 시험에서는 false로 지정했다. 기존 BetterHud 팩/정의를 자동 덮어쓰지 않는다. actual client 팩 gate/actor·표시 수용은 남아 있다.
- PvP 제한은 플레이어/소유된 projectile·TNT·tamed source와 WG/native 피해 규칙을 따른다. 소유자를 알 수 없는 환경 함정 전체를 플레이어 공격으로 추정하지 않는다.

## 6. 데이터베이스 권장 구성

**Aura YAML + 기존 PostgreSQL, 경제 skill writer 하나**를 유지한다. Aura가 공식 지원하지 않는 PG를 기존 DB라는 이유만으로 강제하지 않는다.

| 방식 | 성능/자원·안정성 | 구현·장기 유지보수 | 선정 |
| --- | --- | --- | --- |
| 공식 YAML | 추가 DB daemon 없음, 소규모 단일 writer에 적합. 파일 IO/저장 주기·정지 백업 관리 필요 | 기본 지원, 추가 패치 없음 | 현재 채택 |
| 공식 MySQL/MariaDB + 기존 PG | 사용자/동시 writer 증가 시 비교 가능. 추가 daemon·pool·백업/장애 관리 필요 | 공식 경로지만 두 DB 운영 부담 | 확장 실측 뒤 후보 |
| Aura PG fork | SQL/DDL/auto increment·upsert·타입/저장 계층 변환·회귀 필요 | 공개 storage provider SPI가 없고 upstream 업데이트/라이선스 유지 비용 높음 | 미채택 |
| Aura XP를 Hearth PG에 중복 저장 | 정본 경합·로드/저장/장애 시점 불일치 | 별도 복구/동기화 체계가 커짐 | 미채택 |

공식 지원·실제 저장 구조·라이선스 세부는 [기술 검증](auraskills-technical-verification.md)과 [ADR-0031](decisions/0031-auraskills-economy-integration.md)을 따른다. 이번 실행에서 MySQL/PG fork의 성능을 측정한 것은 아니다. 기동·복원에 필요한 Aura YAML/PG를 동시에 보존했으며 운영 인원 증가에 따른 성능은 아직 실측하지 않았다.

기존 cold backup 도구는 Paper 전체(Aura/Hearth/WG/native)·PG 전체를 동일 manifest로 묶는다. 정상 drain·정지·락/PID 부재·checksum 확인 후 같은 세트로 복원한다. 필요한 데이터가 이미 Paper 루트에 있으므로 별도 중복 백업 도구를 만들지 않았다.

## 7. 자동 검증·성능

- 관련 survival 회귀 배치와 순수 RPG 정책 9개와 방문자 PvP 권한 1개 통과. 정책 미정 차단·item/stack·부적 범위/무손실 미소비·XP 곡선/반올림·최종 producer 중복/용량·ACL/좌표를 확인했다. 마지막 좌표 경계 변경은 해당 검사만 다시 실행했다.
- 실제 PG 최종 13개 통과: E010 idempotent/checksum, extra immutable ID, 소유자/realm/issuer, PvP/Home/object CAS 경합, 원본 월드/앵커 고정, SQL 23514 롤백, tombstone, cooldown 1승자, native inventory payload·기존 realm/스토리 원본 보존.
- 최초 통합 두 JVM은 29/30개 확인 통과·76.222초. 소유권/확률 보완 코드는 해당 PG 행렬과 cold 복사본의 한 JVM 32개 확인을 묶어 36.074초에 통과했다. 이후 방문자 건축 제한이 소유자가 켠 PvP를 가로막는 경로를 보완하고, 해당 순수 권한 검사와 실제 Paper의 최종 provider/listener 연결 3개만 추가 확인했다. 기존 성공한 추가 월드 생성은 반복하지 않았다.
- 최종 idle 측정: 온라인 0명, 100 tick. MSPT p50 **1.654ms**, p95 **22.515ms**, tick 간격 p95 **50.117ms**, 표본 구간 메인 스레드 CPU **120.398ms**, heap **561.0MiB**. 기동/생성 뒤 짧은 표본이므로 p95에는 준비 영향이 포함될 수 있다.
- 최종 Paper 프로세스 최대 관측 RSS **1329.1MiB**, DB helper **238.1MiB**. 프로세스 전체 CPU 41.28초는 기동·복원·API 확인·표본을 포함한 32.406초 벽시계 구간이다. CPU 2개에서 구동했으며 이를 게임 1tick 비용으로 해석하지 않는다.
- 모든 소유 프로세스 정상 종료·PG PID/포트 정리·원본 hash/다른 realm 및 스토리 보존을 확인했다. CPU2·Paper heap1536MiB/RSS2560MiB·합산6144MiB·600/550초·Paper 최대2 JVM 경계를 확대하지 않았다.

두 초기 실패의 원본도 보존했다. 첫 실패는 콘텐츠 상대경로 정규화 오류였고 수정했다. 두 번째는 BetterHud 기본 병합 설정 충돌이었으며 fallback과 격리 설정으로 보완했다. DB 코드가 같던 기동 수정 때는 hash로 동일성을 확인해 이미 통과한 DB 검사를 재사용했고, 이후 object CAS의 월드/앵커 조건 변경 때만 영향받은 DB 행렬을 다시 실행했다.

이 결과는 **부하 수용 기준 합격이나 Aura만의 증가분 측정이 아니다**. 실제 4명·채취/사냥/AoE 동시 실행·개인 월드 반복 로드·장기 저장/부하와 운영 규모는 미검증이다.

## 8. 권장 통합 구조

```text
실제 플레이/명령/GUI
  → 기존 session + combat/travel + common admission
  → RpgRuntime (월드/PvP/사망/ACL/장비/보상/UI)
       → Aura 공개 registry/user/event → 공식 YAML
       → RpgStore → 기존 PG native realm/CAS
       → WG 기본 구획 + object interaction flags
       → native inventory/world save + pending/HOLD
       → actionbar / optional BetterHud cached presenter
```

native drop/XP 이벤트와 협조하는 미래 producer는 하나의 최종 보상 결정을 공유한다. 보호 엔진·native 아이템·Aura 레벨을 Hearth가 다시 구현하거나 이중 정본으로 저장하지 않는다. 기존 생산/시장 자동 에이전트와 상점은 독립 모듈로 유지한다.

## 9. 후속 순서

이번에 요청된 시스템 연결은 한 배치로 구현했다. 이후 순서는 미정 정책/콘텐츠 수치 선택 → 별도 요청 시 실제 정품 클라이언트의 수집/사망/부적/액티브·UI/TPA/간접 보호를 한 흐름으로 수용 → 목표 동접/장기 부하 → 일관 백업/격리 복구·운영 예산과 배포 수용이다. 자동화 시험 시나리오만 반복해서 다음 기능 작업으로 삼지 않는다. 자원 월드 정기 초기화의 주기/세대 교체는 이번 AuraSkills 요청과 별도 설계 범위다.

## 10. 추가 결정

기본 거주 XP/아이템 손실, 1/3의 XP 종류/진행 범위/반올림, item/stack, 부적 보호 범위/발급·가격, 고레벨 기준/대상 숙련도 집합, 입장 요구 레벨, 커스텀 skill/stat/gear 콘텐츠 수치, HUD 팩/배치, 실제 동접/운영 자원 및 배포 라이선스/승인은 남아 있다. 시험값을 운영값으로 채택하지 않았다. 운영 설치/프로세스 재시작·패키지/SSH/방화벽·기존 월드/DB 변경을 하지 않았다.
