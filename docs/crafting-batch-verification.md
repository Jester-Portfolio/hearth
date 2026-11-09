# 작업대 일괄 제작·재료 제작법·의뢰 안내 연결

> 최신 결과: 이 배치의 필수 통합은 [누적 배치 최종 통합](pending-batch-review.md)에서 같은 최종 JAR로 통과했다. 아래는 환경 차단 당시의 최초 구현·검사 기록이며 현재 미완료 목록이 아니다.

확인일: 2026-11-09. 기준 `7dd0750` 이후 작업 트리. [기존 제작](crafting-verification.md)·[공개 실습](workshop-quest-verification.md)·[현장 채집 시설](gathering-facility-verification.md)을 재사용했다. **구현과 관련 단위 검사는 진행했으나 필수 Paper/격리 DB 통합은 환경 제한으로 미실행이다. 완료·배포 승인 상태가 아니다.** 상세 결과는 [JSON](../verification/crafting-batch-result.json)에 기록한다.

## 구현 범위

- 현장 작업대의 상세 화면에서 1·4·8회 제작을 선택한다. 순정 레시피의 1회 결과 개수와 제작 횟수를 구분해 총 결과 및 총 필요·보유·부족 재료를 표시한다. 새 제작법·운영 밸런스는 추가하지 않았다.
- 부족 재료 아이템을 누르면 같은 작업대에 등록된 순정 제작법으로 이동한다. 예를 들어 나무 곡괭이에서 막대기, 막대기에서 판자를 확인할 수 있다. 목표 제작으로 돌아가면 원래 레시피와 선택 횟수를 복원한다. 원목처럼 등록된 제작법이 없는 재료는 링크를 만들지 않는다. 자동 연쇄 제작은 실행하지 않는다.
- 제작 목록·상세에서 기존 다음 플레이 안내를 열어 본인의 최신 목표·추적·담당 NPC 보고 경로를 확인한다. 기존 조회기의 세션·개인/진행 공개 경계를 재사용한다.
- 선택 횟수 전체를 복사된 41슬롯에서 순차 계산한다. 재료 또는 공간이 중간에 부족해도 실제 인벤토리는 적용하지 않는다. 순정 결과·잔여물을 반환하며 보관 슬롯 0~35의 일반 재료만 사용한다.
- 기존 일회용 확인 요청에서 현재 인벤토리와 전체 결과를 다시 계산한 뒤 한 번 적용·dirty 처리한다. 클릭마다 DB 쓰기나 새 원장을 추가하지 않는다. 같은 의뢰 목표에 여러 도구를 제작해도 기존 제작 이벤트 하나로 처리하고, 대상 목표가 있을 때만 기존 source 저장→목표 저장 경계를 사용한다.
- 시설·거리·월드/세대·WorldGuard·생존/커서·세션 재검사, 클릭/드래그 제한, 실패 HOLD 및 원본 보존 경계는 기존 경로를 사용한다. 일반 서버 비활성 설정과 저장 형식·DB 스키마도 그대로다. 새 플러그인은 필요하지 않다.

## 실행 결과와 한계

| 검사 | 결과 |
| --- | --- |
| Gradle 관련 test/jar | **시작 실패**. `FileLockContentionHandler` 초기화에서 `Could not determine a usable wildcard IP for this machine` 발생. Gradle 빌드 성공으로 기록하지 않음 |
| 로컬 JDK 직접 컴파일 | 기존 JDK 25·의존성 캐시로 main 135개·test 87개 소스 컴파일 PASS. 별도 새 `/tmp/hearth-crafting-batch.*`에 출력. 기존 build/JAR을 새 빌드로 표시하지 않음 |
| 관련 JUnit | 제작 패키지, `CraftingQuestTest`, `PreparationGuideTest`, `InventorySaveTest`, `PlayerInteractionRulesTest`, `PlayerSessionsTest` **78개 PASS**. 총 재료·부족분/보유 불변·횟수 제한과 기존 중복/경합/실패 보존 회귀 포함 |
| 격리 실행 사전 검사 | 기존 `verify-s4-2.py`의 `port_free()`가 `PermissionError: [Errno 1] Operation not permitted`로 차단. localhost 소켓 생성 단계에서 중단해 Paper/PG를 기동하지 않음 |
| Git | 최초 `git fetch origin`이 `.git/FETCH_HEAD: Read-only file system`으로 실패. 원격 최신 관계 확인·staging·커밋·푸시 미실행 |

직접 JUnit 실행의 첫 시도는 임시 실행기에 없는 테스트 클래스 이름을 지정해 discovery에서 중단됐다. `InventorySaveTest`로 수정한 뒤 실행했으며 소스 테스트 실패와 구분한다. 원시 로그와 임시 실행기·classpath 목록은 JSON의 경로에 보존한다. 네트워크/파일시스템 제한을 변경하거나 우회하지 않았다.

## 남은 필수 통합과 재개 경로

기존 `stageLifecycleRuntime`과 필수 인자를 사용해 각각 새 사용자 소유 `/tmp/hearth-s4-11.*`에서 다음 범위만 실행한다. 기존 자원·EULA·시도 한도와 훅을 유지한다.

1. `--sessions --crafting`: 추가한 8회 순정 결과·재료 합계·상세 Inventory 객체·중간 재료/공간 부족 원본 보존·케이크 4개와 양동이 12개 반환·시설 한정 제작법 연결, 기존 저장 지연·CAS 실패·다른 진행 보존 검사.
2. `--sessions --quests --story-batch --crafting-quests`: 판자 8회→막대기 4회→도구 4회를 제작해 목표 전이/source 저장은 한 번인지 확인하고, 개인 보고·후속 자동 완료·격리 DB 충돌·월드 재입장을 함께 검사. 해당 신규 fixture의 원목 수량만 조정했다.

위 항목은 **추가한 검사 코드이며 이번에 PASS한 실행 결과가 아니다**. 저장/복원 계약은 불변이므로 새 JVM/DB 재시작 반복은 필요하지 않다. 필수 통합 통과 후 원격 기준을 다시 확인하고 승인 파일만 커밋/푸시한다. 실제 인증 플레이어의 제작·재료 링크·책 전환·인벤토리 적용·연속 클릭과 GUI 가독성은 사용자 요청 전 보류한다. 운영 서비스·기존 DB/월드·과거 증거는 변경하지 않았다.
