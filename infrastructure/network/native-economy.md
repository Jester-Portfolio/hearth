# 일반 생존의 기존 경제 시스템

[ADR-0027](../../docs/decisions/0027-native-survival-economy-integration.md)에 따라 `runtime-mode: survival`의 거주·야생·지옥에서 같은 PG 잔액·공통 시장/생산·거래를 사용한다. 기본 연결은 비활성이며 기존 RPG realm/PG 슬롯을 자동 채택하지 않는다. 바닐라 인벤토리·엔더·경험치·체력·전투·사망 규칙을 유지한다.

재고·도착 예정량·현지 소비/생산 재료에 따라 출하량과 운영 모드를 자동 판단하는 [시장 에이전트 설정/관리](market-automation.md)를 추가할 수 있다. 기본은 외부 LLM 없는 경제 에이전트이며 기존 가격/거래 경계를 유지한다.

[단일 스폰/편의성 정책](../../docs/decisions/0029-single-spawn-shops.md)에 따라 새 경제 서버는 거주 스폰 한곳의 종류별 NPC와 공통 상점 명령어를 사용한다. 여러 마을/지역별 방문·스토리 진행을 요구하지 않는다.

## 플레이

- `/경제` (`/economy`): 잔액, 상점 진입, 공통 시장/시세·생산/제련, 본인 거래 기록.
- `/돈` (`/balance`): 본인 잔액. 새 계정은 0으로 시작하며 등록 일반 상품을 상인에게 판매해 화폐를 얻을 수 있다.
- `/상점` (`/shop`): 건축·광물·농산물·음식·기타 종류 선택 → 상품·수량·구매/판매 견적 → 최종 확정. 거주·야생·지옥 어디서든 열며 상인 ID/방문/거리 제한이 없다. 스폰의 종류별 NPC를 우클릭하면 해당 상점을 바로 연다. 시세가 바뀌면 거래하지 않고 새 견적을 확인한다. 이름/NBT가 붙은 상품은 일반 상품으로 팔지 않는다.
- `/거래 request <이름>` → 상대 `/거래 accept`: 같은 월드 6블록 이내의 개인 거래다. UI에서 상품/화폐 제안을 조정하고 양쪽이 같은 최종 제안을 확인한다. 좌/우클릭은 상품 ±1, Shift는 ±16이며 화폐 버튼은 ±1/±10이다. 제안 수정 시 양쪽 동의가 취소된다. ESC 후 제안은 3분 동안 유지하며 `/거래 show`·`cancel`, `item <상품 ID> <수량>`·`money <금액>`도 사용할 수 있다. 원래 상품을 보관함으로 옮기는 UI가 아니다.
- `/거래 receipt <요청 UUID>`: 본인이 참가한 확정 개인 거래 기록을 조회한다. 조회가 재지급/재요청을 뜻하지 않는다.

거래 확정 전 커서 아이템을 정리한다. 거래/계정 준비·개인 거래 제안을 마친 뒤 `/월드`, `/lobby`·`/hearthserver`를 사용한다. 거래 HOLD는 자동 재시도하지 않으며 요청 번호와 원본을 관리자에게 전달한다.

## 새 설정 준비

다음은 새 0600 YAML만 작성하며 서버/DB를 실행하거나 변경하지 않는다.

```sh
python3 scripts/survival_config.py --output /REVIEWED/NEW_GAME_CONFIG.yml \
  --with-economy --economy-realm REVIEWED_NEW_REALM_UUID \
  --db-host REVIEWED_HOST --db-port REVIEWED_PORT \
  --db-name REVIEWED_DATABASE --db-user REVIEWED_USER \
  --password-environment HEARTH_DB_PASSWORD
```

생존 group ID/월드 registry는 기존 준비 값과 일치시킨다. 새 세계 생성에는 별도 `--allow-create`, 새 경제 realm 최초 생성에는 `--allow-new-realm`이 필요하다. 기존 파일을 덮는 옵션은 없다. 이미 준비한 세계에 경제를 연결할 때는 검토한 `survival-economy` 절만 병합하고 기존 group/세계 설정을 유지한다. 기본 content 파일 이름은 `survival-economy.properties`다. [공개 예제](survival-economy.properties)를 같은 Hearth 데이터 폴더에 복사해 스폰 기준 NPC 오프셋·콘텐츠 수치/라벨을 실제 세계에 맞게 검토한다. 예제 좌표/수치가 운영 배치·밸런스 승인인 것은 아니다.

