# 정식 스토리 실행 기반

기본값은 `runtime-mode: disabled`다. [설정 예시](config.example.yml)는 [ADR-0010](../../docs/decisions/0010-coop-information-and-hud.md)·[ADR-0012](../../docs/decisions/0012-progress-storage-world-lifecycle-and-content.md)의 내부 계정/진행 연결을 사용한다. 가입·초대·파티장이나 클라이언트의 진행 ID 선택 기능을 추가하지 않는다. 운영 서비스/볼륨/네트워크는 이 문서로 변경하지 않았다.

전용 Paper 26.2/Java 25 데이터 폴더와 WorldEdit/WorldGuard, 정품 인증/whitelist, PG, 고정 Ollama를 준비한다. DB 비밀번호는 지정 환경 변수로만 전달한다. AI는 필수이며 `mock`·비활성 대체를 허용하지 않는다. 기존 [명시적 AI 준비](../../docs/decisions/0022-explicit-ai-preparation-and-admission.md)를 유지하며 콘솔 `hearthai prepare`의 성공 후 질문한다. 서버 기동/접속이 모델을 자동 적재하거나 초기 연산을 실행하지 않는다. 일반 과학 품질 보류는 해제되지 않는다.

## 준비 자료와 보존

`runs`에 운영자가 검토한 진행 ID·차원 key/UUID·원래 템플릿 버전/해시·안전한 진입 위치·필수 WorldGuard 구역을 지정한다. `members`는 인증 계정 UUID의 내부 연결이다. 같은 계정을 여러 진행에 중복 지정하거나 같은 물리 차원을 두 진행에 재사용할 수 없다.

이 실행 기반은 **이미 준비한 차원**만 연결한다. Paper 레벨 폴더의 `dimensions/<namespace>/<key>`에 metadata와 진입 지역이 실제로 있어야 한다. 지형/UUID를 새로 만들어 누락을 숨기지 않는다. `.hearth-story-world`는 검토한 준비 산출물의 소유 표식이며 정확히 다음 UTF-8 두 줄과 마지막 개행을 가진다.

```text
<story_run_id>/<namespace:key>/<physical_uuid>
<template_version>/<original_template_sha256>
```

이 표식을 기존/불명 월드에 임의로 써서 채택하지 않는다. 원래 템플릿 해시는 진행 중 바뀐 지형 해시가 아니며, 준비 산출물과 소유권을 검토한 뒤 고정한다. 경제 소유 표식이 있는 차원은 거부하고 경제 실행도 스토리 소유 차원을 거부한다. 기존 진행을 새 템플릿으로 덮어쓰는 경로는 없다. 새 진행용 템플릿 복제·검증/등록은 별도의 [준비 모드](PREPARATION.md)를 사용한다. 준비 자료를 운영 설정에 자동 적용하거나 서비스를 전환하지 않는다.

`content-file`은 1MiB 이하 단일 UTF-8 YAML이며 `content-sha256`과 일치해야 한다. 최상위 `quests`에는 기존 format 2 NPC/퀘스트 문서, `exploration`·`shared`·`rules`·`scenes`에는 각 기존 탐험/공유 스토리/완료 규칙/컷신 형식의 문서를 넣는다. 공개 합성 시험 파일을 운영 서사·보상 수치로 자동 복사하지 않는다. 정상 실행은 퀘스트/공유 진행·탐험/일지·보상/장 스킵·스마트폰/필수 AI 연결·지도/장면/개인 엔더를 연결한다. 선택형 `modules`에는 기존 정의 형식의 `crafting`, `gathering`, `gathering-facilities`, `travel`, `escort`, `combat`, `field`, `dungeon`, `playthrough`, `preparation`, `course-route`와 채집 목표 퀘스트 ID 목록 `gathering-quests`를 넣는다. 목표에서 참조하는 모듈/시설·준비 안내의 의존성이 없거나 알 수 없는 모듈이면 보류한다. 정상 모드에 verification 플래그/JVM 표식을 켜서 우회하지 않는다. 기존 v1 콘텐츠의 개발 표식은 현재 정의 파서 계약으로 유지하며, 실제 실행 허용은 정상 모드·검토한 내용 해시·소유권으로 결정한다. 선택형 `world-effects`는 검토한 고정 블록 치환을 DB 확정 후 같은 진행에만 적용한다. 정의와 실패/보존 경계는 [효과·읽기 전용 복구](OPERATIONS.md)를 따른다.

