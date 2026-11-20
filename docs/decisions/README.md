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
| [ADR-0015: 경제 엔진·거래·스토리 분리](0015-economy-engine-and-transaction-boundaries.md) | 격리 개발 구조 채택·운영 정책 미정 | Java 순수 계산·경제 전용 스키마·최신 인벤토리/CAS 거래·단계적 운영 판단 |
| [ADR-0016: S4 개인 기능·복귀 정책](0016-s4-personal-features-and-recovery.md) | 승인 | 필수 시청/재시청·무료 시설 이동·개인 엔더·스마트폰 발급·재시작 HOLD/복귀 |
| [ADR-0017: 선택형 BetterHud 표시](0017-optional-betterhud-presentation.md) | 승인·개발/격리 범위 | 상대 앵커·단일 출력·개인 프레임/팩 응답·기본 표시 보존 |
| [ADR-0018: 로컬 AI 런타임·격리 예산](0018-local-ai-runtime-and-trial-budget.md) | 승인·구현/격리 실제 추론 범위 | 고정 Ollama/Qwen3 조합·정보/요청 한도·다운로드/CPU/RAM/GPU/시간·소유 자원 정리 |
| [ADR-0019: 접속별 AI 캐시·참고자료 계약](0019-session-ai-cache-and-reference-contract.md) | 승인된 후속 범위의 구현 결정 | 접속/자료 버전별 유한 캐시·백엔드/최신 권한 재확인·자료 번호와 표시 정합성 |
| [ADR-0020: 일반 지식과 게임 자료의 질의 경계](0020-general-knowledge-and-game-context-boundaries.md) | 승인된 S5 후속 범위의 구현 결정·과학 품질 보류 | 서버 선택 목적·일반 모드 정보 비전송·raw 역할 분리·수식 모순 응답 거부 |
| [ADR-0021: 공개 과학 노트와 자료 기반 질문](0021-science-reading-and-reference-questions.md) | 승인된 S5 후속 보완의 구현 결정 | 읽기 전용 과학 노트·선택 확인·노트별 대화/출처·계산기 연결 |
| [ADR-0022: 명시적 AI 준비와 요청 진입](0022-explicit-ai-preparation-and-admission.md) | 내부 검사·별도 승인한 콜드 시험 통과 | 콘솔 준비·초기 연산 확인·질의 6초 유지·준비 성공 표식/적재 상태 확인 |
| [ADR-0023: 필드 전투의 인던 제어 규칙 재사용](0023-field-combat-dungeon-rules.md) | 승인 | 고정 명단·사망/관전·조건부 부활·저장/복귀 재사용, 자동 재소환 금지 |
| [ADR-0024: 경제 전용 실행·PG 잔액·정상 계정 진입](0024-dedicated-economy-admission-and-currency.md) | 승인 | 새 계정 0/빈 인벤토리, 기존 데이터 보존·PAUSE, 별도 실행/저장 종료 |
| [ADR-0025: 로비·경제 멀티서버 네트워크 우선](0025-lobby-economy-network.md) | 사용자 네트워크 우선 결정·구현 선택 기록 | 스토리 개발 보류, Velocity/로비/경제·modern 인증·저장 후 단일 경로/실패 보존 |
| [ADR-0026: 일반 생존 경제 서버와 3월드 선택](0026-vanilla-survival-world-selection.md) | 사용자 일반 마크/월드 분리 결정·구현 선택 | 별도 로비 + 경제 Paper의 거주/야생/지옥, 인벤토리 UI·native 개인 데이터·기존 PG 보존 |
| [ADR-0027: 기존 경제 엔진의 일반 생존 연결](0027-native-survival-economy-integration.md) | 사용자 기존 경제 재사용 결정·구현 | 3월드 PG 화폐/상점·개인 거래·생산/수급, native 인벤토리 유지·그림자 CAS/HOLD |
| [ADR-0028: 시장 자동 운영·경제 에이전트](0028-autonomous-market-agents.md) | 사용자 자동 시장/경제 에이전트 선택·구현 | 부족량/비축/운송 중 수량·모드 안정화·E007 원자 저장/복원, LLM 기본 해제 |
| [ADR-0029: 단일 스폰·종류별 상점](0029-single-spawn-shops.md) | 사용자 편의성/단일 거점 결정·구현 | 여러 마을 제외·스폰 종류별 NPC 한 명·공통 `/상점`·원격 구매/판매·새 단일 시장 설정 |
| [ADR-0030: 공용/개인 거주·자원 월드](0030-residential-and-resource-worlds.md) | 사용자 요구 확정·시스템 구현/격리 검증·수치/운영 미수용 | 공용 건축 제한·크기별 영구 구매/확장·초대 공동 거주·자원 오버월드/지옥·단일 메뉴 |
| [ADR-0031: 경제 AuraSkills 통합](0031-auraskills-economy-integration.md) | 사용자 채택·기반/접근·전투/TPA 1차 구현 | 공개 API·YAML+PG·공통 접근/전투·수락형 TPA, 사망/보상·실제 플레이/운영 수용 별도 |
| [ADR-0032: 자체 설정형 인챈트](0032-native-enchantment-engine.md) | 사용자 자체 이식 요청·105종 구현/자동 검증 | MIT 출처·독립 효과 엔진·PDC/PG native 거래·정책 연동, 실제 수용 별도 |

각 기록에는 다음 내용을 포함합니다.

- 제목과 작성일
- 상태: 제안 / 승인 / 대체됨
- 해결하려는 문제와 요구사항
- 검토한 대안과 근거
- 결정 내용 또는 미정 항목
- 영향, 검증 방법과 필요한 후속 작업
- 운영 변경이 있다면 승인 범위와 롤백 계획

문서의 승인 상태가 실제 운영 작업 승인을 대신하지는 않습니다. 기존 기록을 대체하면 새 기록과 서로 연결합니다.
