# 경제 서버 공통 플랫폼

[플랫폼 구현·검증](../../docs/economy-platform-verification.md)과 [ADR-0033](../../docs/decisions/0033-economy-platform-lifecycle.md)을 따른다. 강화·자동 판매·효과 출처·설정 이전·자원 월드 교체를 기존 경제/RPG 기반에 연결한다. 획득처·상품 구성·확률/가격·주기·레벨 디자인을 확정하지 않는다.

## 활성화·정본

기존 survival backend의 경제·거주·AuraSkills·economy-rpg와 필요한 combat-travel/자체 인챈트를 유지한다. [설정 절](platform.yml)을 병합하며 기본은 비활성이다. 최초 활성 기동이 `plugins/Hearth/platform.properties`를 설치하고 기존 파일은 덮어쓰지 않는다. [기본 예제](../../src/main/resources/platform.properties)의 upgrade/autosell/resource-reset도 각각 false다. 기능별로 설정을 마친 뒤 해당 스위치를 켠다. 검증용 수치는 운영 밸런스가 아니다.

E012는 자동 판매 개인 설정·자원 세대/전환 상태·설정 변경 영수증을 보존한다. 강화는 기존 E011 영수증/native 그림자와 UPGRADE 저널을 재사용한다. 평상시 아이템 정본은 native이며 재접속 때 PG 그림자로 덮어쓰지 않는다. 활성 자원 세대/진행 중 변경/활성 판매 설정이 있으면 플랫폼 전체를 끄는 기동을 거부한다. 기능만 닫으려면 플랫폼을 유지하고 개별 스위치를 끈다.

## 플레이 공통 메뉴

`/플랫폼`(`/강화`, `/자동판매`도 같은 공통 메뉴)을 열어 강화·판매 설정·장비 작업 기록을 선택한다. GUI는 원본 물품을 보관하지 않고 실제 슬롯을 지목한다. Shift/숫자키/드래그/커서/중복 확정과 오래된 접속/설정 세대를 검사한다.

강화는 장비 → 사용할 보호 주문서 → 비용·재료·확률·실패 정책 확인 → 확정이다. `upgrade.equipment`, `upgrade.max-level`, `upgrade.cost`, `upgrade.chance`, `upgrade.failure`, `upgrade.material.<MATERIAL>`로 설정한다. 수식 변수 `level`은 **목표 강화 레벨**이며 기존 인챈트의 안전한 Formula 엔진을 사용한다. 현재 최대 기술 상한은 1000이며 콘텐츠 최대 레벨은 설정한다.

실패 정책은 KEEP/DOWN/RESET/DESTROY다. 선택한 강화 보호 주문서는 한 개 소비하며 실패 시 장비/강화 단계를 보존한다. 일반 PAPER와 다른 특수 책을 보호 재료로 인정하지 않는다. 관리자 `/플랫폼 give-protection <접속 이름>`은 같은 native/PG 거래로 한 개 발급한다. 실제 획득처를 추가하지 않는다. 비용/일반 재료는 성공·실패 모두 같은 요청 결과에 포함된다.

장비의 `hearth:upgrade` INTEGER는 유지하고 `hearth:equipment_version=1`을 추가한다. 구버전의 버전 없는 정상 강화 값도 읽는다. 알 수 없는 버전/비정상 레벨은 거부한다. 자신이 만든 강화 lore만 교체하며 특수/바닐라 인챈트·내구도·외부 PDC/lore를 보존한다. 난수/결과·전후 해시·요청 ID는 한 번 저장하고, replay를 재지급으로 처리하지 않는다.

## 자동 판매

`autosell.goods`에는 **기존 상점 good ID**를 나열한다. 판매 가격/재고/시장 영향은 기존 상점과 같으며 별도 가격 정본을 만들지 않는다. 사용자는 메뉴에서 개인 켜기/끄기와 상품 필터를 저장한다. 서버 스위치·서버 허용 목록·개인 스위치/목록을 모두 만족해야 한다. 상품 목록은 페이지로 표시한다.

대상은 소지 슬롯 0~35의 기존 상점에서 취급하는 일반 아이템이다. 장착/보조 손·커서·다른 보관함은 판매하지 않는다. 이름/PDC·강화·특수 인챈트 등으로 plain 상품과 다른 물품은 제외한다. 아이템을 버리거나 새로운 보상을 만들어 지급하는 방식이 아니라, 실제 최신 인벤토리를 native 거래로 소비하고 PG 잔액/재고를 한 번 정산한다. 다른 창/거래/이동/사망·세대 변경 중에는 실행하지 않는다.

