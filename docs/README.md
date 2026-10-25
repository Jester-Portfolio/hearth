# 설계 문서 안내

| 문서 | 역할 |
| --- | --- |
| [PROJECT_STATUS.md](PROJECT_STATUS.md) | 현재 단계·완료 근거·환경·미결정 및 미검증 항목 |
| [stack-build-verification.md](stack-build-verification.md) | S2 빌드·API 참조 컴파일·계약 검토와 미검증 런타임 범위 |
| [plugin-suitability-review.md](plugin-suitability-review.md) | S4 사전 조사: WorldGuard/WorldEdit 의존성·버전 근거, Multiverse-Core/Paper API 비교와 미실행 시험 계획 |
| [story-world-detailed-design.md](story-world-detailed-design.md) | S3 진행 ID·수명주기·DB·콘텐츠·보호/채집·복구의 제안 초안 |
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

- [S3 완료 조건 감사](s3-completion-audit.md): 영역별 충족/미충족/S4 이관/의도적 보류, 상태 변경 전 사용자 검토.
- 통합 후속 정책: [ADR-0010](decisions/0010-coop-information-and-hud.md), [ADR-0011](decisions/0011-votes-boss-return-and-gathering-reset.md), [ADR-0012](decisions/0012-progress-storage-world-lifecycle-and-content.md).

- G1~G4 후속: [ADR-0013](decisions/0013-vote-boss-and-gathering-state-boundaries.md)과 [ADR-0014](decisions/0014-worldguard-protection-engine.md), 최신 [S3 재감사](s3-completion-audit.md).
