# Hearth 자체 인챈트 구현·검증

날짜: 2026-11-20. [운영/설정·32개 효과 목록](../infrastructure/network/enchants.md), [105종 대응표](enchant-port-map.md), [구조화 증거](../verification/enchant-system-result.json), [ADR-0032](decisions/0032-native-enchantment-engine.md)를 함께 사용한다.

## 결과와 적용 범위

CrazyEnchantments 고정 소스의 **105종 모두에 자체 실행 규칙과 구체 효과 모듈을 연결했다(구현 대응률 105/105)**. 외부 CrazyEnchantments 플러그인/클래스를 로드하지 않는다. 이 비율은 소스 목록·설정·실행 모듈의 대응률이며 **105종 실제 플레이 성공률을 뜻하지 않는다**. 사용자가 보류한 정품 클라이언트 시험, 다인 전투/거래·GUI 클릭·지속 부하와 운영 배포는 미실행이다.

기준은 [CrazyEnchantments 6ecea9a19affa811a8e23dd47fe647482507b82c](https://github.com/Crazy-Crew/CrazyEnchantments/tree/6ecea9a19affa811a8e23dd47fe647482507b82c)의 `CEnchantments` 등록 목록과 개별 YAML·효과 처리기다. 두 목록의 105종을 전부 대조했다. 해당 커밋의 기본 YAML은 모두 Enabled=true이며 누락할 비활성 항목은 없었다. Hearth는 개별 비활성과 알 수 없는/비활성 기존 ID 보존을 별도로 지원한다. [대응 JSON](../verification/enchant-port-mapping.json)은 각 ID·원본 부위/레벨·트리거·효과·정의 해시·실제 클라이언트 미검증을 기록한다.

## 요구별 구현

| 요구 | 구현 | 검증·한계 |
| --- | --- | --- |
| 설정형 엔진 | Trigger/Condition/Effect/Target/Chance/Cooldown/Duration/Formula, 32개 Java 모듈, 공개 확장 등록 | 105종 규칙 계획·등록 모듈 자동 대조. 실제 플레이 효과 전수 수용은 보류 |
| 장비/부위/개수/충돌/월드 | 종류·슬롯의 독립 상한과 낮은 값 적용, 양방향 충돌, 기존 데이터 보존 | 적용 계획/메타데이터 검사. 바닐라 인챈트 수는 제외 |
| 책 | 생성/PG 지급·미감정 고정 데이터·감정·기존 native 거래·장비 적용, 보호/증가 재료 | Paper PDC/41슬롯 codec·성공/실패/보호/재료 검사. 실제 획득처 미선정 |
| 합성 | 같은 ID/레벨 2권→다음 레벨, 최대 레벨·확률/비용/재료·ALL/ONE/KEEP 실패, GUI/기록 | 같은 스택 2권과 불일치/최대/실패 계획 검사 |
| 회수 | 대상 ID 선택, 성공은 동일 레벨 책, 실패는 선택 ID만 소멸, 확률/비용/재료·GUI/기록 | 다른 특수/바닐라 인챈트·PDC·외부 lore·장비 보존 검사 |
| 관리자 | 개별 YAML, 신규/복제/활성/룰·주요 작업 값 GUI와 명령, 안전 리로드 | 오류 후보 거부·기존 세대 보존·단일 파일 변경 검사. 실제 채팅/클릭 미실행 |
| 저장/중복 방지 | 안정 ID/PDC 버전1 + PG E011 영수증·native 그림자 CAS·저널·고정 roll | 실제 PG 경합/SQL 실패/소유권과 두 JVM 복원 검사 |
| 보호/기존 시스템 | Hearth admission/거주 ACL/WG·PvP/전투 TP·사망/부적·보상·Aura modifier/Fly | 기존 정책 경로 호출과 격리 enable/API 검사. 실제 우회/전투 조합 수용은 보류 |
| 미래 연동 | Java 확장 모듈·최종 수집 DeliveryObserver/action ID | 자동 판매 자체는 이번에 구현하지 않음. 임의 명령/잔액 지급 기능을 효과로 제공하지 않음 |

## 구조·데이터 경계

`EnchantCatalog`가 YAML을 검증해 불변 세대를 공개하고 `EnchantEngine`이 장착 ID의 해당 트리거만 계획한다. 수식은 컴파일된 유한 AST이며 임의 코드 실행을 허용하지 않는다. PDC 해석 캐시·이벤트 처리 표식·공유 쿨다운·투사체/소환수/임시 블록/작업 크기의 상한을 둔다. 반경 친구 계산은 해당 변수를 사용하는 룰에만 수행한다. 전투 이벤트마다 전체 카탈로그/파일/PG를 읽지 않는다.

아이템은 안정 ID/레벨과 책의 고정 성공/파괴/증가 값을 PDC로 가진다. 버전 불명 데이터는 수정하지 않는다. 고유 표식이 있는 Hearth lore만 교체하므로 강화 수치·타 플러그인 데이터·바닐라 인챈트를 보존한다. 설정에서 ID를 없애거나 최대 레벨/개수를 낮춰도 기존 데이터를 삭제하지 않고 효과/신규 적용을 제한한다.

GUI는 가상 슬롯 선택 방식으로 원본 물품을 별도 보관하지 않는다. 확정 요청은 UUID·설정 세대·원본 인벤토리·장비/책/보조 재료 슬롯에 묶인다. 주 스레드에서 계획한 결과/난수는 PG에 전달하며 DB 콜백에서 Bukkit 인벤토리를 만지지 않는다. E011은 잔액 차감·원본 revision CAS·새 native 그림자·요청/결과 영수증을 한 트랜잭션에 저장한다. 동일 UUID의 다른 지문/roll은 거부하며 replay 응답은 지급 payload를 반환하지 않는다. 클라이언트 적용은 기존 NativeTransactions 저널/세션 장벽을 통과한다. commit 불명·stale·세대/권한 변경은 원본과 기록을 보존하고 HOLD한다. 자동 재굴림/중복 지급·임의 복구는 하지 않는다.

PG에는 결과·roll·before/after 해시를 남기고 41슬롯 원본 전체를 거래마다 중복 적재하지 않는다. 쿨다운은 메모리에서 판정하고 변경 묶음을 저장하며 정상 drain/새 JVM에서 복원한다. 비정상 종료의 인벤토리 원자 적용·임시 지형까지 무손실 자동 복구를 보장하지 않는다. [ADR-0003](decisions/0003-inventory-performance-and-recovery.md)의 수동 HOLD 대응을 유지한다.

## 원본 적응·정책 충돌 처리

- Wings는 Factions 대신 허용된 개인 거주·건축 권한·공통 입장·전투 제한으로 재구성한다. 이전 allowFlight를 기록해 자신이 부여한 lease만 회수한다.
- 아군은 scoreboard team/같은 허용 개인 거주 구성원·자기 소환수다. 원본 faction 전용 판정은 의존하지 않는다.
- Aura 스탯은 자체 이름의 비영속 modifier로 차분 갱신하고 해제/월드 이동 때 회수한다. 강화/다른 modifier를 지우지 않는다. 포션도 소유한 효과/만료를 구별한다. 같은 능력의 Aura 기본 효과와 중복되지 않도록 운영 시 양쪽 설정을 함께 선택해야 한다.
- 광역 채굴/벌목은 로드된 블록의 WG/거주 권한을 각각 확인하고 실제 `Player.breakBlock` 경로로 제한된 수량만 실행한다. 재귀·광역 증폭을 차단한다. 성숙 작물·씨앗 비용·내구도를 적용한다.
- Smelt/추가 드랍/수집은 기존 RewardCoordinator의 동일 action/producer와 최종 보상 경계를 사용한다. 초보 월드 감소는 역할 범위를 설정하며 기존 설정에서 키가 없으면 이전 전 월드 의미를 보존한다. 추가 아이템 entity를 BlockDropEvent 목록에 끼워 넣지 않는다. 미래 생산자는 같은 coordinator를 사용해야 하며 임의 외부 플러그인 지급까지 통제한다고 보장하지 않는다.
- 폭발은 native 귀속 피해를 사용하고 지형 파괴는 기본 비활성이다. SelfDestruct가 Hearth의 사망 분배/부적 결과를 임의로 비우지 않는다. summon은 지속 저장·아이템/XP 보상을 막고 소유자/정책·수명/언로드에서 정리한다.
- Sticky-Shot은 엔티티/블록 impact를 하나의 룰로 처리한다. 엔티티 근처 공기 3×3·블록 명중점 공기만 임시 치환하며 WG 검사·원본/복원 기록·파괴/피스톤/폭발 차단을 둔다. 정상 종료/언로드에서 복원하고 불명 종료는 기존 HOLD·수동 복구 대상이다.
- 원본 SkillSwipe의 부족한 XP 생성, Harvester의 성숙도 조건, Recover의 착용자/처치자 불일치는 설명에 맞춰 수정했다. MultiArrow는 원래 화살의 피해/치명타/화염을 복사하고 추가 화살 회수를 막는다.

## 실행한 검증

원시 증거는 모두 사용자 소유 `/home/server/hearth-tmp` 아래에 보존했다. 파일별 해시/결과/자원 측정은 구조화 증거를 따른다. 같은 코드/계약의 성공 검사를 불필요하게 반복하지 않았으며 효과/컨텍스트 변경 후에는 해당 Paper 확인만 추가했다.

| 구분 | 결과 | 범위 |
| --- | --- | --- |
| Gradle compile/JAR | PASS | Java25·Paper26.2 API, CE runtime 의존성 없음 |
| 순수 Java | PASS | 인챈트 8개 고유 검사, 관련 RpgPolicy 10개, 기존 native journal/transaction 15개 회귀. 순차 변경에 영향받은 검사만 재실행 |
| PG E011 | PASS 13개 | 중복 UUID 경합·한 번 차감/저장, replay 무지급, 소유/issuer/roll, stale·철회, SQL 실패 rollback, 실패 회수 기록·쿨다운·기록 범위 |
| 두 Paper JVM | PASS 준비157/복원158개 assertion | 105종 모듈·PDC/책/적용/합성/회수/감정·메타데이터·41슬롯 codec·관리 후보 거부, 실제 비플레이어 Zombie heal, PG 영수증/쿨다운/아이템 새 JVM 복원 |
| 최종 영향 검사 | PASS 임시 블록4개·화살1개 | native 공기 grid/원본 제외/복원·잘못된 포션 후보 거부, 실제 Arrow 2개의 속성 복사. 마지막 컨텍스트 수정 JAR의 화살 검사도 PASS |
| 원본 보존/정리 | PASS | 소스 hash·다른 realm/스토리 자료 보존, 시험 프로세스 정상 종료·cleanup 오류 없음 |
| 원본 재생성 | PASS | 고정 clean checkout에서 importer를 새 후보 경로에 실행, 105개 정의+manifest 일치 |

초기 단위 검사의 Adrenaline health fixture가 원본 조건과 달라 수정했다. 초기 Paper 시험은 **검증 helper**의 DB 콜백에서 codec을 호출해 주 스레드 검증에 실패했다. 실제 실행기의 경계를 완화하지 않고 helper를 owner executor로 옮긴 후 영향 배치를 통과했다. 실패 로그도 덮어쓰지 않고 보존했다. 마지막 실행 전 JAR 파일명의 대소문자 오입력은 artifact preflight에서 거부됐고 프로세스/DB를 시작하지 않았다.

## 성능·한계

주 배치 두 JVM+PG 시험은 51.796초였다. Paper 최대 표본 RSS는 준비 1,316,216KiB/복원 1,395,136KiB(약 1.33GiB), DB 237,508KiB였다. Paper CPU 관측은 각각 38.41/39.26 CPU초로 기동·월드 로드가 포함된다. CPU 2개·Paper heap 1536MiB/RSS 2560MiB·합산 RSS 6144MiB·600초/작업550초의 기존 상한 안에서 실행했다. 최신 좁은 재검사 수치는 JSON에 별도 기록한다.

접속 0명의 기동/API 검증이므로 **유저당 오버헤드·MSPT/TPS·실제 광역 채굴/전투 부하·장기 누수/동접 한계는 판정하지 않았다**. 이 측정으로 운영 성능 합격을 선언하지 않는다. 포션 혼합/비행 lease·간접 WG 보호·전체 실제 트리거/클릭·다인 경합·장애 시점 인벤토리 수용은 별도 실제 시험이 필요하다.

## 후속 수용·운영

정식 획득처, 최대 레벨·부위별 개수·수치/확률/가격/재료·초보 보상 역할과 Aura 중복 능력 조합은 전체 시스템 뒤 밸런싱한다. 다음은 이 값 선택과 사용자 재개 요청 후 실제 플레이를 한 흐름으로 수용하고, 목표 동접/장기 부하·일관 백업 복구를 확인하는 것이다. 자동 판매 등 별도 미구현 기능은 공개 연동 경계로 연결한다.

운영 서비스/DB/월드/SSH/방화벽/패키지를 변경하지 않았다. 기본 비활성이다. 배포 시 별도 승인 후 단일 writer 정상 drain·native/Aura/WG/PG 일관 백업·격리 복구·이전 JAR/설정 보존을 선행한다. 설정을 되돌릴 때 기존 아이템 PDC/영수증을 삭제하지 않는다.

MIT 원본 저작권은 JAR에 [그대로 번들](../src/main/resources/META-INF/licenses/CrazyEnchantments-MIT.txt)하며 [출처 고지](../THIRD_PARTY_NOTICES.md)를 보존한다. 외부 CrazyEnchantments JAR은 설치/배포하지 않았다.
