# 단일 스폰·종류별 공통 상점 검증

2026-11-18. 사용자가 경제 멀티서버는 스토리/RPG 마을 구조를 사용하지 않고 단일 스폰 거점과 종류별 NPC 한 명·공통 진입을 사용하도록 확정했다. [ADR-0029](decisions/0029-single-spawn-shops.md)·[설정/명령어](../infrastructure/network/native-economy.md)를 따른다.

## 변경

- 인자 없는 `/상점` (`/shop`) → 종류 선택 → 상품/수량·구매/판매 견적 → 최종 확인을 연결했다. 상인 ID 입력과 NPC 방문/거리 제한을 제거해 거주·야생·지옥에서 공통 상점을 이용한다. NPC 우클릭은 해당 종류를 바로 연다.
- 새 예제는 시장 하나, 건축/광물/농산물/음식/기타 5종·15상품과 스폰 기준 NPC 오프셋이다. 여러 마을·지역 간 운송·스토리 사건/안내는 넣지 않았다. 종류별 상품 중복/누락·같은 NPC 위치 중복·거주 외 배치·범위 밖 오프셋을 거부한다.
- `/경제`는 잔액/상점·시장/생산·본인 기록에 집중한다. 상점 종류/상품 페이지·이전/갱신을 제공하고 기술 ID를 입력하지 않는다. 새 단일 시장에는 지역 이동·안내 코스를 표시하지 않는다.
- 기존 세션/화면·커서·동시 작업·시세 revision, 최신 native 그림자 CAS와 PG 거래·HOLD/원본 보존은 유지한다. 기존 스토리/다지역 데이터와 엔진은 보존하고 새 콘텐츠로 자동 이전/초기화하지 않는다.

## 실행한 검사

[집계 JSON](../verification/spawn-shops-result.json)에 증거와 JAR 해시를 기록했다.

| 검사 | 결과 |
| --- | --- |
| Java 단위/회귀 | **35개 PASS**: SpawnShops 8·NativeTransactions 15·NativeMenuRules 2·EconomyEngine 10. 단일 시장/전체 상품 분류·중복/누락/잘못된 배치 거부·legacy 원본·200틱 생산/소비/재료 수량 보존, 기존 거래/GUI 방어와 가격 회귀 |
| Java25 컴파일·JAR·helper 준비 | PASS. 최종 JAR SHA-256 `dec4870a106ed0a01c2ff51ce19a4fb3c353d284bdc077da65c05c52680bfe0f` |
| 최종 복사 PG + 실제 Paper 두 JVM | **36개 확인 PASS / 39.268초**. 같은 스폰에 종류별 실제 Villager 정확히 한 명·5종 아이콘, 단일 시장/경로·스토리 사건 없음, native 코덱/제어 Port의 판매·구매/개인 거래, 실제 SQL 23514/commit 불명/세대 취소 원본 보존·다음 JVM의 잔액/재고/기록/HOLD 복원·기존 행 불변·정상 drain |
| Python/문서 | 변경 스크립트 AST 구문·diff·변경 Markdown UTF-8/관련 링크 검사. 외부 AI/새 모델 시험은 실행하지 않음 |

단위 검사는 초기 기능 코드 기준이며 이후 보완은 실제 Paper 시험용 바닥 준비·표시 문구/검증·시험 orchestration이다. 최종 코드에서 컴파일·영향받은 두 Paper/PG 경로를 확인했고 동일한 성공 단위 검사는 반복하지 않았다. PG 거래는 실제 쿼리지만 제어 Port의 저장/메모리 적용은 모형이며 실제 Player.saveData/아이템 클릭 시험이 아니다. 상점 인벤토리창을 실제 플레이어로 열거나 구매·판매하지 않았다.

## 실패 보완·증거

첫 시험은 기존 fixture의 스폰 주변 NPC 위치에 바닥이 없어 STARTUP_FAILED로 보류됐다. 복사 월드의 해당 바닥을 읽기 전용으로 확인한 뒤, 새 격리 복사본에서만 바닥/머리 공간을 준비하는 표식 제한 fixture를 추가했다. 제품의 일반 실행은 지형을 편집하지 않으며 실제 운영 스폰도 만들지 않는다. 첫 실패 당시 포트 재사용 검사가 OWNED_PORT_REMAINS를 기록했고, 후속 확인에서 모든 기록 PID 부재와 두 포트의 bind 성공을 별도 보존했다.

첫 시험에서 이미 통과한 E006 DB 신규 연결/CAS 행렬은 다시 실행하지 않았다. 복원용 DB helper를 재사용하는 두 번째 준비에서 `restore-realm.txt` 누락으로 Paper 이전에 중단됐고, 새 대상 realm ID를 먼저 기록하도록 보완했다. 세 번째 묶음에서 최종 두 JVM 경로를 통과했다. 실패 원본과 JAR은 덮어쓰지 않았다.

- 최초 실패: `/home/server/hearth-tmp/hearth-native-economy-run.uok1bp4p/native-economy-result.json`.
- helper 준비 실패: `/home/server/hearth-tmp/hearth-native-economy-run.ymw3wy50/native-economy-result.json`.
- 최종 PASS: `/home/server/hearth-tmp/hearth-native-economy-run.f97a70im/native-economy-result.json`.
- 단위 XML/빌드·원본/최종 JAR·후속 포트 확인: `/home/server/hearth-tmp/hearth-spawn-shops.7sx3k8it`.

고정 Paper26.2 build134/Java25·WG7.0.19/WE7.4.4, 승인된 비활성 세계/PG의 새 소유 복사본을 사용했다. CPU2/nice10·600초/작업550초·Paper heap1536MiB/RSS2560MiB·합산RSS6GiB·호스트 가용6GiB·최대Paper2/PG1·localhost 경계를 유지했다. 최종 cleanup_errors=[]이고 원본 설정/registry/경제 매핑/PG control 해시·기존 행 보존과 소유 프로세스/포트 종료를 확인했다. 이번 작업의 운영 서비스/기존 데이터 변경은 없다.

## 미검증·다음

실제 `/상점` 입력·종류/상품 클릭·원격 구매/판매·다인 경쟁과 재접속/월드 이동은 미검증이다. 운영 스폰의 지형/보호·시설 배치, 전체 바닐라 상품 확장·가격/공급량·장기 화폐/재고 균형과 부하/배포 수용은 남는다. 기존 realm에 단일 시장 예제를 덮어쓰지 않으며 콘텐츠 변경/이전은 별도 검토한다.
