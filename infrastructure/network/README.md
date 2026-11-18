# 로비·경제 네트워크

현재 개발 우선순위는 **Velocity 프록시 + 로비 Paper + 경제 Paper**다. 스토리 개발은 사용자 요청으로 보류한다. [ADR-0025](../../docs/decisions/0025-lobby-economy-network.md)의 고정 역할·정품 인증·저장/실패 보존 경계를 따른다. 기존 서버/DB/Docker/SSH/방화벽/터널은 이 작업으로 변경하지 않는다.

## 빌드·역할

Java25/기존 Gradle 환경에서 `gradle jar`로 같은 Hearth JAR을 만든다. Paper의 `plugin.yml`과 Velocity의 `velocity-plugin.json`을 함께 담고, 각 런타임은 자기 진입점만 로드한다. 프록시는 WorldGuard/PG/스토리·AI를 초기화하지 않는다. 로비와 경제 Paper에는 기존 고정 WorldEdit/WorldGuard가 필요하다. Velocity 4.3.1 build45 공식 JAR SHA-256은 `0844bcabdcdb4f5b875906fd4c3365bc1bb76918e42bc595ed88ad195258dd75`다. 새 버전으로 자동 갱신하지 않는다.

각 역할의 준비 데이터·경로·포트를 따로 둔다. 경제 realm은 한 경제 백엔드만 쓴다. 로비는 자체 월드 UUID와 이미 준비된 안전 입장/WorldGuard 보호 구역을 갖고, 기존 경제의 월드/슬롯을 로비 소유로 채택하지 않는다. 로비 구역은 BUILD=DENY로 검토하고 게임 보호 정본은 WorldGuard를 유지한다. 경제는 기존 정상 실행/콘텐츠/저장 설정을 그대로 검토한다. 기존 `economy-world.properties`의 경로가 새 위치와 다르면 자동 재연결하지 않으므로 복원/이전은 소유권을 별도로 확인한다.

## 설정 준비

`scripts/network_config.py`는 **새로 없는 출력 폴더**만 만든다. 기존 서버에 설정을 덮어쓰거나 서비스/프로세스를 실행하지 않는다. 검토한 정상 경제 YAML과 로비 위치/UUID를 명시한다. 아래 값은 실행 가능한 운영 값이 아닌 치환 양식이다.

```sh
python3 scripts/network_config.py \
  --output /REVIEWED/NEW_PRIVATE_NETWORK \
  --economy-config /REVIEWED/ECONOMY/plugins/Hearth/config.yml \
  --proxy-port REVIEWED_PROXY_PORT --lobby-port REVIEWED_LOBBY_PORT \
  --economy-port REVIEWED_ECONOMY_PORT --max-players REVIEWED_LIMIT \
  --lobby-world-uuid REVIEWED_UUID --lobby-world-key minecraft:overworld \
  --lobby-region REVIEWED_REGION \
  --lobby-x REVIEWED_X --lobby-y REVIEWED_Y --lobby-z REVIEWED_Z \
  --lobby-yaw REVIEWED_YAW --lobby-pitch REVIEWED_PITCH
```

출력은 루트 0700·파일0600이다. `proxy/velocity.toml`, `proxy/plugins/hearth-network/network.properties`, 역할별 Hearth YAML·server.properties **fragment**, `paper-global.fragment.yml`, 서로 다른 `secrets/forwarding.secret`/`secrets/relay.secret`, 해시 목록 `network-bundle.json`을 만든다. 소스 YAML의 경제 정의는 유지하고 기존 network 절만 교체한다. 스토리/verification=true 설정을 경제로 조용히 변환하지 않는다. 새 EULA 동의나 기본 월드/보호 구역 생성도 하지 않는다.

설치 검토에서는 기존 준비 서버에 필요한 역할별 설정을 **검토 후 병합**한다. Paper global의 다른 설정은 유지하고 modern forwarding 절만 병합한다. Spigot의 `settings.bungeecord=false`, Paper/PG의 loopback 접근과 정품 whitelist도 확인한다. 환경 변수 `HEARTH_VELOCITY_SECRET`은 forwarding.secret의 한 줄 값, `HEARTH_NETWORK_SECRET`은 relay.secret의 한 줄 값으로 프록시·두 백엔드에 각각 주입한다. DB 비밀번호는 기존 별도 환경 변수를 유지한다. 값/환경 덤프를 출력·Git 커밋하지 않는다. 공유 비밀을 알고 있는 서버/운영자는 신뢰 경계 안이며 기존 방화벽/SSH를 자동 설정하지 않는다.

이 설정의 Paper online-mode=false는 **프록시 정품 인증 + 실제 로드된 modern forwarding 검증**을 전제로 한다. 네트워크 flag만 켜거나 legacy/offline 전달로 인증을 대신하지 않는다. 잘못된 역할/비밀·공개 backend 바인딩·modern 비활성은 로비/경제를 HOLD한다. 프록시도 online mode, modern, 로비 기본 접속, 고정 서버/loopback 주소·비호환 채널/failover 비활성을 확인하고 실패하면 입장을 닫는다.

이전 [단일 직결 서버 운영 구성](../deployment/README.md)의 오프라인 감사/기동 manifest는 이 네트워크의 인증 구성을 허용하지 않는다. 해당 결과로 네트워크 배포 승인/검증을 대신하지 않는다. 네트워크 이미지·운영 기동/롤백 구성은 별도 운영 범위다.

## 플레이 경로와 보류

처음 접속은 로비다. `/hearthserver`의 경제 아이콘으로 이동을 요청한다. 경제에서 `/lobby` 또는 같은 메뉴로 돌아온다. 실제 GUI 아이템은 가상 선택창이며 로비에 소지품을 지급하지 않는다. 로비의 기존 보관/엔더/커서에 아이템이 있으면 원본을 지우지 않고 입장을 보류한다. 서버끼리 플레이어 파일/인벤토리/화폐를 복사하지 않는다.

경제에서 거래·채집/다른 이동을 먼저 끝내고 요청한다. 소스 세션 동결 → 기존 최신 PG 저장 완료 → 서명된 단일 요청 → 프록시의 같은 연결/목적 검사 순서다. 프록시의 `/server`나 다른 전환도 저장 확인된 단일 허가가 필요하다. 연결이 확실히 거절되면 기존 소스에서 계속할 수 있다. 저장/전송/연결 결과 불명·기한 초과는 자동 재시도 없이 보류한다. 이전 연결의 응답은 새 세션에 적용하지 않는다. 재접속 및 장애 대응은 기존 PG/월드/runtime 표식·수동 원본 확인 절차를 따른다.

로비 관측은 `runtime_health.py --mode lobby`로 읽을 수 있다. `controller_ready`는 컨트롤러 집계이며 실제 정품 접속·목적지 클릭/이동 성공이 아니다. 실제 클릭/서버 전환·코옵은 별도 요청 전 보류한다. 프록시/두 Paper의 수용량·운영 자원/가용성·자동 failover를 확정한 것은 아니다.

프록시 `/velocity reload`는 네트워크 입장/전환을 HOLD로 닫는다. 검토한 구성으로 정상 재시작해야 하며, reload로 온라인 인증·목적지/비밀 구성을 바꾼 뒤 기존 이동 권한을 재사용하지 않는다. 같은 경제 realm의 다중 백엔드/PG 분산 소유권 조정은 지원하지 않는다.
