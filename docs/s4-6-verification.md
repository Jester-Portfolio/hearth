# S4-6 — 인벤토리 저장 기반 검증

- 확인일: 2026-10-26 (Asia/Seoul).
- 시작 기준: `48277c8ef5c7e9be8c34e88f6db6c57772d946dc`. main·작업 트리·스테이징이 깨끗했고 fetch 후 origin/main과 일치했습니다.
- 상태: **이번 범위 통과**. 단위/실DB 사전 검사와 후속 Paper 통합에서 ItemStack 왕복·실제 30초·dirty/오류를 확인했습니다. 실제 접속 플레이어 검증이나 전체 S4 완료는 아닙니다.
- 이번 기록은 [ADR-0003](decisions/0003-inventory-performance-and-recovery.md)·[ADR-0012](decisions/0012-progress-storage-world-lifecycle-and-content.md)의 성능·소유권·저장 순서를 유지합니다. 기존 [S4-4](s4-4-verification.md)·[S4-5](s4-5-verification.md) 증거와 V001 SQL은 변경하지 않았습니다.

## 구현 범위

- [InventoryPayload](../src/main/java/io/github/jesterportfolio/hearth/inventory/InventoryPayload.java): 41슬롯의 불변 바이트 봉투, 포맷 버전/슬롯 수·개별/전체 길이·SHA-256 검증. 손상/미지원 봉투는 거부하며 빈 데이터로 대체하지 않습니다. 체크섬은 손상 검사이며 인증 수단이 아닙니다. 구현 상한은 슬롯당 64KiB·봉투당 1MiB이며 초과 시 저장을 거부/보류합니다. 아이템 보유 수량의 게임 밸런스 정책이 아닙니다.
- [PaperInventoryCodec](../src/main/java/io/github/jesterportfolio/hearth/inventory/PaperInventoryCodec.java): 일반 36슬롯·방어구 4슬롯·보조 손 1슬롯만 명시적으로 취급합니다. 캡처·NBT 해석·적용은 메인 스레드에 제한했습니다. 실제 접속 플레이어에서 실행한 결과는 없습니다. Paper 내부의 실제 Bukkit 인벤토리 객체/ItemStack로 시험했습니다.
- [InventorySaveSession](../src/main/java/io/github/jesterportfolio/hearth/inventory/InventorySaveSession.java): memory_version·저장 완료 버전·DB revision 분리, 소유자별 단일 in-flight·중복 flush 합치기, 캡처 이후 변경의 dirty 유지, 실패 보류/최소 오류 기록을 구현했습니다. 연결/commit 불명이나 CAS 충돌은 자동 재시도하지 않습니다.
- [InventoryAutosave](../src/main/java/io/github/jesterportfolio/hearth/inventory/InventoryAutosave.java)·[Paper 어댑터](../src/main/java/io/github/jesterportfolio/hearth/inventory/PaperInventoryAutosave.java): 초기 30초와 설정 가능한 주기, 변경 없는 세션 생략, 메인 task의 캡처/비동기 저장 경로입니다. 일반 로그인/아이템 이벤트에는 연결하지 않았습니다.
- [InventoryStore](../src/main/java/io/github/jesterportfolio/hearth/persistence/InventoryStore.java)·기존 PostgresProgressStore: 기존 worker 2개·대기열 16개·Hikari 풀/CAS를 재사용합니다. [V002](../src/main/resources/db/migration/V002__story_inventory.sql)는 `(story_run_id,player_uuid)` PK/FK·bytea·revision을 추가하며 경제 realm/일반 상자는 구현하지 않습니다.
- SchemaMigration은 기존 V001 체크섬과 행을 보존하는 V001→V002 순차 트랜잭션으로 확장했습니다. 알 수 없는/변경된/불연속 버전을 거부하며 손상된 기존 테이블을 초기화하지 않습니다.

## 공식 API·기성 기능 검토

