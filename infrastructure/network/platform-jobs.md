# 예약 작업·관리자 작업센터 운영

[검증 범위](../../docs/platform-jobs-verification.md)와 [기존 플랫폼](platform.md)을 함께 사용한다. 기존 자원 교체를 예약하거나 같은 작업 이력으로 수동 실행하는 기능이다. 실제 주기는 미선정이며 기본은 비활성이다.

## 설정과 시계

기존 `platform.properties`에 아래 키를 사용할 수 있다. 기존 파일에 키가 없어도 같은 비활성 기본값을 적용한다.

```properties
jobs.enabled=false
jobs.resource.wilderness.interval-ticks=0
jobs.resource.nether.interval-ticks=0
jobs.resource.novice.interval-ticks=0
jobs.resource.end.interval-ticks=0
```

0은 해당 예약을 끈다. 양수는 **경제 논리 tick 간격**이며 Minecraft tick/실제 날짜 예약이 아니다. 경제 tick-millis와 함께 활성 시간 간격을 결정한다. 기존 offline PAUSE에 따라 서버 정지 중에는 진행하지 않는다. 실행이 늦어져도 완료/취소한 시점에서 한 주기를 기다리고 밀린 작업을 연속 실행하지 않는다. 기술 상한은 주기100,000,000 tick이다.

자동 예약에는 jobs.enabled와 resource-reset.enabled가 모두 필요하다. 수동 reset/jobs run은 명시적 관리자 요청이며 기존 resource-reset.enabled를 따른다. jobs.enabled는 자동 예약 스위치다. 기본/개인 거주는 대상에 넣을 수 없다. 초기화 주기/시드/기본 자산을 이번 예제로 운영 확정하지 않는다.

최초 활성 준비는 기존 설정 파일을 보존한다. 실행 중 예약 변경은 `config-stage platform.properties <파일.candidate>` → 검토 → `config-apply <UUID>` 경로를 쓴다. 같은 정본/GUI/기존 원본·후보/해시/PG 변경 기록을 사용한다. 일반 reload는 예약 정책 변경을 거부하고 안전 이전 경로를 안내한다. 미확정 작업이 있는 동안 구성 변경을 거부하므로 먼저 기록을 확인한다. 원본이나 저널을 지워 해결하지 않는다.

## 명령과 GUI

관리 권한은 기존 `hearth.platform.admin`이다.

```text
/플랫폼 admin
/플랫폼 jobs
/플랫폼 jobs list
/플랫폼 jobs run novice <seed>
/플랫폼 jobs inspect <작업 UUID>
/플랫폼 jobs reconcile <작업 UUID>
/플랫폼 jobs cancel <작업 UUID>
```

기존 `/플랫폼 reset <role> <seed>`도 같은 작업 엔진/이력으로 연결한다. `/플랫폼 admin` → 예약·작업센터에서 예약 예정값·실행/보류·최근 기록·고정 입력/상태를 확인한다. 진행 중에는 일반 강화/판매를 여는 조건과 별개로 관리자 상태 조회를 허용하지만 현재 접속/권한/화면/세대 확인은 유지한다. 모든 변경은 동일 API의 busy/상태 검사에 따른다. 전역 HOLD에서 인증/접근을 우회하는 관리자 접속 기능은 없다. 이 경우 기존 콘솔을 사용한다.

이력은 최근128개를 페이지로 조회하고 이전 기록은 UUID inspect로 조회한다. 전체 저장 상한4096개에 도달하면 새 실행을 거부하며 자동 삭제하지 않는다. status는 유한 요약만 표시하고 상세 입력을 전부 채팅에 출력하지 않는다.

## 상태·복구 순서

- RUNNING: 실행 의도를 PG에 확정한 상태. 요청 ID/종류/대상·고정 시드/입력을 바꾸지 않는다.
- SUCCEEDED: 확인된 효과가 완료됐다. 같은 작업을 다시 실행하지 않는다.
- HOLD: 재시작/효과 실패·불명 결과다. 자동 재실행하지 않으며 realm의 다른 작업도 잠시 멈춘다.
- CANCELLED: 효과 미시작 또는 명시적인 자원 중단을 확인했다. 기록은 보존한다.

실제 세계 효과는 기존 `resource_reset` 요청과 **같은 UUID**다. 작업과 세계의 단계가 다를 수 있으므로 다음 순서로 처리한다.

1. jobs inspect와 reset-list로 작업/세계 요청·고정 입력·상태를 조회한다.
2. 자원이 PREPARING이면 임의 재생성하지 않는다. 기존 reset-abort가 허용하는 원본 상태에서 명시적으로 중단한다. READY/로컬 세계 HOLD는 기존 reset-resume으로 기록된 신원을 확인한다. 불명 전역/native HOLD는 기존 복구 지침을 따른다.
3. 기록된 효과와 로컬 세계 제어가 일치하는 ACTIVE/RETIRED 결과는 jobs reconcile로 확인한다. 이 명령이 세계를 다시 만들거나 이동을 다시 실행하지 않는다.
4. 효과 기록이 전혀 없거나 ABORTED면 jobs cancel로 작업을 닫을 수 있다. 진행 중/불명 효과는 취소/완료 처리하지 않는다.

시드/입력 변경·stale revision·다른 realm 요청을 거부한다. 완료를 되돌려 RUNNING으로 만들거나 슬롯/아이템을 재지급하지 않는다. 완료/취소 후 다음 예약은 현재 논리 tick+설정 주기다. 전원 차단의 무손실 자동 복구/임의 HOLD 해제 도구가 아니다.

## Java 확장

신뢰하는 모듈은 onLoad에서 JobExtension을 Bukkit ServicesManager에 등록하고 `install(JobRuntime)`에서 고유 kind와 JobHandler를 등록한다. 등록은 초기화 전, 최대32종이다. 현재 제공하는 자동 예약은 resource_reset 네 역할뿐이며 다른 모듈은 공통 submit API를 사용한다.

prepare는 템플릿을 고정 입력으로 만든다. PG reserve 성공 전에는 효과를 실행하지 않는다. execute는 전달된 current lease와 Bukkit 주 스레드/자체 비동기 경계를 지켜야 한다. inspect는 효과를 재실행하지 않고 NOT_STARTED/PENDING/SUCCEEDED/CANCELLED 중 검증 가능한 결과만 반환한다. 실패/불명은 HOLD이며 임의 재시도하지 않는다. 같은 kind/대상의 영속 ID·입력 의미를 업데이트 때 유지한다. 외부 handler가 사라지면 기본 명령 실행기로 대체하지 않는다.

입력은 버전1·최대32필드/4096문자이며 비밀키/토큰을 넣지 않는다. 임의 명령/스크립트 실행 기능을 제공하지 않는다. 실제 새 handler의 게임 효과/권한·장애 계약은 해당 모듈 구현에서 검증해야 한다.

E013과 세계/메타데이터는 기존 전체 Paper+PG 일관 백업에 포함한다. 운영 예약 활성화·주기/보존/아카이브·서비스 변경은 별도 운영 검토 범위다.
