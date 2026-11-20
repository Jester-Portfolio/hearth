# ADR-0031: 경제 서버 AuraSkills 기반과 Hearth 정책 경계

- 날짜: 2026-11-20
- 상태: [기반/공통 접근](../auraskills-admission-verification.md)·[전투/TPA](../combat-travel-verification.md) 이후 [잔여 시스템 일괄 구현](../economy-rpg-verification.md). 미정 콘텐츠/정책·실제 클라이언트/부하·운영 배포는 미완료.
- 근거: [기술 검증·요구별 결과](../auraskills-technical-verification.md), [구조화 증거](../../verification/auraskills-technical-result.json).

## 문제

경제 backend에 숙련도/레벨/스탯/스킬을 도입하면서 월드별 전투·사망·접근·보상·거주 권한과 연동해야 한다. 기존 PostgreSQL 사용만을 이유로 AuraSkills의 비공식 저장 포크를 만들지 않는다. 실제 플레이 시험은 사용자가 이번에 보류했다.

## 결정

1. AuraSkills 2.4.0 공식 JAR과 공개 API adapter를 사용한다. 기본 숙련도/스탯은 Aura가, 월드 역할·거주 ACL·입장/TPA·전투 태그·사망·최종 보상 정책은 Hearth가 담당한다. 로비/스토리에 자동 활성화하지 않는다.
2. 동접 4명·경제 skill writer 하나의 현재 구조에서는 **Aura YAML + Hearth PostgreSQL**을 권장 구성으로 선정한다. Aura 값의 PG 이중 정본과 reflection 저장 계층 교체는 하지 않는다. 다중 writer/실측 IO 병목이 생기면 공식 MySQL/MariaDB를 우선 비교한다. PG 포크는 공개 provider SPI 부재·SQL/DDL 변환·업데이트/라이선스 비용 때문에 보류한다.
3. 이름이 안정된 modifier로 장비/강화 효과를 교체·제거한다. 커스텀 mana 선언의 2.4.0 설정 키 불일치는 콘텐츠 `skills.yml`의 `mana_ability`로 회피하며 원본 JAR은 수정하지 않는다. 커스텀 선언과 실제 능력 입력/효과 구현을 구분한다.
4. 입장/이동은 하나의 최신 정책 검사, 보상은 협조하는 생산자의 최종 결과에 1회 적용하는 coordinator로 통합한다. 단일 Bukkit drop listener로 모든 미래 플러그인의 지급을 제어한다고 보장하지 않는다. Aura XP 파생 이벤트는 HandlerList를 상속하므로 base XP listener 하나를 사용한다.
5. Aura 기본 action bar/boss bar를 끄고 기존 Hearth UI/선택 BetterHud presenter가 표시를 소유하는 구성을 권장한다. 전체 백업은 Aura/native/WG/PG를 같은 manifest로 묶는다. 기존 인벤토리 성능·HOLD·수동 복구 원칙을 유지한다.

## 대안과 결과

전체 자체 숙련도 구현은 기성 레벨·스탯/기본 능력/설정·API를 중복 개발하므로 채택하지 않는다. 공식 MySQL은 확장 선택지지만 현재 추가 daemon·이원화 운영 비용을 먼저 부담할 근거가 없다. PG 어댑터는 기술적으로 포크에서 가능하나 단순 설정/API 확장 작업이 아니다.

격리 실제 enable/API/YAML 복원은 기술 기반의 증거다. 액티브 발동·간접 보호·실제 사망/이동/화면·동접/장기 부하는 미검증이다. upstream은 GPL v3이며 Hearth API 결합물 외부 배포 전 배포 라이선스·의무를 검토한다. 이번에는 외부 소스/JAR을 재배포하거나 프로젝트 라이선스를 변경하지 않았다.

## 후속·미정

기반/공통 접근·전투/사망·장비/보상·표시 시스템을 일괄 구현했다. 다음은 미정 정책/콘텐츠 선택과 실제 플레이/부하·운영 수용이다. 기본 거주 사망 정책, XP 종류/산식, 부적 범위, 레벨 집합/요구 값, 확률 판정 단위, 전투 예외, 운영 예산/라이선스는 상세 검증 문서를 따른다. 이번 검증은 이 값을 임의 확정하지 않는다.

운영 변경은 없으며 rollback은 필요하지 않았다. 향후 배포 시 단일 writer·정상 drain·일관 백업·격리 복구 리허설·이전 설정/JAR 보존을 포함한 별도 계획과 운영 승인이 필요하다.
