# 작업대 제작·시설·인벤토리 저장 연결

확인일: 2026-11-08. 기준 `f4f255e`. 기존 41슬롯·커서 정리·세션·dirty/30초/CAS를 재사용한다. 상세 실행 결과와 첫 실패는 [JSON](../verification/crafting-result.json)에 보존한다. 전체 S4는 진행 중이며 일반 서버에서는 비활성이다.

## 구현한 플레이 흐름

- 외부 [공개 콘텐츠](../src/main/resources/crafting-demo.yml)에 등록한 작업대 우클릭 → 제작 목록 → 재료/결과 확인 → 한 번 제작을 연결했다. 참나무 판자·막대기·작업대·나무 곡괭이의 순정 레시피를 참조한다. 블록을 자동 배치하거나 기존 콘텐츠를 덮어쓰지 않는다.
- 기존 GUI와 저장 코드를 재사용하면 충분하므로 외부 제작 플러그인을 도입하지 않았다. 계산은 Paper의 [getCraftingRecipe/craftItemResult](https://jd.papermc.io/paper/26.2/org/bukkit/Server.html), 잔여물은 [ItemCraftResult](https://jd.papermc.io/paper/26.2/org/bukkit/inventory/ItemCraftResult.html)를 사용한다. 실제 컴파일·실행 대상은 기존 Paper 26.2 build 134다.
- 미리보기는 인벤토리를 변경하지 않는다. 확인 요청은 일회용이며 같은 요청은 같은 결과를 공유한다. 다음 틱에서 세션·진행·물리 월드/세대·시설 블록·거리·WorldGuard 상호작용·공유 콘텐츠 접근·현재 인벤토리와 레시피 결과를 재검사한다. 목록에 없는 레시피는 선택할 수 없다.
- 순정 재료와 동일한 일반 보관 슬롯 0~35만 소모하고 결과와 잔여물을 같은 보관 범위에 넣는다. 이름/메타데이터가 다른 아이템, 방어구 36~39와 보조 손 40은 재료로 소모하지 않는다. 재료나 공간이 부족하면 적용 전에 거부한다.
- 적용·dirty 반영은 메인 스레드에서 수행하고 기존 비동기 저장에 맡긴다. 제작 클릭마다 DB 트랜잭션을 만들지 않는다. 이전 저장 완료가 이후 제작의 dirty를 지우지 않는다. 부분 적용 실패는 기존 세션 HOLD와 저장 lease를 보존하며, 적용 전 취소는 대기 작업만 정리한다.
- GUI 전체의 클릭/드래그는 취소하고 검증된 왼쪽 메뉴 클릭만 처리한다. 화면 열기·전환과 제작은 [InventoryClickEvent의 다음 틱 지침](https://jd.papermc.io/paper/26.2/org/bukkit/event/inventory/InventoryClickEvent.html)을 따른다. E키·기본 인벤토리와 저장 형식, V001~V009는 변경하지 않았다.

## 실제 실행한 검사

| 범위 | 결과 |
| --- | --- |
| 관련 단위·회귀 | 65개 PASS. 첫 일괄 build의 64개와 추가 경합 1개이며, 최종 수정 영향인 제작 확인 8개를 재검사했다. 세션·인벤토리·월드 수명주기 회귀 포함 |
| Paper/PG | 최종 한 JVM의 26개 확인 PASS. 실제 순정 레시피·54슬롯 목록 객체·양동이 3개 반환·재료/공간/메타데이터 보존·WorldGuard 시설 구역·DB 저장 검사 |
| 저장/격리 | 실제 DB 쓰기의 완료 응답을 제어해 저장 중 제작과 dirty 보존 확인. A/P1 최종 revision 2, A/P2·B/P1·B/P3 불변. 제어 세션의 종료/재연결에서 기존 payload 복원 |
| 실패/취소 | 실제 PG의 잘못된 revision 저장 거부·probe dirty/HOLD/기존 값 보존. 늦은 확인 요청과 취소 장벽 정리 확인. 부분 적용 실패는 단위 포트 주입이며 실제 Player 실패 시험이 아님 |
| 실행물/정리 | 최종 빌드 JAR과 설치/실행 JAR 해시 일치. 프로세스·포트 반환 및 DB 풀 정상 종료 PASS |

첫 묶음은 연속 제작의 완료 콜백보다 대기 표식 제거가 늦어 `CRAFT_BUSY`로 실패했다. 제거 후 결과를 전달하도록 수정하고 경합 시험을 추가했다. 첫 자동 정리 판정에는 DB 풀 종료 확인 누락도 남았으며, 해당 PID와 포트의 실제 반환을 별도로 확인했다. 두 번째 묶음은 PASS, 이후 정상 취소 장벽을 보완한 최종 세 번째 묶음도 PASS다. 각 묶음은 새 경로·Paper 1회·PG 1개이며 실패 증거를 덮어쓰지 않았다.

최종 실행은 33.829초, Paper 최대 표본 RSS 998.5MiB·CPU 59.25초였다. 세부 자원·시간은 JSON을 정본으로 삼는다. 기존 600초·힙 1536MiB·RSS 2.5GiB·가용 RAM 6GiB·CPU 2/nice +10 한도를 유지했다. TPS/MSPT·장기 부하·누수는 측정하지 않았다.

## 실행과 미검증 경계

기존 stageLifecycleRuntime으로 **새 사용자 소유** `/tmp/hearth-s4-11.*` 경로를 준비한 뒤 `scripts/verify-lifecycle.py`의 기존 필수 인자에 `--sessions --crafting --accept-eula`를 사용한다. 이 단독 한 JVM 모드가 명시적 설정 `crafting-verification-enabled`와 JVM 게이트 `hearth.verification.crafting`를 함께 켠다. 운영 DB/월드와 이전 증거 경로는 사용하지 않는다.

실제 인증 플레이어의 작업대 클릭, PlayerInventory 적용, shift-click/숫자키/드래그 우회, 거리·WorldGuard 이벤트와 GUI 사용성은 **미검증**이다. 자동 시험은 실제 Bukkit 객체와 제어 세션을 사용했으며 가짜 Player를 만들지 않았다. 저장/복원 계약을 변경하지 않아 새 JVM 반복은 생략했다. 순정 임시 제작칸·상자·화로·엔더 상자, 운영 제작 콘텐츠와 경제 레시피는 추가하지 않았다. 후속 배치는 기존 탐험·퀘스트·제작의 공개 플레이 흐름 연결과 미정 저장 범위 검토를 우선하고, 실제 클라이언트 시험은 사용자 요청 전 보류한다.
