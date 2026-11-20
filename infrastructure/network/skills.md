# 경제 backend 숙련도·공통 접근 제어

[AuraSkills 채택](../../docs/decisions/0031-auraskills-economy-integration.md)과 [구현/검증](../../docs/auraskills-admission-verification.md)을 따른다. `survival` 실행 모드에서만 사용하며 기본 비활성이다. 로비/스토리 설정에 적용하지 않는다. 운영 설치·재시작은 이 문서의 승인 범위가 아니다.

검토용 [skills.yml](skills.yml)의 블록을 새 경제 backend 설정에 병합하고 [skills-access.properties](../../src/main/resources/skills-access.properties)를 `plugins/Hearth/` 아래 지정한 이름으로 준비한다. `enabled: true`를 선택한 구성은 AuraSkills **2.4.0**과 유효한 정책 파일이 필요하다. 미설치·다른 버전·알 수 없는/비활성 숙련도·로드 실패는 입장을 열지 않는다. API는 compileOnly이고 Hearth JAR에 Aura 코드를 묶지 않는다. 기존 PG/월드/인벤토리는 이 설정으로 이전하거나 초기화하지 않는다.

정책의 여섯 역할은 `public_home`, `private_home`, `novice_resource`, `resource`, `nether`, `end`다. 현재 물리 바인딩은 기존 기본 거주→public_home, 야생→resource, 지옥→nether, 개인 월드→private_home이다. 초보 자원·엔드의 정책 항목을 적어도 새 월드가 생기지 않는다. 기존 3월드 UUID/PG 바인딩을 자동 확장하지 않으며 미등록 월드는 거부한다.

각 `<role>.rules`는 `none` 또는 쉼표로 구분한 규칙 ID다. 여러 규칙은 AND로 적용한다. 각 규칙은 다음 세 필드를 갖는다. 실제 요구 값은 아직 미정이며 예제 기본값은 모두 `none`이다.

| 필드 | 의미 |
| --- | --- |
| `rule.<id>.kind` | `specific`(정확히 하나), `average`, `sum`, `all` |
| `rule.<id>.skills` | 고정 분모/대상의 `namespace/key` 목록. 중복 불가, custom ID 가능 |
| `rule.<id>.minimum` | 결정한 0 이상 정수. all은 각각, sum/average는 집합 기준 |

평균은 목록의 고정 개수로 계산하며 미로드/없는 숙련도를 0이나 시작 레벨로 대체하지 않는다. 사용하지 않은 규칙·오타 키·빠진 역할·거주 역할의 레벨 규칙도 오류로 처리한다. 거주는 레벨 제한을 제외하지만 개인 ACL·월드 경계는 그대로 검사한다. `none`인 자원 역할도 활성 상태에서는 실제 숙련도 데이터 로드를 기다린다.

`/월드`에는 조건 미충족 사유를 표시하고, 메뉴/포탈 이동 준비 후·실제 teleport 때 최신 값과 개인 거주 세대를 재검사한다. 다른 플러그인의 Home/TPA도 표준 teleport 이벤트를 통과할 때 같은 제한을 받는다. **TPA와 30초 전투 제한은 [후속 전투 배치](combat-travel.md)로 연결했다. 새 Home 명령은 미구현이다.** 관리자/다른 플러그인에 자동 레벨 우회 권한을 부여하지 않는다.

활성 모드의 재접속 초기 위치는 공용 스폰이다. async 초기 배치에서 Bukkit 상태/DB를 기다리지 않고 안전 위치를 사용하므로, 이전 자원 위치나 개인 월드에 잠깐 노출되는 우회를 막는다. 이후 레벨 로드가 완료되면 메뉴로 다시 이동할 수 있다. 리스폰 목적지가 제한되면 공용 스폰으로 바꾼다. 입장 후 레벨/권한 변경은 즉시 행동 판정과 1초 주기 재확인으로 처리하며 이동이 불명확하면 연결을 종료해 다음 접속의 안전 위치를 사용한다.

정책 변경은 정상 저장 종료·설정 검토·다음 정상 기동으로 적용한다. hot reload는 제공하지 않는다. 일반 `/reload`로 오류를 덮거나 기존 소유/준비/HOLD 파일을 지우지 않는다. 활성 중 Aura가 비활성화되거나 콘텐츠 검증이 실패하면 서버 접근은 HOLD로 닫는다. 실제 로그인·포탈·리스폰·메뉴 클릭·동접 수용은 아직 별도 시험이 필요하다.
