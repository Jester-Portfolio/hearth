# 경제 전용 실행 준비

[ADR-0024](../../docs/decisions/0024-dedicated-economy-admission-and-currency.md)의 선택형 실행이다. 기본 `runtime-mode: disabled`는 유지한다. 이 문서/예시를 작성했다고 운영 서비스를 기동·중단하거나 기존 Docker/SSH/Zabbix·PG/월드를 변경한 것은 아니다.

## 구성

별도 경제 전용 Paper 데이터 폴더와 Java 25/Paper 26.2, 기존 WorldEdit/WorldGuard, 같은 Hearth JAR을 사용한다. AI/Ollama·스마트폰은 필요 없다. 정품 인증과 whitelist가 켜져 있어야 한다. 스토리 모드/격리 verification 설정과 함께 켜지 않는다. 플러그인 hot reload/활성 접속 중 모드 전환은 지원하지 않는다.

[설정 예시](config.example.yml)의 realm·기존 차원 key/UUID·진입 위치·WorldGuard 구역을 실제 준비 자료로 채운다. 예시 UUID/좌표는 배포 값이나 게임 밸런스가 아니다. 차원은 Paper의 `dimensions/<namespace>/<key>` 아래에 실제 metadata/진입 지역 파일이 있는 기존 차원이어야 하며 기본 `minecraft` 차원은 관리 대상으로 삼지 않는다. 다른 진행/경제 소유 표식이나 기존 매핑과 충돌하면 접속을 보류한다. 새 빈 월드·바닥·보호 구역을 자동 생성하지 않는다.

`plugins/Hearth/economy.properties`에는 기존 경제 콘텐츠 형식의 상품/가격·생산·지역/상인·운송/사건/안내를 작성한다. `offline=PAUSE`, `fixture-balance=0`이 필요하며 가격·생산량·주기는 실제 기획 값으로 별도 검토한다. 기존 realm의 내용 해시가 다르면 초기화하지 않고 HOLD한다. 예제 콘텐츠의 개발 잔액/시설 수치를 운영 기본값으로 복사하지 않는다.

DB 비밀번호는 `password-environment`로 지정한 환경 변수에서만 읽는다. 접속 설정과 실제 백업/토큰은 Git에 넣지 않는다. PG/서비스/볼륨 배치, 이미지/Java·Paper/플러그인 고정 버전 및 운영 CPU/RAM/GPU 배정은 운영 적용 계획에서 확정해야 한다. 이 설정은 Compose나 호스트 환경을 자동 구성하지 않는다.

## 정상 접속과 보존

정품/whitelist 접속 후 PG에 새 계정을 잔액 0·빈 41슬롯으로 저장하고 기존 세션 저장/이동을 마친 뒤 행동을 허용한다. 계정 생성 경합·재접속은 기존 행을 읽는다. PG에 값이 있는데 새 개발 자산으로 덮어쓰지 않는다.

첫 진입의 비어 있지 않은 미확인 순정 인벤토리나 다른 경제 소유 표식은 덮지 않고 보류한다. 기존 서버 자료를 채택/이전하려면 기존 PG·월드·플레이어 저장 범위와 소유권을 먼저 검토해야 한다. 이 실행 모드는 스토리 인벤토리 자동 이전 도구가 아니다. 같은 경제 월드의 일반 블록 조작도 WorldGuard 거부를 해제하지 않는다.

## 저장 종료·장애 대응

서버 콘솔 전용 명령은 `heartheconomy status`와 `heartheconomy drain`이다. drain은 새 접속/행동을 제한하고 진행 중 저장·거래를 정리한 뒤 접속 종료, WorldGuard/월드 저장·언로드와 PG 풀 종료를 비동기로 확인한다. **`HEARTH_ECONOMY_RUNTIME_DRAINED PASS`를 확인한 뒤** 승인된 운영 중단을 수행한다. DB를 기다리며 Bukkit 메인 스레드를 막지 않는다.

시작 시 `plugins/Hearth/economy-runtime.active`를 배타적으로 만든다. 정상 drain이 끝나야 본인 표식을 제거한다. 기존/변경된 표식이 있으면 자동 삭제·재기동 수용을 하지 않는다. drain 없는 종료는 원본과 표식을 보존하며 다음 실행이 HOLD한다. 이 경계는 모든 정전/파일·DB 무손실 보장이 아니다.

관리 진단은 같은 콘솔의 `pending <player_uuid>`, `history <player_uuid> [cursor]`, `inspect <player_uuid> <request_uuid>`, `recover <player_uuid> <request_uuid> <observed_revision>`으로 기존 영수증/오프라인 HOLD 임대 대응을 사용한다. recover는 DB 보정/재거래·잔액 변경이나 월드 HOLD 해제가 아니다. 온라인 상태·revision/근거가 불명하면 거부한다. 실행 표식을 지워 복구됐다고 취급하지 않는다. [백업·수동 대응](../backup/README.md)을 따른다.

실제 클라이언트·다인 상인 거래/장기 부하와 운영 배포는 별도 수용 대상이다. 자동 시험의 `heartheconomy verify`는 새 소유 격리 경로/JVM 표식·localhost PG와 실제 접속자 부재가 모두 맞을 때만 허용되며 일반 서버 검사/복구 명령이 아니다.
