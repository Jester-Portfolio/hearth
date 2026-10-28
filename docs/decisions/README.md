# 설계 의사결정 기록

주요 기술이나 시스템 경계를 결정할 때 `0001-주제.md` 형식의 문서를 추가합니다. 스토리 운영 구조 B안과 후속 기술 스택·월드 정책은 승인되었습니다. S3 설계 완료 판정은 [최종 감사](../s3-completion-audit.md)를 따르며 전체 기능 구현·실행 검증은 미완료입니다. 이 목록의 상태는 **설계 승인 상태**이며, 현재 부분 구현·검증 여부는 [PROJECT_STATUS](../PROJECT_STATUS.md)를 따릅니다. 과거 ADR 본문의 당시 미실행 기록은 이력으로 보존합니다.

| 기록 | 상태 | 범위 |
| --- | --- | --- |
| [ADR-0001: 스토리 월드 B안 채택](0001-story-world-multiworld.md) | 승인·기술 미정 항목은 ADR-0002로 갱신 | 단일 서버 프로세스의 진행별 독립 월드, 공통 AI의 접근 격리, A안 확장 경계 |
| [ADR-0002: 기술 스택과 월드 운영 정책](0002-stack-and-world-policies.md) | 승인 | 기술 스택, 템플릿·콘텐츠·진행 분리, 보호·채집 정책 |
| [ADR-0003: 성능 우선 인벤토리 저장과 수동 장애 대응](0003-inventory-performance-and-recovery.md) | 승인 | 월드 상자·PostgreSQL 개인 인벤토리, 기본 안전성·비용 제한·수동 조정 |
| [ADR-0004: 공동체·퀘스트·장별 따라잡기와 스킵](0004-community-quests-and-chapter-skip.md) | 승인 | 공유 진행·개인 퀘스트·접속자 전원 조건·고정 분기·장별 스킵 |
| [ADR-0005: 전투 인던 구역별 스폰·소환 시점 난이도](0005-dungeon-spawning-and-difficulty.md) | 승인 | 내부/산정 바운더리 분리·시점별 인원 산정·난이도 고정·중복 억제 |
| [ADR-0006: 퀘스트·대화·컷신·전투 사망과 조건부 부활](0006-quests-dialogue-cutscenes-and-combat-revival.md) | 승인 | 퀘스트 운영·개인 시청 기록·관전·설정형 부활·전투 인벤토리 유지 |
| [ADR-0007: 필드·특수 인던과 보스전 운영·재도전](0007-dungeon-and-boss-operations.md) | 승인 | 유형별 난이도·집결/횟수 분리·명단 고정·복귀·전멸 대기·운영 투표 |
| [ADR-0008: 스토리·자유 탐험·지역 접근·거점 이동](0008-story-exploration-regions-and-fast-travel.md) | 승인 | 공동체 탐험 개방·접근 3종·개인 방문 해금/시설 이동 |
| [ADR-0009: 스마트폰 아이템·GUI·기본 메뉴·정보 접근](0009-smartphone-item-gui-and-information.md) | 승인 | 순정 인벤토리 보존·6메뉴·알림·채팅 AI·지도/미니맵 검증 경계 |
| [ADR-0010: 코옵 정정·지도·정보 메뉴·HUD](0010-coop-information-and-hud.md) | 승인 | 가입 기능 제외·지도 공개·일지/공동체/알림/설정·HUD |
| [ADR-0011: 투표·보스 복귀/관전·채집 리셋](0011-votes-boss-return-and-gathering-reset.md) | 승인·추천값 별도 | 채팅30초·정확복귀·혼합 상태·채집 계기/세대 후보 |
| [ADR-0012: 내부 식별·저장·월드/콘텐츠 보존](0012-progress-storage-world-lifecycle-and-content.md) | 승인·구현 후보 별도 | 소유권·영속 분기·월드 단일작업·버전 보존/백업 후보 |
| [ADR-0013: 투표/보스/채집 경계](0013-vote-boss-and-gathering-state-boundaries.md) | 승인·C1 온라인 시작 명단 후속 확정 | 입력마감/DB확정·보스일시정지·온라인 시작/자동부활·기존 참가자 복귀·진행별리셋 |
| [ADR-0014: WorldGuard 기본 보호 엔진](0014-worldguard-protection-engine.md) | 승인 | 양쪽 월드 보호·WorldEdit 의존성 확인·Hearth 전용 문맥/시험 행렬 |

각 기록에는 다음 내용을 포함합니다.

- 제목과 작성일
- 상태: 제안 / 승인 / 대체됨
- 해결하려는 문제와 요구사항
- 검토한 대안과 근거
- 결정 내용 또는 미정 항목
- 영향, 검증 방법과 필요한 후속 작업
- 운영 변경이 있다면 승인 범위와 롤백 계획

문서의 승인 상태가 실제 운영 작업 승인을 대신하지는 않습니다. 기존 기록을 대체하면 새 기록과 서로 연결합니다.