`autosell.interval-ticks`(최소20)·`autosell.max-quantity`로 호출 빈도/수량을 제한한다. 36슬롯은 한 번 디코드/집계하고 상품별 DB 요청을 전부 보내지 않는다. 한 pulse에 최대8개 요청과 1024개의 알림 큐, 순환 접속자 검사 상한을 둔다. pickup/최종 수집 알림은 **검사 예약**만 하며 제출된 수량을 신뢰해 화폐를 주지 않는다. `/플랫폼 sell`은 같은 정책으로 검사를 요청한다.

RewardCoordinator에서 이미 감소한 보상에 판매 시 페널티를 다시 적용하지 않는다. Fortune/Looting/추가 드랍·Smelt의 최종 결과를 소지품에서 확인한다. 수급 경합은 기존 견적 변경 거부, DB/commit 불명·native 적용 실패는 기존 HOLD/원본 보존 경로다.

## 능력치 출처와 확장

`RpgAura.statLedger`는 장비/강화·커스텀 패시브·자체 인챈트의 출처를 구별한다. 같은 출처 갱신은 값을 교체하며 누적하지 않는다. 인챈트는 ID·장비 슬롯·stat별로 구별한다. ADD 또는 MAX_ABSOLUTE와 합산 cap을 `stats.stacking`, `stats.cap`으로 설정한다. Aura에는 최종 Hearth 합계의 안정된 nonpersistent modifier만 차분 반영한다.

다른 플러그인의 modifier를 지우거나 native Aura 효과를 다시 등록하지 않는다. 장비 교체·금지 월드·사망/퇴장 시 자신의 출처만 갱신/회수한다. `/플랫폼 stats`는 본인 출처/합계, 관리자 `/플랫폼 stats <접속 이름>`은 해당 계정의 진단이다. 외부 Aura modifier는 합산 모델 밖에 그대로 존재한다. 운영 능력 구성에서 같은 의미의 효과를 양쪽에 선언할지는 추후 정하며 숫자가 같다는 이유로 임의 제거하지 않는다.

확장 모듈은 기존 공개 인챈트 효과 등록·`RpgRewards.deliver`·최종 수집 observer·`RpgAura.source/removeSource/replaceSources`를 사용한다. Bukkit/API 호출은 주 스레드에서, PG는 기존 비동기 worker에서 실행한다. 임의 외부 플러그인의 직접 지급/직접 SQL까지 자동으로 통제한다고 보장하지 않는다.

## 설정 후보·리로드·복구

관리 권한은 `hearth.platform.admin`(기본 op)이다. `/플랫폼 admin` → **설정 변경 작업대** 또는 `/플랫폼 config`로 후보/보류 목록을 연다. 개별 후보를 선택하면 로컬/PG 상태·원본/후보 해시·현재 파일 위치(BEFORE/AFTER/EXTERNAL)·최대 13줄의 비교 미리보기를 확인하고 적용/롤백한다. 미리보기는 줄 위치 기준 요약이며 전체 diff가 아니다. 상세 내용은 보존한 before/after 파일을 비교한다. GUI와 명령은 같은 API/정본을 사용한다.

```text
/플랫폼 config-stage platform.properties tuning.candidate
/플랫폼 config list
/플랫폼 config inspect <후보 UUID>
/플랫폼 config apply <후보 UUID>
/플랫폼 config rollback <후보 UUID>
/플랫폼 config recover
/플랫폼 history
```

기존 config-apply/config-rollback 명령도 유지한다. candidate는 Hearth 데이터 폴더의 `이름.candidate` 일반 파일이다. 허용 정본은 `platform.properties`, 기본 이름의 `rpg-policy.properties`/`rpg-content.properties`, `enchants/<ID>.yml`/`operations.yml`이다. 네트워크·인증/DB 접속 설정은 이 도구가 수정하지 않는다. 다른 이름/불변 데이터 구조를 바꾸는 설정에는 어댑터가 필요하다.

후보는 `platform-changes/<UUID>/`에 원본(before)·후보(after)·해시·manifest를 보존한다. 적용/롤백 의도(PREPARING/ROLLBACK_PREPARING)를 로컬에 먼저 기록하고 E012 PG를 PREPARED로 만든 뒤 원자 파일 교체 → live 설정/예약 구성 → PG 확정 → 로컬 확정 순으로 진행한다. ROLLBACK_APPLIED는 파일만 복원한 **미완료** 상태이며 PG까지 확정한 ROLLED_BACK과 구분한다. 전체 파일+PG는 하나의 분산 트랜잭션이 아니다.

