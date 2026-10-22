# Hearth 주요 변경 이력

Git 커밋의 실제 변경을 기준으로 주요 항목만 기록합니다. 날짜는 커밋 기록의 Asia/Seoul(+09:00) 기준입니다. 사소한 편집과 확인할 수 없는 과거 작업은 나열하지 않습니다. 현재 상태는 [PROJECT_STATUS](PROJECT_STATUS.md), 이후 계획은 [ROADMAP](ROADMAP.md)을 참고합니다.

## 2026-10-23 — 기술 스택과 월드 운영 정책 확정

관련 커밋: 이 항목을 추가한 커밋 (`git log --oneline -- docs/CHANGELOG.md`로 확인).

- 사용자 확정 스택과 템플릿·콘텐츠·PostgreSQL 진행 데이터 분리, 월드 보호·채집 정책을 ADR-0002에 기록했습니다.
- README·아키텍처·영역 문서·AGENTS와 공식 현황·로드맵을 갱신했습니다. B안·AI 정보 격리·A안 확장 경계는 유지하고 과거 비교·가능성 검토에는 후속 결정 링크를 추가했습니다.
- 기술 결정과 호환성·구현·실행 검증을 구분했습니다. 경제 프로세스 분리, 인던 수명주기, AI 기술, 기존 월드 신규 지형 적용과 세부 게임 시스템은 미정으로 유지합니다.
- 검사 범위는 문서 링크·공백·정책 정합성·staging 범위와 기존 Gitleaks 보안 Hook입니다. 설치·서비스 실행·운영 환경 변경은 없습니다.

## 2026-10-23 — 승인된 작업의 자동 commit·push 정책

관련 커밋: 이 항목을 추가한 커밋 (`git log --oneline -- docs/CHANGELOG.md`로 확인).

- 사용자 승인 개발·문서 작업은 관련 검사와 문서 갱신 후 해당 작업만 staging하고 의미 있는 단위로 commit한 뒤 origin/main에 자동 push하도록 AGENTS.md를 변경했습니다.
- 기존 미커밋 변경과 다른 작업은 제외하며, 검사 실패·충돌·원격 이력 불일치·push 실패 시 중단·보고하도록 했습니다.
- force push, reset --hard, 기존 이력 변경, 브랜치 삭제와 기존 Docker·Zabbix·Cloudflare 운영 변경은 별도 승인 대상으로 유지했습니다.
- 검증 범위는 문서 링크, Git diff 공백 검사, 정책 요구사항 및 staging 범위 확인입니다. 게임 실행 시험은 없으며 로드맵 단계와 기술 선택은 변경하지 않았습니다.

## 2026-10-23 — 공식 문서 관리 체계

관련 커밋: `74e33ef` — `docs: establish official project status roadmap and changelog`.

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
