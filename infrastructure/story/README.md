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

이 표식을 기존/불명 월드에 임의로 써서 채택하지 않는다. 원래 템플릿 해시는 진행 중 바뀐 지형 해시가 아니며, 준비 산출물과 소유권을 검토한 뒤 고정한다. 경제 소유 표식이 있는 차원은 거부하고 경제 실행도 스토리 소유 차원을 거부한다. 기존 진행을 새 템플릿으로 덮어쓰는 경로는 없다. 새 진행용 템플릿 복제/배포 자동화는 이 기반의 지원 범위가 아니다.

`content-file`은 1MiB 이하 단일 UTF-8 YAML이며 `content-sha256`과 일치해야 한다. 최상위 `quests`에는 기존 format 2 NPC/퀘스트 문서, `exploration`·`shared`·`rules`·`scenes`에는 각 기존 탐험/공유 스토리/완료 규칙/컷신 형식의 문서를 넣는다. 공개 합성 시험 파일을 운영 서사·보상 수치로 자동 복사하지 않는다. 현재 정상 실행 기반은 퀘스트/공유 진행·탐험/일지·보상/장 스킵·스마트폰/필수 AI 연결·지도/장면/개인 엔더를 연결한다. 제작/채집/호위/던전/전투·거점 이동·세계 효과의 정상 콘텐츠 묶음 연결은 후속이며, 해당 상호작용을 요구하는 미연결 콘텐츠는 초기화 보류로 처리한다.

PG의 `hearth_runtime`에는 연결/계정 표식만 추가하고 기존 `hearth_progress`의 값·revision·아이템을 초기화하지 않는다. 기존 행과 다른 월드·템플릿·콘텐츠 설정은 HOLD한다. 콘텐츠 변경은 저장된 퀘스트/분기/장면 ID와 진행 호환성을 별도로 검토해야 하며 현재 자동 이관/재확정 기능은 없다.

최초 접속에서 빈 41슬롯/커서와 내부 연결을 확인한 뒤 PG에 빈 개인 저장을 한 번만 만든다. 기존 행이 있으면 그대로 읽는다. 소유 불명 아이템·다른 진행 소유 표식·기존 개인 진행의 누락 인벤토리는 보류한다. 기존 서버 인벤토리의 자동 가져오기/덮어쓰기는 없다. PG 결과 후에도 같은 접속·원래 슬롯/커서/소유 표식인지 확인한다.

## 저장 종료

콘솔 `hearthstoryadmin status`·`hearthstoryadmin drain`을 사용한다. drain은 새 접속/행동을 닫고 개인 저장·기능/진행 장벽, WorldGuard/월드 저장·언로드, PG 종료를 순서대로 확인한다. `HEARTH_STORY_RUNTIME_DRAINED PASS` 뒤 승인된 서비스 종료를 수행한다. Bukkit 메인 스레드에서 DB를 기다리지 않는다.

본인 `story-runtime.active` 표식은 정상 drain 후에만 제거한다. 기존/교체/변조 표식이나 중단·commit 불명은 자동 복구하지 않는다. 표식을 지워 성공으로 취급하지 않고 [백업/수동 대응](../backup/README.md)에 따라 원본을 검토한다. hot reload·운영 전환/자원 배정·장기 부하와 실제 클라이언트는 별도 수용 대상이다. 격리용 `verify`는 명시한 새 소유 경로/JVM 표식과 실제 접속자 부재에서만 실행한다.