[Paper ItemStack API](https://jd.papermc.io/paper/26.2/org/bukkit/inventory/ItemStack.html)의 `serializeAsBytes`·`deserializeBytes`를 사용합니다. 아이템 메타데이터를 이름/수량으로 축약하는 자체 변환 대신 공식 NBT 경로를 선택했습니다. [PlayerInventory API](https://jd.papermc.io/paper/26.2/org/bukkit/inventory/PlayerInventory.html)에 따라 핫바/일반·방어구·보조 손을 명시적으로 구분합니다. 추가 body/saddle 슬롯, 엔더 상자, 열린 GUI, 커서, 선택 핫바 위치의 별도 영속화는 이번 범위가 아닙니다. Minecraft 버전 업그레이드 간 전 형식 호환성도 아직 시험하지 않았습니다.

[Multiverse-Inventories 공식 안내](https://mvplugins.org/inventories/)는 Core 의존성과 월드별 인벤토리 분리를 설명합니다. 현재 Core는 미채택이고 승인된 PostgreSQL·논리 진행 ID·저장 순서를 대체하는 적합성은 확인되지 않았습니다. 추가 플러그인 설치 대신 Paper 직렬화와 기존 저장 기반을 사용하는 최소 구현을 선택했습니다. 새 플러그인의 버전/성능/호환성을 검증하거나 채택한 것이 아닙니다.

## 사전 검사 결과

| 항목 | 범위·결과 |
| --- | --- |
| Gradle build/test/check | S4-6 사전 단위/회귀 9개 통과. S4-7 추가 후 최종 단위/회귀 총 15개 통과 |
| 봉투 손상·버전·길이·불변성 | 단위 시험 통과. 추가로 Paper에서 잘못된 NBT 거부 확인 |
| 미변경 생략·중복 flush·저장 중 추가 변경 | 제어된 저장 future의 단위 시험 통과 |
| 실패/stale 결과 | dirty·revision 보존 및 자동 재시도 억제 단위 시험 통과 |
| 초기 30초 | 시험 시계 단위 검사 및 실제 Paper **30,049ms** 주기 관측 통과 |
| V001→V002 | 새 PostgreSQL 18.3에서 기존 V001 행·체크섬 보존, V002 추가·재실행 통과 |
| 진행/개인 키 | 실제 DB에서 A/B·P1/P2 바이트 봉투 격리, FK·중복·이전 revision 거부 통과 |
| 쓰기 실패·새 연결 | 실제 DB trigger의 23514 실패·기존 값 보존, 풀 종료 후 새 연결 복원 통과 |
| ItemStack의 수량·메타데이터·빈 슬롯 | Paper에서 41슬롯·핫바·방어구·보조 손·빈 슬롯·수량·이름/설명/인챈트 왕복 통과 |

실DB 사전 시험은 `/tmp/hearth-s4-6.l5kt4i1l`의 새 사용자 소유 클러스터에서만 실행했습니다. 핵심 의존성 해시, 가용 RAM 6GiB·디스크 4GiB 사전 검사 후 nice +10으로 실행했고, PostgreSQL은 S4-5의 소규모 설정·loopback을 사용했습니다. 테스트 JVM 힙 256MiB/CPU 힌트 2개·90초 작업 제한, 전체 Gradle 명령 10초·JUnit 실DB 본문 2.501초였습니다. CPU/RSS는 측정하지 않았습니다. 종료 후 postmaster 종료·포트 반환을 확인했고 데이터는 보존했습니다. 운영 DB·기존 서비스에는 접근하지 않았습니다.

```bash
nice -n 10 env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-s4-1.RSHqyT/gradle-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --offline --console=plain build test check inventoryIntegrationTest -PinventoryWorkspace=/tmp/hearth-s4-6.l5kt4i1l
```

위 경로는 보존된 증거이므로 재실행하지 않습니다. DB 시험의 슬롯 바이트는 형식 봉투 시험 데이터이며 실제 Minecraft 아이템으로 시험했다고 해석하면 안 됩니다.

## 실제 Paper 통합 결과

[통합 기록](s4-8-verification.md)의 신규 `/tmp/hearth-s4-6.bxx056__`에서 첫 JVM의 S4-6 게이트를 통과한 뒤 S4-7 전환을 수행했습니다. 인벤토리는 실제 Bukkit의 45칸 시험 객체 중 명시한 41칸으로 검사했으며 Player·로그인 이벤트를 모사하지 않았습니다. `PlayerInventory` 전용 캡처/적용 경로는 컴파일했지만 인증 플레이어에서는 실행하지 않았습니다.

- 초기 수량64/revision0 상태에서 미변경 flush 후에도 revision0 유지, 수량63 dirty 변경은 30,049ms 주기에 저장돼 revision1이 됐습니다. 주기별 dirty 수집과 소유자별 비동기 CAS이며, 여러 플레이어를 하나의 JDBC batch/트랜잭션으로 묶은 구현은 아닙니다.
- 저장이 DB 잠금을 기다리는 동안 수량62→61로 추가 변경했습니다. 이전 완료가 saved_version만 갱신하고 새 dirty를 유지했으며 순차 저장 후 revision2/3과 수량62/61을 확인했습니다. 중복 flush는 같은 in-flight를 공유했습니다.
- trigger 쓰기 실패(23514)에서는 dirty와 revision3·이전 수량61을 유지했습니다. 확실한 거부가 제거된 것을 확인해 수동 해제 후 수량60/revision4를 저장했습니다. 연결/commit 불명·CAS 충돌은 이 해제 경로에 포함하지 않습니다.
- A의 P1 변경이 A의 P2·B의 P1에 섞이지 않았습니다. 별도 풀의 새 연결에서 수량60/revision4를 복원했습니다. 잘못된 진행/FK/중복 키·오래된 revision은 사전 실DB 검사로, 외부 발급 컨텍스트 거부는 통합 시험에서도 확인했습니다.
- 두 번째 Paper에서 A/B·P1/P2 슬롯/메타데이터·revision을 신규 생성 없이 복원했습니다. DB 연결 중단에서는 dirty·오류 보류를 유지했습니다.

`markDirty`는 검증된 호출자가 메모리 변경을 통지하는 인터페이스입니다. 전체 클릭/획득/드롭/컨테이너 이벤트 자동 추적은 구현하지 않았으며 일반 부팅에 활성화하지 않습니다. 실패 시 자동으로 계속 재전송하지 않고 해당 상태만 보류합니다. 연결/commit 불명이나 CAS 충돌은 관리자 확인·정상 재조회가 필요하며 빈 인벤토리로 초기화하지 않습니다.

## 순서 승인·남은 경계

처음에는 선행 단계의 실제 시험과 두 JVM 한도 사이의 순서를 질문했습니다. 사용자가 **단계별 구현·사전 검사 후 두 JVM에서 S4-6→7→8 실제 시험을 순차 수행**하도록 승인해 재개했습니다. S4-6 단위/실DB 사전 검사 뒤 S4-7 구현/단위 검사를 마쳤고, 실제 첫 JVM에서도 `HEARTH_S46_GATE PASS` 이후에만 전환을 허용했습니다.

수량/메타데이터는 가상 시험 데이터입니다. 다른 Minecraft 버전 간 NBT 변환, 악의적 모든 NBT 조합, 실제 PlayerInventory/추가 body·saddle 슬롯·엔더 상자/GUI/커서·실제 행동 검증은 미실행/범위 밖입니다. 부팅 시 자동 DB 연결·이벤트 활성화도 없습니다. [단계 JSON](../verification/s4-6-result.json)·[S4-8 증거](../verification/s4-8-result.json)에 검사·자원·정리·미실행을 구분합니다.
