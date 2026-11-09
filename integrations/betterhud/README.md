# Hearth 선택형 BetterHud

[ADR-0017](../../docs/decisions/0017-optional-betterhud-presentation.md)과 [검증 기록](../../docs/hud-display-verification.md)을 따른다. 현재 S4 개발 게이트/격리 검증용이며 일반 운영 활성화·재시작·팩 게시 절차를 승인하는 문서가 아니다.

## 준비할 설정

BetterHud Bukkit `2.1.0-SNAPSHOT-448`만 검증했다. [공식 아티팩트 고정](../../verification/betterhud-artifact.json)을 사용하고 `gradle verifyBetterHudApi`가 SHA-256을 확인한다. API는 compile-only이며 Hearth JAR에 플러그인을 포함하지 않는다. 기본 `betterhud-enabled`는 꺼져 있다. 켜려면 기존 S4 의존 게이트와 해당 플러그인이 필요하다.

BetterHud 설정에서 다음 값을 검토한다. 기존 사용자 설정/병합 목록을 통째로 덮어쓰지 않는다.

```yaml
merge-boss-bar: false
remove-default-hotbar: false
enable-self-host: false
metrics: false
version-check: false
pack-type: zip
merge-other-folders:
  - Hearth/betterhud-resourcepack
```

`merge-other-folders`의 기준은 **plugins 폴더**다. `../Hearth/...`로 쓰지 않는다. 새 격리 시험은 `default-hud: []`, `default-popup: []`, `default-compass: []`로 불필요한 기본 예제를 표시하지 않는다. 실제 서버의 다른 HUD를 임의로 해제하지 않는다.

Hearth는 `huds/layouts/texts/hearth-s4.yml`과 자체 픽셀, Hearth의 `betterhud-resourcepack` 병합 폴더를 준비한다. 이미 있는 파일은 보존하므로 기존 정의를 수정한 경우 새 템플릿과 직접 비교해야 한다. 초기 BetterHud 작업이 끝난 뒤 한 번 비동기로 재생성한다. 스토리/개인 데이터는 이 자산 폴더로 내보내지 않는다.

## 팩과 화면 확인

생성된 `BetterHud/build.zip`은 좌상단 퀘스트·우상단 원형 미니맵과 기존 스마트폰 자산을 포함한다. 실제 팩 위치는 BetterHud 설정을 따른다. `Hearth/betterhud-pack.json`은 모든 생성 자산 내용에서 도출한 `pack_id`를 기록한다.

별도 승인으로 팩을 제공할 때 이 ID와 실제 ZIP 해시를 사용한다. 예를 들어 Paper의 서버 팩 설정/API로 전송한 요청의 ID가 `pack_id`와 같아야 한다. 기존 콘텐츠가 바뀌면 새 ID/해시와 팩을 함께 적용한다. 로드 성공 응답 전에는 기본 표시를 사용한다. 다운로드 대기·거부/실패·다른 팩 ID는 BetterHud 표시를 열지 않는다. Hearth가 URL을 게시하거나 팩을 자동 강제 전송하지 않는다.

생성 결과는 다음처럼 읽기 전용으로 검사한다.

```bash
python3 scripts/check-betterhud-pack.py \
  --pack /tmp/새격리경로/server/plugins/BetterHud/build.zip \
  --betterhud-folder /tmp/새격리경로/server/plugins/BetterHud \
  --output /tmp/새로운팩검사.json
```

이 검사는 실제 클라이언트 렌더링·셰이더/GUI 배율·패널 가독성·플레이/클릭 성공이 아니다. 실제 Minecraft 클라이언트는 별도 요청 전 보류한다. 운영 적용은 설정/팩 백업과 기본 표시 복귀 계획을 포함해 별도 승인받는다.
