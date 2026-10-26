# S4-10 — 저장·언로드·재로드 경계 검증

- 확인일: **2026-10-26 (Asia/Seoul)**. 기준은 [S4-9](s4-9-verification.md)와 같습니다.
- 최초 검사 판정: **구현·단위/컴파일 검사 통과, 실제 전체 저장·언로드·재로드 경로는 미완료**. [S4-11](s4-11-verification.md)의 첫 JVM 실패를 성공으로 대체하지 않습니다.
- 근거: [단계 JSON](../verification/s4-10-result.json).

**최신 판정: S4-10의 이번 범위 PASS.** 새 격리 환경에서 기존 인벤토리 저장 정리→공유 진행/revision→WG→월드 저장→방해 확인→실제 언로드/재로드와 대표 거부 경로를 확인했습니다. [재검증 JSON](../verification/s4-11-reverification-result.json)·[실행 결과](s4-11-verification.md#수정본-재검증-결과)가 최신 근거이며 아래 미완료 표와 수정본 미실행 설명은 최초 검사 당시 기록입니다.

## 재사용과 저장 순서

기존 V001/V002·PostgresProgressStore·InventorySaveSession의 dirty/CAS·단일 in-flight·closeForLogout을 재사용합니다. SQL, 기존 인벤토리 코드와 이전 JSON 증거는 변경하지 않았습니다. 일반 30초 비동기 저장·월드 파일 상자·성능 우선 원칙은 유지합니다. 이 단계는 일반 저장 주기를 바꾸거나 클릭별 SQL을 추가하지 않습니다.

[WorldPersistence](../src/main/java/io/github/jesterportfolio/hearth/lifecycle/WorldPersistence.java)는 다음 경계만 순서화합니다.

1. QUIESCING에서 해당 진행 인벤토리의 기존 저장과 최신 dirty를 정리합니다.
2. 진행 공유 상태·revision을 확인합니다.
3. 해당 WorldGuard RegionManager 저장 완료를 기다립니다.
4. 안전한 메인 시점에서 Paper 월드를 저장합니다.
5. 플레이어·Hearth가 추적하는 참조/저장·플러그인 청크 티켓·강제 로드 청크를 검사합니다.
6. `unloadWorld(..., false)`의 실제 boolean을 확인합니다. true일 때만 참조 해제 및 OFFLINE 처리를 허용합니다.

[PaperWorldBackend](../src/main/java/io/github/jesterportfolio/hearth/world/PaperWorldBackend.java)에 명시적 save/unload를 분리했습니다. 기존 saveAndUnload는 이를 호출하며 false를 예외로 반환합니다. 다른 플러그인의 티켓을 강제로 제거하지 않습니다. 이 검사는 모든 JVM/타 플러그인 참조가 사라졌다는 증명이나 메모리 누수 검사와 다릅니다.

[PaperLifecycleBoundary](../src/main/java/io/github/jesterportfolio/hearth/lifecycle/PaperLifecycleBoundary.java)는 기존 매핑의 실경로·진행/UUID 표식·공간 파일·Paper metadata SHA-256을 확인한 뒤 기존 월드만 로드합니다. WorldGuard 로드/플래그, DB 공유·개인·인벤토리 복원을 확인해야 준비 완료가 됩니다. 누락·불일치는 빈 월드 생성이나 템플릿 재복제로 처리하지 않습니다.

## 스레드 계약과 공식 근거

- Bukkit 월드 조작과 RegionManager 취득은 메인 스레드에서 수행합니다. `Bukkit.isTickingWorlds()`이면 **한 틱 이상 뒤**에 재확인합니다. [Paper Bukkit API](https://jd.papermc.io/paper/26.2/org/bukkit/Bukkit.html)의 로드/언로드 주의사항을 따릅니다.
- WorldGuard manager의 load/save는 별도 I/O worker에서 완료/예외를 기다립니다. [공식 RegionManager 문서](https://worldguard.enginehub.org/en/latest/developer/regions/managers/)는 해당 호출의 모든 스레드 사용과 블로킹 완료 계약을 명시합니다. [7.0.19 소스](https://github.com/EngineHub/WorldGuard/blob/7.0.19/worldguard-core/src/main/java/com/sk89q/worldguard/protection/managers/RegionManager.java)도 대조했습니다. Bukkit World 전체를 worker로 넘기지 않습니다.
- [Paper World API](https://jd.papermc.io/paper/26.2/org/bukkit/World.html)의 플레이어·플러그인 청크 티켓·강제 청크 조회를 사용합니다. 구역 파일을 PostgreSQL로 이관하지 않습니다.
- DB와 파일 I/O는 별도 worker, 후속 판정은 메인 결과 큐입니다. DB 응답을 메인 스레드에서 join/get으로 기다리지 않습니다. 파일 worker 2개·대기열 16개이며 기존 DB bounded worker/풀이 유지됩니다.

## 단위 시험과 실제 시험 구분

[WorldPersistenceTest](../src/test/java/io/github/jesterportfolio/hearth/lifecycle/WorldPersistenceTest.java)의 3개 JUnit 메서드가 통과했습니다.

| 범위 | 결과 |
| --- | --- |
| 매핑 → 로드 → 구역 → DB/개인 준비 → dirty 정리 → 공유 확인 → 구역 저장 → 월드 저장 → 방해 검사 → 언로드 → 참조 해제 | 제어 future/경계 모형에서 정확한 순서 통과 |
| dirty 저장 미완료 | 그 뒤 저장·언로드가 실행되지 않음 |
| 매핑/로드/구역/개인/인벤토리/공유/구역 저장/월드 저장/방해 검사 등 9개 경계 실패 | 각 오류 뒤 단계 중단·참조 보존 |
| unload false | 참조 보존. S4-9 상태 검사에서 ERROR_HOLD·OFFLINE 금지 |
| 저장 내부 단계 사이 세대 변경 | 다음 월드 작업 거부 |
| 실제 최초 준비의 기존 saveAndUnload·기존 월드 재로드/구역 로드 | 첫 JVM의 S4-2 prepare·새 관리자 초기 준비에서 확인 |
| 새 조정자의 최신 dirty → WG → 월드 저장 → 언로드/재로드 전체 | **미완료**, S4-11 대기 루프 오류로 중단 |
| 실제 DB 쓰기 실패·WorldUnloadEvent 취소, 티켓/누락/UUID 오류 probe | 계획은 작성했으나 해당 런타임 명령까지 도달하지 못해 **미실행** |

실제 디스크 고장·임의 plugin disable·commit 불명·강제 종료 무손실 복구는 검증하지 않았습니다. ERROR_HOLD/RECOVERY_REQUIRED는 수동 확인이 필요하며 관리자 복구를 자동화하지 않았습니다. 정상 종료 시 데이터 정리 경로도 이번 실패 실행에서는 성공하지 않았습니다.

## 발견한 오류와 수정 상태

시험 어댑터의 반환 타입 오류는 컴파일 전에 수정했습니다. 런타임 대기 루프가 `runTask`로 자신을 같은 틱에 다시 예약해 heartbeat를 붙잡았습니다. 이를 `runTaskLater(..., 1)`로 수정했고, 안전 시점 재검사에도 같은 명시적 다음 틱 처리를 적용했습니다. 수정 후 빌드/단위 검사만 다시 수행했습니다. **수정본 Paper 런타임 성공은 아직 확인하지 않았습니다.**


## 수정본 재검증

새 검증에서는 기존 in-flight 수량31 저장 뒤 최신 dirty 수량30까지 정리되어 revision2가 된 후 구역/월드 저장과 언로드가 진행됐습니다. 저장 중 입장 예약은 완료되지 않다가 실제 언로드·기존 월드 로드·WG/DB/인벤토리 준비가 끝난 후에만 허가됐습니다. 원래 UUID/key·GOLD_BLOCK·추가 EMERALD_BLOCK·WG flags가 유지됐고 B는 독립적이었습니다.

실DB trigger의 23514 거부는 별도 A/P2 저장 pipeline probe에서 dirty와 기존 수량5/revision0을 보존하고 뒤 저장/언로드를 실행하지 않았습니다. WG/world 저장 오류는 **API 경계의 결정적 예외 주입**이며 실제 파일시스템 고장은 아닙니다. A의 실제 WorldUnloadEvent 취소는 unload false·로드 상태·ERROR_HOLD·신규 예약 거부를 확인했습니다. 이를 새 조정자로 해제하지 않았고 B의 최신 저장/revision1·언로드는 정상 완료했습니다. 청크 티켓·누락 월드·UUID 불일치도 새 시험 자료에서 거부했습니다.

대기 반복의 runTaskLater(...,1) 수정은 유지했고, future가 이미 완료/실패했거나 플러그인이 닫혔으면 재예약하지 않도록 보완했습니다. 안전 시점 대기에 10초 future 타임아웃을 두고 owner 경로의 세대 검사를 유지했습니다. 실제 조건 대기에서는 틱이 증가했고 타임아웃 뒤 추가 예약이 없었습니다. isTickingWorlds=true를 강제로 지속시킨 분기나 실제 파일 I/O 무응답은 별도로 시험하지 않았습니다.
