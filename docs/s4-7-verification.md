# S4-7 — 논리 진행 전환·저장 순서 검증

- 확인일: 2026-10-26. 기준 커밋: `48277c8ef5c7e9be8c34e88f6db6c57772d946dc`.
- 상태: **이번 범위 통과**. [S4-6](s4-6-verification.md)의 사전 검사 뒤 구현했고 단위 검사 후 [S4-8](s4-8-verification.md)의 실제 S4-6 게이트 통과 이후에만 전환을 시험했습니다. 이 실행 순서는 사용자 추가 승인에 따릅니다. 실제 로그인·순간이동·가입/탈퇴 시스템이나 전체 S4 완료가 아닙니다.

## 구현과 불변식

[InventoryTransition](../src/main/java/io/github/jesterportfolio/hearth/inventory/InventoryTransition.java)은 서버 내부 한 플레이어의 컨텍스트를 담당합니다. 일반 이벤트/운영 서버에 자동 등록하지 않습니다. 같은 플레이어의 A→B만 허용하고 동일 진행/다른 플레이어 대상·동시 전환을 거부합니다. 신뢰하는 서버가 발급한 컨텍스트를 사용하며 Store가 발급자와 진행/개인 키를 검증합니다.

1. 현재 source 컨텍스트와 유효성을 확인하고 상태를 `TRANSITION`으로 바꿉니다.
2. source를 freeze해 새 메모리 변경을 거부합니다. 이미 진행 중인 저장을 비동기로 기다립니다.
3. 이전 캡처 이후 dirty가 남았다면 최신 스냅샷을 한 번 더 저장합니다. freeze 이후에는 새 변경이 없으므로 무한 저장 큐를 만들지 않습니다.
4. source의 dirty/in-flight/오류 상태와 revision 확인 뒤 target을 읽습니다. 이 조정자 호출은 모두 owner 스레드에서 순서화하며 메인 스레드에서 future를 기다리지 않습니다.
5. target의 봉투/NBT·개인 키/진행·revision을 검사하고 세대·현재 source를 재확인합니다. 새 세션 생성도 적용 전에 검증합니다.
6. 적용 콜백이 성공했을 때만 active를 target으로 바꾸고 `ACTIVE`로 완료합니다. 오류이면 `HOLD`, 원래 DB 데이터는 보존하고 성공으로 표시하지 않습니다.

세대/연결 무효화는 `invalidate()`로 전달합니다. 실제 세션 관리자 대신 내부 컨텍스트에서 시험했습니다. 결과 적용 executor는 기존 I/O가 정리될 때까지 살아 있어야 하며 모든 강제 종료/실행기 중단을 복구하는 기능은 아닙니다. 적용 중 일부 메모리가 바뀐 뒤 실패하는 경우 자동 롤백을 보장하지 않고 해당 상태를 보류합니다. DB 데이터는 삭제/빈 상태로 대체하지 않습니다.

[InventorySaveSession](../src/main/java/io/github/jesterportfolio/hearth/inventory/InventorySaveSession.java)의 `closeForLogout`는 새 변경/저장을 거부하고 기존 in-flight 및 최신 dirty 저장을 정리합니다. `logout()` 완료 뒤 연결/세대를 무효화하며 실패는 보류합니다. 전환 중 로그아웃 요청은 BUSY로 반환해 호출자가 완료/실패를 확인하도록 합니다. 실제 종료 이벤트 연결은 별도 범위입니다.

## 시험 결과

| 상황 | 검사 범위·기대 결과와 실제 결과 |
| --- | --- |
| source 저장 지연·기존 in-flight와 추가 변경 | 제어된 future 단위 시험에서 두 source 저장이 끝나기 전 target 읽기/적용 없음 |
| 정상 A→B | Paper+DB에서 수량59/revision5의 A 저장 후 B 수량15/revision0 적용. 중복 요청은 BUSY, 적용 한 번 |
| A 저장 실패 | 단위 및 Paper+DB trigger 23514에서 target 미적용·HOLD·DB source 보존 |
| B 누락 | 단위 및 실제 DB에서 MISSING_INVENTORY·빈 행/인벤토리 자동 생성 없음 |
| B 손상 | 단위 및 실제 DB의 손상 봉투에서 거부·target 미적용. 시험 도구만 원래 가상 payload를 복원 |
| 중복 전환 | 단위 및 Paper에서 BUSY·중복 적용 없음 |
| 이전 세대 응답 | 단위 및 실제 잠긴 DB target 읽기 중 세대 무효화. STALE_CONTEXT·적용 없음 |
| revision 충돌 | 전환 단위 시험에서 source CAS 충돌 시 target 읽기 없음. 인벤토리 자체 CAS는 S4-6 실DB 사전 검사 통과 |
| 적용 실패 | 단위 및 Paper의 의도적 적용 콜백 실패에서 APPLY_FAILED·HOLD·성공 표시 없음 |
| 종료 중 저장 | 단위 및 실제 DB 잠금 중 기존 저장/최신 dirty를 순서대로 정리. 신규 요청 CLOSED·최종 수량54/revision10 |
| 재접속 준비 | 새 Paper JVM이 마지막 A/B 개인 데이터를 복원. 실제 로그인이나 이전 실제 클라이언트 세션 시험은 아님 |

전환 단위 JUnit 6개 안에서 위 오류 조합을 검사했으며 전체 단위/회귀는 15개 통과입니다. 실제 이동, 경제 realm, 거래/보상과의 원자성은 구현하지 않았습니다. 일반 상자·월드 파일을 DB 트랜잭션에 포함하지 않습니다. source 저장 완료 후 전환이라는 ADR-0003 원칙은 유지합니다.

## 보존·미검증

오류 시 해당 source가 freeze/HOLD 상태를 유지합니다. 관리자·호출자는 저장/현재 세대/DB 상태를 확인하기 전 변경을 다시 허용하거나 새 관리자 인스턴스로 덮어쓰면 안 됩니다. 연결/commit 불명·임의 비활성화·부분 PlayerInventory 적용 실패·실제 종료 경합의 자동 복구는 미검증입니다. 로그·백업·관리자 수동 대응 원칙을 유지합니다.

변경 파일은 위 두 클래스·[단위 시험](../src/test/java/io/github/jesterportfolio/hearth/inventory/InventoryTransitionTest.java)·격리 시험 어댑터와 관련 기록입니다. [단계 JSON](../verification/s4-7-result.json)에 단위만 검사한 항목과 실제 통합 결과를 구분합니다. CPU/RAM·시간·프로세스 정리는 공통 [S4-8 기록](s4-8-verification.md)을 참고합니다. 기존 운영 환경·실제 플레이어 데이터·비공개 서사는 변경/접근하지 않았습니다.
