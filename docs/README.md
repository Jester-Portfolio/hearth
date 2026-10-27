# 설계 문서 안내

| 문서 | 역할 |
| --- | --- |
| [PROJECT_STATUS.md](PROJECT_STATUS.md) | 현재 단계·완료 근거·환경·미결정 및 미검증 항목 |
| [stack-build-verification.md](stack-build-verification.md) | S2 빌드·API 참조 컴파일·계약 검토와 미검증 런타임 범위 |
| [s4-1-verification.md](s4-1-verification.md) | S4-1 빌드/파일·격리 서버 smoke 결과, 초기 오류/수정·자원·미검증 범위 |
| [s4-2-verification.md](s4-2-verification.md) | 두 JVM 영속성·자연 피스톤 이벤트·API 판정, 접속 플레이어 미실행·자원/오류 |
| [s4-3-verification.md](s4-3-verification.md) | 인증 한 명의 A 보호 부분 검증·기존 준비 이력·남은 B/권한/반복 시험 |
| [s4-4-verification.md](s4-4-verification.md) | 최소 PostgreSQL 스키마·비동기 저장·진행/개인 격리·revision·실패·재연결 실DB 시험 |
| [s4-5-verification.md](s4-5-verification.md) | Paper libraries·실DB 통합·두 JVM 상태 보존·대표 경합/오류·자원/정리와 미실행 경계 |
| [s4-6-verification.md](s4-6-verification.md) | V002·41슬롯 NBT·dirty/30초·실DB/런타임과 실제 플레이어 미검증 범위 |
| [s4-7-verification.md](s4-7-verification.md) | source 저장 후 전환·실패/HOLD·stale/중복·종료의 실제/단위 시험 |
| [s4-8-verification.md](s4-8-verification.md) | 두 Paper JVM 인벤토리 복원·제한 반복/오류·자원/정리·미검증 경계 |
| [s4-9-verification.md](s4-9-verification.md) | 수명주기·예약·IDLE·세대 단위 조건 통과와 실제 시험 경계 |
| [s4-10-verification.md](s4-10-verification.md) | 저장 순서·실패 보존 단위 및 실제 기존 월드 재진입/대표 거부 검증 |
| [s4-11-verification.md](s4-11-verification.md) | watchdog 실패 이력·수정본 두 JVM 재검증 통과·자원/정리와 미실행 경계 |
| [s4-shared-story-batch-verification.md](s4-shared-story-batch-verification.md) | V006·공유 단계/조건/채팅 투표·분기·탐험/지역 접근·두 JVM 검증 |
| [s4-exploration-batch-verification.md](s4-exploration-batch-verification.md) | V005·개인 발견/공개 지도·일지·공동체 읽기 경계·설정·두 JVM 검증 |
| [s4-quest-batch-verification.md](s4-quest-batch-verification.md) | 다단계/V004·추적·개발 HUD·스마트폰·알림/설정·두 JVM 검증 |
| [personal-quest-verification.md](personal-quest-verification.md) | 다중 NPC·개인 퀘스트·읽기 전용 목록/상세·단계별 실제 검증·클라이언트 보류 |
| [player-session-manual-test.md](player-session-manual-test.md) | 실제 인증 한 명의 보호·복원·빠른 재접속·IDLE·두 JVM 결과 및 수동 절차 |
| [player-session-verification.md](player-session-verification.md) | 격리 세션 이벤트/저장·빠른 재접속·다중 장벽 자동 검사와 후속 실제 기록 연결 |
| [plugin-suitability-review.md](plugin-suitability-review.md) | S4 사전 조사: WorldGuard/WorldEdit 의존성·버전 근거, Multiverse-Core/Paper API 비교와 미실행 시험 계획 |
| [story-world-detailed-design.md](story-world-detailed-design.md) | S3 설계 완료 기준선: 승인 정책·수명주기/DB/보호 후보·보류·미실행 시험 |
| [ROADMAP.md](ROADMAP.md) | 단계별 상태·목표·완료 조건·선행 조건 |
| [CHANGELOG.md](CHANGELOG.md) | Git 기록에 근거한 주요 변경 이력 |
| [architecture.md](architecture.md) | B안 채택 구조, 시스템 경계, 데이터 분리와 미정 항목 |
| [story-world-architecture.md](story-world-architecture.md) | B안 채택 근거를 포함한 세 구조 비교와 남은 검증 항목 |
| [story-world-feasibility.md](story-world-feasibility.md) | B안 다중 월드의 구현 경로, 데이터 격리와 기술적 제약 검토 |
| [ADR-0001](decisions/0001-story-world-multiworld.md) | 승인된 B안 채택 결정과 영향·미결정 사항 |
| [ADR-0003](decisions/0003-inventory-performance-and-recovery.md) | 성능 우선 아이템 저장·정상 동작 안전성·관리자 수동 장애 대응 |
| [decisions/README.md](decisions/README.md) | 주요 기술·설계 결정의 기록 규칙 |
| [스토리 월드](../minecraft/story/README.md) | 스토리 월드의 목적과 설계 범위 |
| [경제 월드](../minecraft/economy/README.md) | 경제 월드의 목적과 설계 범위 |
| [서비스](../services/README.md) | 보조 서비스의 범위 |
| [인프라](../infrastructure/README.md) | 배포와 운영 설계의 범위 |
| [스크립트](../scripts/README.md) | 자동화 작성 원칙 |

[ADR-0002](decisions/0002-stack-and-world-policies.md)는 확정 기술 스택과 템플릿·콘텐츠·진행 분리, 보호·채집 정책의 기준입니다. 과거 비교·가능성 검토는 작성 당시 근거로 보존하며 후속 결정 링크를 따릅니다.

문서에는 확정된 요구사항, 제안, 미정 사항을 구분합니다. 주요 결정은 의사결정 기록을 남기고 관련 설계 문서에 반영합니다. 실제 운영 구성을 확인하지 않은 내용은 검증된 사실로 기록하지 않습니다.

작업 시작 시 현황·로드맵·관련 ADR을 확인합니다. 현재 사실, 단계별 계획, 주요 이력과 확정 설계의 역할을 구분하고 상세 내용은 해당 문서로 연결합니다. 문서 갱신과 두 환경 간 인계·커밋 규칙은 [AGENTS.md](../AGENTS.md)를 따릅니다.

- [ADR-0004: 공동체·퀘스트·장별 따라잡기와 스킵](decisions/0004-community-quests-and-chapter-skip.md) — 승인된 운영 정책. 구현·런타임 시험 미완료.

- [S3 완료 조건 감사](s3-completion-audit.md): C1 최종 승인·필수 12영역 명세 충족에 따른 S3 설계 완료, S4 실행 이관/의도적 보류 구분.
- 통합 후속 정책: [ADR-0010](decisions/0010-coop-information-and-hud.md), [ADR-0011](decisions/0011-votes-boss-return-and-gathering-reset.md), [ADR-0012](decisions/0012-progress-storage-world-lifecycle-and-content.md).

- G1~G4 후속: [ADR-0013](decisions/0013-vote-boss-and-gathering-state-boundaries.md)과 [ADR-0014](decisions/0014-worldguard-protection-engine.md), 최신 [S3 재감사](s3-completion-audit.md).
