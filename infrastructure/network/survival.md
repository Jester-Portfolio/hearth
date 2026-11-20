# 일반 생존 경제 backend: 3월드 선택

[ADR-0026](../../docs/decisions/0026-vanilla-survival-world-selection.md)의 새 실행 모드는 `survival`이다. 기존 로비·경제 역할 중 경제 Paper 안에 **거주·야생·지옥**을 둔다. 기존 RPG 경제/스토리 데이터 자동 이전은 하지 않는다.

[거주 시스템](housing.md)을 명시적으로 활성화하면 공통 3월드에 개인 거주 선택과 구매/확장·초대·기본 구획 보호를 추가한다. 아래는 공통 3월드의 기존 준비/이동 계약이며 개인 월드는 별도 소유 registry로 연결한다. 시스템/구매는 기본 비활성이고 실제 지형·수치는 추후 설정한다. 기존 세계/registry를 지우거나 임의로 새 key로 바꾸지 않는다.

## 플레이

`/월드` 또는 `/world` → 인벤토리 선택창 → 거주·야생·지옥 아이콘을 왼쪽 클릭한다. 도착점은 초기 준비에서 고정한 자연 지형의 안전 지점이며, 막히면 이동을 거부한다. 현재 월드는 표시한다. 탑승/사망/다른 이동 처리 중에는 이용할 수 없다. 아이템·엔더·경험치·체력은 같은 Player의 바닐라 상태를 유지한다. 사망 드롭·채집/제작은 기존 기본 게임을 따른다. 네더 포탈은 등록 월드의 공통 이동·안전 진입 경로를 사용하며, 미등록 엔드 포탈/월드는 현재 거부한다. 엔드/초보 자원의 물리 확장은 기존 UUID/경제 바인딩 이전을 포함한 후속 범위다.

별도 로비 복귀는 기존 `/lobby`·`/hearthserver` 메뉴를 사용한다. 개인 파일 저장 확인 뒤 프록시에 요청하며 저장/연결 결과 불명은 자동 재시도하지 않는다. 이동 대기 중 사망하거나 시간 초과/HOLD 안내가 나오면 재접속 후 상태를 확인한다. 이 경로는 아직 실제 정품 계정으로 수용하지 않았다.

## 새 설정 준비

다음 명령은 **새 파일만** 작성한다. 기존 서버/서비스를 실행하거나 바꾸지 않는다.

```sh
python3 scripts/survival_config.py --output /REVIEWED/NEW_GAME_CONFIG.yml
```

출력은 group UUID·`allow-create: false`·세 key·네트워크 비활성을 포함하는 0600 YAML이다. 초기 준비가 필요한 **새 전용 게임 데이터**를 검토한 경우 `--allow-create`로 새 설정 파일을 만들 수 있다. 기존 월드/계정/PG를 초기화하거나 변환하는 옵션이 아니다. `network_config.py --economy-config`는 이 YAML도 받아 기존 `economy` 역할에 modern forwarding 구성을 덧붙인다. [네트워크 설정/인증](README.md)을 함께 따른다. 직결 실행은 online-mode + whitelist, 프록시 실행은 검증된 modern 인증 + whitelist + loopback이 필요하다.

기본 월드 key는 거주 `minecraft:overworld`, 야생 `hearth_survival:wilderness`, 지옥 `minecraft:the_nether`다. 새 야생은 기본 NORMAL 생성기이며 `terrain: FLAT`과 정수 `seed`는 초기 준비 시 선택할 수 있다. Nether terrain은 NORMAL만 허용한다. 기존 기본 차원은 Paper server.properties에서 만든 원래 생성기/설정을 유지한다.

초기 준비 완료 후 `plugins/Hearth/survival-worlds.properties`의 group·key/UUID/seed·입장점이 기준이다. 설정의 `allow-create`를 false로 유지해도 다음 정상 기동에서 기존 세계를 로드한다. registry와 실제 월드를 함께 백업한다. registry·`survival-preparation.pending`·기존 `economy-world.properties`를 지우거나 UUID를 재확정해 오류를 덮지 않는다. 누락/불일치는 원본과 로그를 보존해 수동 조사한다. 기존 기본 차원의 playerdata가 있는 새 묶음, 미등록 custom 월드·다른 소유권의 계정도 자동 채택하지 않는다.

`hearthsurvivaladmin status`는 콘솔 상태를, `runtime_health.py --mode survival`은 3개 관리 월드의 집계를 확인한다. `hearthsurvivaladmin verify`는 소유 격리 시험 표식/경로가 있는 콘솔에서만 실행하며 운영 시험 명령이 아니다.

기존 PG 잔액/상점·개인 거래/생산 연결은 [일반 생존 경제 설정](native-economy.md)으로 명시적으로 활성화할 수 있다. 거주 소유/보호 시스템은 [거주 설정](housing.md)을 따르며 실제 지형/운영 수치·야생 리셋/주기·경제 밸런스는 아직 확정하지 않았다. 운영 설치·기존 데이터 이전·서버별 분산 인벤토리·외부 포트 전환도 적용하지 않았다.

선택형 숙련도·레벨 입장 조건은 [AuraSkills 공통 접근 설정](skills.md)을 따른다. 이를 켜면 자원 이동의 데이터 로드/레벨 조건·개인 ACL을 함께 확인하고 재접속을 공용 스폰에서 시작한다. 기존 3월드/경제 binding을 자동 확장하지 않는다.

[선택형 전투/TPA 설정](combat-travel.md)은 공통 이동 경로에 30초 제한과 수락형 요청을 추가한다. 켜진 구성은 태그 중 재접속과 재시작 보호 대기도 적용하며, 리스폰/내부 안전 복귀는 태그를 지우지 않는 예외다.
