# S4-9 — 월드 수명주기 관리자 검증

- 확인일: **2026-10-26 (Asia/Seoul)**. 시작 기준: `7cf167e63a83a90761dcb67e0d46e0b16ea1c585`의 깨끗한 main, fetch 후 origin/main과 일치.
- 최초 검사 판정: **S4-9 단위 완료 조건 통과**. S4-10 실제 저장 경로 전체와 S4-11 통합 완료를 뜻하지 않습니다. 후속 런타임 실패로 이번 작업은 미커밋 상태입니다.
- 근거: [단계 JSON](../verification/s4-9-result.json), [S4-10](s4-10-verification.md), [S4-11 실패 기록](s4-11-verification.md).

**최신 판정: S4-9의 이번 범위 PASS.** 기존 미커밋 작업을 보존한 후 새 격리 환경에서 단위/회귀 26개와 두 JVM의 명시한 내부 수명주기 검사를 통과했습니다. [재검증 JSON](../verification/s4-11-reverification-result.json)과 [S4-11 재검증 결과](s4-11-verification.md#수정본-재검증-결과)를 따릅니다. 위의 미커밋/후속 실패 설명과 마지막 문단은 최초 검사 당시 기록입니다.

## 구현과 사용 경계

[WorldLifecycle](../src/main/java/io/github/jesterportfolio/hearth/lifecycle/WorldLifecycle.java)은 검증된 한 `story_run_id`·물리 매핑의 소유 스레드 조정자입니다. 테스트 어댑터는 진행 ID별 하나의 인스턴스를 보관합니다. 물리 UUID/key로 DB 소유권을 선택하지 않으며 가입·탈퇴·파티장·실제 로그인/텔레포트는 구현하지 않았습니다. Multiverse-Core는 미채택입니다. [기존 적합성 검토](plugin-suitability-review.md)의 Paper 기본 API와 Hearth 조정자 경로를 확장하며 새 월드 관리 플러그인을 도입하지 않았습니다.

OFFLINE → LOADING → READY → ACTIVE → IDLE → QUIESCING → SAVING → UNLOADING → OFFLINE을 지원합니다. 준비 실패는 RECOVERY_REQUIRED, 저장/언로드 실패는 ERROR_HOLD입니다. 정상 상태로 자동 해제하는 API는 없습니다. 취소된 이전 세대의 실제 I/O 자체가 반드시 중단된다고 보장하지 않으며, 뒤 단계 실행과 현행 상태 적용을 거부합니다.

동일 요청은 같은 예약 future를 공유하고, 같은 진행의 다른 예약도 하나의 로드를 기다립니다. READY 예약과 실제 참가자를 구분하며 둘 다 없어야 IDLE입니다. 저장 중 새 요청은 미입장 예약으로 대기하며 기존 언로드와 새 준비가 끝나야 허가됩니다. READY/ACTIVE/IDLE의 변경 허용 질의와 QUIESCING 이후 변경 제한 경계를 제공합니다. 일반 서버의 아이템/플레이어 이벤트에는 연결하지 않았습니다.

IDLE 시간은 호출자가 명시하는 양수 Duration입니다. **5분은 ADR-0012 추천값이며 운영 확정값·이번 구현의 고정 기본값이 아닙니다.** 격리 시험은 명시적인 1초를 사용합니다. 세대와 타이머 토큰을 함께 검사하며 이전 타이머·중복 만료를 무시합니다. 시험 어댑터의 대기 예약·최근 완료 요청 캐시는 각각 최대 16개입니다. 이 수치는 운영 수용량이나 영구 중복 요청 원장이 아닙니다.

## 단위 시험

[WorldLifecycleTest](../src/test/java/io/github/jesterportfolio/hearth/lifecycle/WorldLifecycleTest.java)의 8개 JUnit 메서드가 통과했습니다. 실제 스레드 병렬성 대신 소유 스레드 큐와 완료 순서를 제어해 경쟁 입력을 재현했습니다.

| 시험 입력 | 확인한 결과 |
| --- | --- |
| 같은 진행의 중복/동시 예약, ACTIVE 추가 예약 | 단일 로드, 동일 요청 future 공유·중복 참가 반영 방지 |
| 참가자 잔존 또는 준비된 입장 예약 잔존 | IDLE 금지, 둘 다 없으면 IDLE |
| IDLE 재예약·취소된 이전 타이머·같은 만료 중복 실행 | 언로드 예약 취소, 최신 타이머만 1회 QUIESCING 진입 |
| SAVING 중 새 입장 | UNLOADING 및 다음 LOADING/준비 완료까지 허가 보류 |
| A 로드 실패, B ACTIVE | A는 RECOVERY_REQUIRED, 신규 요청 HELD, B 불변 |
| load/save 중 세대 무효화 후 이전 완료 | 현행 상태/후속 저장을 덮어쓰지 않음 |
| 저장 실패 또는 unload false | OFFLINE 오기록 없음, ERROR_HOLD |
| 예약 상한·동일 요청의 다른 소유자 | 명확한 거부, 추가 로드 없음 |

실제 Paper 첫 JVM에서는 A/B 로드 단일화·공유/개인/인벤토리 준비 후 ACTIVE·IDLE 취소 두 회까지 확인했습니다. 이후 시험 대기 루프 오류로 서버 틱이 정지했으므로 전체 타이머·실패 격리가 Paper에서도 통과했다고 해석하지 않습니다. 인증 플레이어 시험은 미실행이며 S4-3은 보류입니다.


## 수정본 재검증

기존 WorldLifecycle와 8개 단위 메서드는 다시 작성하지 않았습니다. 신규 환경의 첫 JVM에서 동일 요청 future 공유·A/B 각 1회 초기 로드·준비 완료 뒤 ACTIVE·IDLE 취소 2회·저장 중 입장 대기·OFFLINE 후 재준비를 확인했습니다. 두 번째 JVM에서도 중복 로드 단일화와 기존 상태를 복원했습니다. A의 실제 언로드 거부 후 ERROR_HOLD를 유지한 채 B는 정상 저장·언로드했습니다. 이전 load/save/timer 콜백의 상세 조합은 기존 단위 검사이며 Paper에서는 제어 future의 세대 변경 probe를 별도로 확인했습니다. 실제 플레이어 이벤트 검증은 아닙니다.
