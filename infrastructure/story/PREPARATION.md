# 새 스토리 진행 준비

[ADR-0012](../../docs/decisions/0012-progress-storage-world-lifecycle-and-content.md)의 새 진행 복제/원본 보존을 구현한다. [설정 예시](preparation.example.yml)와 [검증 범위](../../docs/story-preparation-verification.md)를 따른다. 이 예시는 운영 배포/모드 전환 승인이 아니며 서비스 중단·재시작이나 기존 Docker/SSH/방화벽·DB/월드는 변경하지 않았다.

`runtime-mode: story-prepare`는 게임 접속을 항상 닫고 콘솔의 준비 작업만 받는다. 정품/whitelist와 실제 접속자 부재를 요구하며 verification 모드와 같이 켜지 않는다. 게임 모드로 자동 바꾸거나 계정을 가입시키지 않는다. DB 비밀번호는 지정한 환경 변수에서만 읽는다.

## 검토할 입력

`plugins/Hearth/templates/<template>`에 정지한 제작 산출물의 `region`·필요한 `entities`/`poi`, 검토한 WorldGuard 정의 `worldguard-regions.yml`을 둔다. 원본 진행/실행 중인 월드를 입력 폴더로 연결하지 않는다. 공간 폴더는 Paper 표준의 평평한 파일 배치만 지원하며 별칭/심볼릭 링크·특수 파일·중첩 폴더를 거부한다. 공간 해시는 기존 TemplateStore의 상대 경로/파일 내용 SHA-256, 보호 해시는 해당 YAML 파일의 SHA-256이다.

같은 검토 콘텐츠 파일/해시와 작업별 새 진행 ID·`hearth_story` 차원 키·템플릿 버전/해시·보호 해시·안전한 진입 위치/필수 보호 구역을 지정한다. 실제 콘텐츠·좌표·보상/밸런스는 예제 값으로 정하지 않는다. 기존 진행 ID/차원 키/디렉터리·WorldGuard 자료는 채택하거나 덮어쓰지 않는다.

파일 수·전체 복제 바이트·디스크 여유를 명시한다. 현재 파일 수 상한 100,000개·공간/보호 합산 12GiB, 입력 보호 파일/콘텐츠 각각 1MiB와 작업 90초/안전한 Paper 단계 10초를 넘기지 않는다. 여유 공간은 실제 월드·WorldGuard·보고서 저장소에서 각각 확인한다. 이 제한은 운영 CPU/RAM·저장 용량 배정이나 수용량 판정을 대신하지 않는다.

## 실행과 완료 판정

콘솔 `hearthstoryadmin status`, `hearthstoryadmin prepare [설정한 작업 ID]`, `hearthstoryadmin drain`을 사용한다. ID를 생략하면 설정한 작업을 순서대로 묶어 준비한다. 같은 작업의 중복 요청은 한 결과를 공유하며 동시에 다른 복제 작업을 시작하지 않는다. 실패했던 작업을 자동 재시도하거나 산출물을 지우지 않는다. 다른 설정 작업은 독립적으로 진행할 수 있다.

새 차원에 새 UUID를 발급한 뒤 저장/언로드하고 공간/보호만 복제한다. 템플릿의 Paper UUID·월드 metadata·playerdata/개인 자료는 복제하지 않는다. 실제 차원을 다시 열어 UUID·보호/진입을 검증하고 저장한 뒤에만 PG에 새 진행/연결을 한 트랜잭션으로 등록한다. 새로운 개인 인벤토리·보상이나 가입 역할을 생성하지 않는다.

`prepared/<job>-<nonce>.yml`은 검토용 연결 자료다. 파일 존재만으로 완료가 아니다. 콘솔의 준비 완료와 `.hearth-story-provisioning` 부재·알려진 PG 등록 결과를 함께 확인한다. 검증된 `runs`/콘텐츠 정보를 [스토리 설정](config.example.yml)에 넣고 내부 계정 연결·기존 자원/운영 전환을 별도로 검토한다. 프로그램이 기존 서버 설정을 덮거나 서비스를 재시작하지 않는다.

등록 실패/commit 불명·원본/대상 변경·시간 초과는 실패한 새 자료와 표식을 보존한다. 정상 스토리는 준비 표식이 남은 차원을 거부한다. 표식을 삭제해 복구된 것으로 취급하지 않는다. `story-preparation.active`는 정상 drain과 DB 종료 확인 뒤에만 제거한다. HOLD 작업/진행 중 작업이 있으면 정상 drain을 거부한다. [백업/수동 대응](../backup/README.md)에 따라 원본과 등록 결과를 검토한다.
