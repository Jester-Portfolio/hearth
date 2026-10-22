# Hearth 주요 변경 이력

Git 커밋의 실제 변경을 기준으로 주요 항목만 기록합니다. 날짜는 커밋 기록의 Asia/Seoul(+09:00) 기준입니다. 사소한 편집과 확인할 수 없는 과거 작업은 나열하지 않습니다. 현재 상태는 [PROJECT_STATUS](PROJECT_STATUS.md), 이후 계획은 [ROADMAP](ROADMAP.md)을 참고합니다.

## 2026-10-23 — 공식 문서 관리 체계

관련 커밋: 이 항목을 추가한 커밋 (`git log -1 -- docs/CHANGELOG.md`로 확인). 자체 해시를 본문에 고정해 재커밋하지 않습니다. 아래 과거 기록은 확인된 기존 커밋을 명시합니다.

- PROJECT_STATUS·ROADMAP·CHANGELOG를 추가하고 공식 기록의 역할을 구분했습니다.
- ChatGPT는 기획·아키텍처·결과 검토, Codex는 구현·테스트·승인 범위의 인프라 작업을 담당하도록 문서 관리 규칙을 추가했습니다.
- README와 문서 안내에 링크를 추가하고 설계 완료·구현 완료·검증 상태를 구분했습니다.
- 문서 정리에 한정하며 서버 설치·실행이나 운영 변경은 포함하지 않습니다.

## 2026-10-22 — B안 운영 구조 채택

관련 커밋: `800eb4899fd3ede55d3f2478a7d9f225ccc158e8` — `docs: adopt story-world multiworld architecture in ADR-0001`.

- [ADR-0001](decisions/0001-story-world-multiworld.md)에 단일 서버 프로세스의 진행별 독립 월드 운영을 승인된 결정으로 기록했습니다.
- 싱글·코옵 진행, 상태 격리, 저장 후 유휴 언로드, 공통 AI의 진행별 정보 접근 격리와 A안 확장을 위한 배치 분리를 반영했습니다.
- 구조 비교·구현 가능성 검토 문서를 추가하고 관련 아키텍처·안내·스토리·서비스 문서를 갱신했습니다. 구현 검증 완료를 의미하지 않습니다.

## 2026-10-22 — 초기 설계와 저장소 구조

관련 커밋: `5575d118a0d55602833402fc5c3d724f6edad9a1` — `docs: establish initial Hearth project architecture`.

- 루트 README, 시스템 아키텍처, 문서·의사결정 안내와 Minecraft·서비스·인프라·스크립트 영역 문서를 추가했습니다.
- 현대 지식 활용 세계관, 스토리·경제 구분, 데이터 경계, AI 기능 범위와 미정 기술을 기록했습니다.

## 2026-10-22 — 프로젝트 지침 초기화

관련 커밋: `3a7c4ea37e9bdb27aadee338e404436bbc197dc2` — `chore: initialize Hearth project instructions`.

- AGENTS.md에 프로젝트·개발·환경·운영 안전·작업 흐름 지침을 추가했습니다.