기동 시 로컬과 PG 전체 기록(각 최대4096개)을 대조한다. 중간 상태·누락/변조/부분 생성 증거·식별자/상태 불일치는 유지보수로 닫고 자동 예약 구성/dispatch도 보류한다. 구버전 ROLLED_BACK이라도 PG가 PREPARED/COMMITTED이면 미확정으로 취급한다. 문제가 있는 후보 이외의 후보를 교차 적용하지 않는다. 원본/후보가 같고 현재 파일이 양쪽 해시 중 하나일 때만 같은 UUID로 적용/롤백을 재개한다. 외부 수정이나 충돌하는 `.platform-writing`은 보존하고 덮어쓰지 않는다. 남은 임시 파일이 보존된 목표와 정확히 같으면 기존 쓰기만 완료할 수 있다.

복구는 `config list` → `config inspect UUID` → 같은 UUID의 apply/rollback 순서다. 성공한 live 변경은 다른 미확정 기록이 없으면 설정 유지보수를 해제한다. 외부 파일을 운영자가 보존한 정상본으로 복원한 후에는 `config recover`가 전체 저널/PG 일치·현재 platform/인챈트 설정·예약 구성을 재검증하고 live 설정을 다시 읽는다. 조회(list/inspect)만으로 유지보수를 해제하지 않는다. 부분 생성/누락 증거를 자동 삭제·재작성하거나 PG 기록만 확정하는 명령은 없다. 증거가 불명하면 원본/백업 확인 후 수동 대응한다.

platform/인챈트는 live 적용한다. RPG 정책/registry는 온라인 0명일 때만 적용하며 **같은 프로세스에서 recover를 해도 재시작 요구는 유지**한다. 정상 drain 후 같은 파일과 DB로 새 프로세스를 시작한다. 미완료 저널은 새 프로세스에서도 자동 해제하지 않는다. 기존 등록 ID 제거·월드 key/seed/terrain 변경은 거부한다. 설정 롤백은 장비/잔액을 과거로 되돌리지 않으며 native/사망/세계/작업 HOLD를 해제하지 않는다. 전역 HOLD에서 관리자 접속을 우회하지 않으므로 기존 콘솔을 쓴다. 운영 프로세스 재시작에는 별도 승인이 필요하다.

일반 reload는 준비 상태에서만 허용하며 예약 정책 변경은 config 경로를 사용한다. 파일/PG 조회는 기동·명시적 관리 요청/설정 작업에서만 수행하고 tick 경로에는 추가하지 않는다. [후속 검증 기록](../../docs/platform-configuration-verification.md)을 참고한다.

## 정지 상태의 경제·시장 정책 이전

경제의 콘텐츠 해시/시장 정책은 기존 binding·PG와 함께 바꿔야 하므로 별도 `nativePolicyTool`을 제공한다. **운영 실행/서비스 정지는 별도 승인 대상**이며 도구가 서버를 끄거나 재시작하지 않는다. 정상 drain/일관 백업과 정지·미정리 거래/사망 확인을 선행한다.

```text
gradle nativePolicyTool -PpolicyAction=stage -PpolicyPaper=<정지 Paper 루트> -PpolicyKind=economy -PpolicyCandidate=<후보 properties> -PpolicyPlan=<새 계획 폴더> -PpolicyOffline=offline-confirmed
gradle nativePolicyTool -PpolicyAction=apply -PpolicyPaper=<동일 루트> -PpolicyKind=economy -PpolicyCandidate=<동일 후보> -PpolicyPlan=<동일 계획> -PpolicyOffline=offline-confirmed
```

rollback은 동일 계획에 `policyAction=rollback`을 사용한다. economy 후보는 기존 전체 경제 properties를 기준으로 base/target·수요·생산 용량/효율·운송량/지연·가격 계수/틱 간격을 바꿀 수 있다. ID/재질/토폴로지·상점 구조·초기 잔액/소유권은 자동 변환하지 않는다. market 후보는 interval-ticks/confirmations/minimum-hold-ticks/reserve-*-bps/buffer-ticks/low-stock-bps 숫자 필드만 포함한다.

기존 config.yml의 대상 section은 일반 2칸 들여쓰기 scalar 형식을 사용해야 한다. 복잡한 YAML 문법/중복 키는 해석을 추측하지 않고 거부한다. 시장 수정은 검토한 숫자 줄만 바꾸고 다른 설정 내용을 보존한다. 접속 비밀번호는 기존 환경 변수에서만 읽으며 계획/출력에 넣지 않는다. 정책 이전은 잔액·인벤토리·재고·운송·진행 시계를 초기화하지 않는다. 정책을 되돌려도 최신 데이터는 유지한다.