시설 블록과 안전한 진입/도착 위치는 준비 월드에 실제로 있어야 한다. 전투는 종료 복귀를 포함한 기존 S4 정의를 검토하고 필드 조우는 같은 인던 규칙을 사용한다. 필드의 재시작 HOLD/자동 재소환 금지와 활성 전투·불명 이동 HOLD는 유지한다. 채집 초기 영역/템플릿·주기는 기존 제한 구역/원본 before 이미지 정책을 따른다. 채집 준비는 개인 복원 뒤 행동 허용 전에 비동기로 합치며, 실패를 빈 초기 상태로 대체하지 않는다. [연결/검증 범위](../../docs/story-modules-verification.md)를 따른다.

PG의 `hearth_runtime`에는 연결/계정 표식만 추가하고 기존 `hearth_progress`의 값·revision·아이템을 초기화하지 않는다. 기존 행과 다른 월드·템플릿·콘텐츠 설정은 HOLD한다. 콘텐츠 변경은 저장된 퀘스트/분기/장면 ID와 진행 호환성을 별도로 검토해야 하며 현재 자동 이관/재확정 기능은 없다.

최초 접속에서 빈 41슬롯/커서와 내부 연결을 확인한 뒤 PG에 빈 개인 저장을 한 번만 만든다. 기존 행이 있으면 그대로 읽는다. 소유 불명 아이템·다른 진행 소유 표식·기존 개인 진행의 누락 인벤토리는 보류한다. 기존 서버 인벤토리의 자동 가져오기/덮어쓰기는 없다. PG 결과 후에도 같은 접속·원래 슬롯/커서/소유 표식인지 확인한다.

## 저장 종료

콘솔 `hearthstoryadmin status`·`hearthstoryadmin drain`을 사용한다. drain은 새 접속/행동을 닫고 개인 저장·기능/진행 장벽, WorldGuard/월드 저장·언로드, PG 종료를 순서대로 확인한다. `HEARTH_STORY_RUNTIME_DRAINED PASS` 뒤 승인된 서비스 종료를 수행한다. Bukkit 메인 스레드에서 DB를 기다리지 않는다.

본인 `story-runtime.active` 표식은 정상 drain 후에만 제거한다. 기존/교체/변조 표식이나 중단·commit 불명은 자동 복구하지 않는다. 표식을 지워 성공으로 취급하지 않고 [백업/수동 대응](../backup/README.md)에 따라 원본을 검토한다. hot reload·운영 전환/자원 배정·장기 부하와 실제 클라이언트는 별도 수용 대상이다. 격리용 `verify`는 명시한 새 소유 경로/JVM 표식과 실제 접속자 부재에서만 실행한다.

정상 실행과 기존 실행 표식으로 접속이 차단된 HOLD에서도 `hearthrecovery`로 기존 저장의 읽기 전용 점검을 제공한다. 설정과 PG의 기존 소유권만 사용하며 표식 삭제·행 생성·HOLD 해제는 하지 않는다. [명령·제한](OPERATIONS.md)을 따른다.

공동체는 기동 시 선언한 진행의 실제 PG 명단 전체를 복원하고 현재 연결에서 온라인 상태를 합친다. 과거 구성원을 접속 허용 설정에서 제외해도 저장 명단을 지우지 않으며, 미가입 설정 계정을 멤버로 만들지 않는다. 이름은 whitelist 캐시/현재 접속 정보를 사용한다. `hearthstoryadmin members <run UUID> [page]`는 명단 캐시만 읽는다. [구현·검사·미검증](../../docs/story-community-verification.md)을 따른다.

정상 기동은 준비 시 등록한 PG 불변 연결을 확인만 한다. 연결 누락/불일치는 HOLD이며 기존 월드의 자동 채택·진행 재생성을 하지 않는다. [기동 보호/운영 점검](../../docs/runtime-operations-verification.md)과 [미적용 운영 구성](../deployment/README.md)을 따른다.