새 공개 예제는 `shop-layout=spawn`·시장 `spawn` 하나·5종 상점/15상품이다. 종류마다 NPC 하나를 거주 월드에 등록하며 상품은 중복/누락 없이 분류한다. `merchant.<ID>.x/y/z`는 거주 월드의 고정 입장점 기준 오프셋(가로/세로 ±32·높이 ±8)이다. 같은 위치 중복을 거부하며 지형은 자동 편집하지 않는다. 예제에는 마을·운송 경로·스토리 안내/사건이 없다.

콘텐츠 계산은 기존 EconomyContent/merchants/report 형식이다. `fixture-balance=0`, `offline=PAUSE`만 허용한다. 새 스폰 설정의 상인은 `merchant.<ID>.world=residence`만 허용한다. 기존 legacy 콘텐츠의 절대 좌표/월드 정의는 보존한다. 미생성 청크·막히거나 위험한 위치는 기동을 HOLD한다. 지형을 자동 편집하거나 기존 보호 구역을 덮지 않는다. 일반 보호 정본은 WorldGuard다.

DB 비밀번호는 지정한 환경 변수의 값으로만 주입한다. 네트워크 설정 생성기에는 이 YAML을 `--economy-config`로 전달하며 역할 이름은 economy를 유지한다. [로비/modern 인증](README.md)·[생존 월드 준비](survival.md)를 함께 따른다. 이 안내로 기존 서비스·Docker·포트·SSH/방화벽·운영 DB에 적용한 것은 아니다.

## 관리·저장 보류

서버 콘솔에서 다음을 사용한다. 플레이어에게 관리 기능을 개방하지 않는다.

- `heartheconomy status`: 생존 경제 상태/보류 수.
- `heartheconomy pending`: 요청 종류·참여 계정·요청 UUID 조회.
- `heartheconomy inspect <요청 UUID>`: 해당 보류 요청의 PG 확정 영수증 조회. 자동 표식 삭제·재지급·잔액 조정 없음.
- `heartheconomy event <요청 UUID> <등록 사건 ID>`: 기존 콘텐츠에 등록한 경제 사건을 멱등 예약.
- `hearthsurvivaladmin drain`: 진행 중 금융 worker가 정산된 뒤 접속/이동을 닫고 플레이어 종료·PG 종료·active 표식 정리. 완료 안내 후 정상 서버 종료를 진행한다.

`native-economy.properties`는 realm/group/world/content 연결 기록, `native-economy.active`는 정상 drain 확인, `native-economy-pending/*.pending`는 작은 요청 보류 표식이다. registry/native 개인 파일·월드·PG·이 기록을 보존한다. 비정상 종료/연결 불일치/보류 계정은 임의로 빈 상태를 만들거나 표식을 지우지 않는다. 기존 RPG PG realm과 생존 연결을 바꾸어 오류를 덮지 않는다.

SQL/commit 불명과 native 적용/저장 실패는 영수증·현재 PG 잔액/그림자 슬롯·native 원본/백업을 비교해 수동 조사한다. PG 그림자/영수증을 재접속 때 자동 적용하면 이미 처리한 거래를 중복 지급할 수 있다. 실제 잔액/아이템 보정·복원/기존 데이터 이전에는 별도 검토와 승인이 필요하다. 정상 drain 후에도 HOLD 요청 표식은 유지할 수 있고 해당 계정은 재입장을 보류한다.

새 단일 시장 예제로 기존 realm 콘텐츠를 덮지 않는다. 기존 경제 content hash/연결 기록이 다르면 기동을 거부하며 자동 이전하지 않는다. [단일 스폰 검증](../../docs/spawn-shops-verification.md)을 따른다.

실제 클라이언트 상점/거래·인벤토리/엔더·사망/재접속·로비 전환·동접 부하는 아직 수용하지 않았다. [격리 검증](../../docs/native-economy-verification.md)의 제어 Port는 실제 Player가 아니다.