DB 확정 전후의 중단은 `native-policy-migration.pending`으로 기동을 막는다. 같은 검토 계획의 apply/rollback이 파일/PG의 전후 해시를 확인해 재개한다. 불명 외부 상태/다른 pending은 거부한다. 표식 삭제나 DB 행 직접 수정으로 우회하지 않는다. 이전 버전 아이템을 일괄 재작성하는 작업이 아니다.

## 자원 월드 세대 교체

```text
/플랫폼 reset-list
/플랫폼 reset novice <seed>
/플랫폼 reset wilderness <seed>
/플랫폼 reset nether <seed>
/플랫폼 reset end <seed>
/플랫폼 reset-resume <요청 UUID>
/플랫폼 reset-abort <요청 UUID>
```

`resource-reset.enabled=true`가 필요하다. 기본/개인 거주는 허용 대상이 아니다. 기존 월드/폴더를 지우지 않고 요청별 `hearth_resource:r_<role>_<UUID>`를 만든다. PREPARING → READY → ACTIVE로 진행하며 새 청크/안전 지점·WG 보호·원본 저장/대피·역할 교체/이전 월드 언로드·PG 활성화를 연결한다. 처리 시간은 `resource-reset.timeout-seconds`(10~300) 안에서 제한하고 불명/timeout 이후 자동으로 입장을 다시 열지 않는다.

준비 청크는 DB 확정 중 plugin ticket으로 유지하고, 활성화 때 기존 월드 관리자로 소유를 넘긴다. 남은 후보 ticket은 종료/중단 정리 대상이다. 공통 admission이 준비 중인 역할을 닫으므로 메뉴/포탈/TPA/Home 등의 최신 검사가 같은 제한을 받는다. 대피는 시스템의 공용 스폰 이동이며 전투 태그 자체를 지우지 않는다. 바쁜/불명 이동이 있으면 원본을 유지하고 보류한다.

기존 base/extra registry와 경제 바인딩은 원본을 유지하고 활성 세대를 별도 E012 기록으로 복원한다. 이전 UUID를 가리키는 Home/이동을 새 좌표로 임의 해석하지 않는다. 인챈트 제한은 실제 key와 원래 논리 key, `role:novice|wilderness|nether|end`를 인식한다. 세대마다 설정을 다시 쓰지 않으며 관계없는 역할은 열지 않는다.

PREPARING은 후보 UUID가 미확정이므로 임의 재생성하지 않는다. READY는 기록된 key/UUID/seed/청크를 확인한 명시적 재개만 허용한다. ACTIVE 확정 결과가 불명이었던 경우도 같은 UUID의 조회/재개로 확인한다. abort는 원래 역할이 그대로인 준비 단계에서만 허용하고 후보/이전 파일을 삭제하지 않는다. 기존 활성 세대에 후속 pending이 있으면 재기동 후에도 해당 역할은 닫힌다. 원본/과거 세대 보존 때문에 저장 공간은 증가하며 보존/정리 정책·자동 실행 주기는 추후 결정한다. 호출 API/명령 엔진과 실제 주기 운영을 구분한다. [영속 예약·관리자 작업센터](platform-jobs.md)는 경제 논리 시계와 같은 교체 엔진을 사용하며 기본 비활성/주기0이다. 운영 주기를 선택하지 않았다.

## 관측·백업·검증 경계

status는 준비/유지보수·설정 세대·판매 큐/처리중·자원 세대/보류·native HOLD 수와 유한 집계 시간/실패·TPS/MSPT/heap을 조회한다. 개인 아이템/질문 원문을 지표로 기록하지 않는다. 기존 모듈/PG/native 저널이 상세 영수증 정본이며 공통 화면이 임의 보정/재지급을 하지 않는다.

기존 `offline_backup.py`로 **정상 정지 Paper 전체 + PG 전체**를 같은 묶음에 백업한다. Aura YAML·WG·기본/개인/자원 세대·인벤토리·인챈트/강화·설정 변경 원본과 저널이 모두 포함된다. `verify_platform_backup.py`는 통과한 소유 격리 root만 받아 전체 묶음을 검증·복원하고, 복원본의 두 JVM 검증은 별도로 수행한다. 운영 백업 주기/보존/오프사이트·RPO/RTO는 확정하지 않았다.

실제 클라이언트 조작·다인 전투/거래·실제 대피·목표 동접/장기 부하와 운영 배포는 별도다. 자동 검사/계획기 계측을 실제 플레이 성공이나 운영 용량 합격으로 표시하지 않는다.

예약/수동 reset의 공통 이력·중단 결과 확인은 [작업센터 가이드](platform-jobs.md)를 따른다. 예약 정책 변경은 config-stage/apply로 처리하며 일반 reload로 바꾸지 않는다.
