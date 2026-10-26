# 최소 플레이어 세션 연동 — 격리 개발 모드 검증

확인일: 2026-10-26. 시작 main/HEAD/origin/main은 `ad8c601790dfe0a4fd136d19935952a116b9fd19`, 작업 트리·스테이징은 깨끗했습니다. 이 기록은 기존 S4-9~11 이후의 승인된 최소 세션 연동이며 **실제 인증 플레이어 검증이나 S4 전체 완료가 아닙니다**. 이전 상세 결과는 [S4-11](s4-11-verification.md), 이번 수치/시도별 증거는 [세션 JSON](../verification/player-session-result.json)을 따릅니다.

## 구현 범위와 재사용

[PlayerSessions](../src/main/java/io/github/jesterportfolio/hearth/session/PlayerSessions.java)은 기존 WorldLifecycle의 예약/참가·WorldPersistence 저장 장벽에 실제 연결의 수명을 연결합니다. 저장은 기존 InventorySaveSession·InventoryTransition.logout·PostgresProgressStore worker/풀/CAS를 사용합니다. 새 저장 테이블·병렬 저장 체계·계정/가입/탈퇴·파티 관리·운영 배포는 추가하지 않았습니다. 기성 보호는 기존 WorldGuard를 유지하며 [기존 플러그인 검토](plugin-suitability-review.md)의 Paper API 경로를 확장했습니다.

1. 신뢰하는 서버의 UUID–story_run_id 연결을 받습니다. 같은 UUID의 이전 저장 lease가 남아 있으면 새 복원은 기다립니다. 클라이언트가 진행 ID를 지정하는 경로는 없습니다.
2. 월드/구역/공유 진행 준비를 기다리고 기존 개인 진행·41슬롯 인벤토리를 읽습니다. 누락/손상은 거부하며 자동 생성하지 않습니다.
3. owner/main에서 검증·인벤토리 적용, 안전한 시점의 이동을 수행합니다. 이동 결과와 실제 월드 UUID를 확인한 후에만 참가를 확정합니다. 준비/이동/HOLD 동안 플레이·아이템 변경은 차단합니다.
4. 허용한 변경 이벤트는 다음 틱 캡처로 합치고, 30초 주기 직전에도 실제 상태를 비교합니다. 바뀐 경우만 dirty/memory_version을 갱신하며 DB는 기존 비동기 경로입니다. ItemStack 의미 비교로 NBT 표현 차이만 있는 저장을 생략합니다. DB CAS의 바이트 검증은 유지합니다.
5. 종료 시 main에서 마지막 불변 스냅샷을 확보합니다. 접속 권한은 끊어도 이전 저장 lease는 남겨 기존 in-flight와 최신 dirty를 정리합니다. 이후에만 새 세션이 복원할 수 있습니다.
6. 준비 중 종료는 예약을 취소하고 늦은 결과를 적용하지 않습니다. 이동 중 종료는 이미 발행한 이동 결과가 정리될 때까지 예약을 유지합니다. 중복 종료는 같은 future를 반환합니다.
7. 진행별 장벽은 모든 퇴장 저장/HOLD를 기다리며 활성 참가자는 언로드 방해 요소입니다. 다음 세대 준비만 기다리는, 아직 인벤토리를 보유하지 않은 예약과 기존 월드 참조/dirty를 구분합니다. 실패/HOLD를 새 연결로 지우지 않습니다.

## 실제 Paper 이벤트 어댑터

[PaperSessionAdapter](../src/main/java/io/github/jesterportfolio/hearth/session/PaperSessionAdapter.java)는 로그인·종료·입력·인벤토리 이벤트를 연결합니다. `verification-enabled`, `lifecycle-verification-enabled`, `session-verification-enabled`(기본 false), 전용 JVM 플래그·새 격리 경로/소유자/표식·시험 DB 식별을 모두 요구합니다. 일반 부팅에는 연결하지 않습니다.

초기 스폰은 main에서 만든 기존 시험 기본 월드의 안전 위치 스냅샷으로 지정합니다. 비동기 이벤트에서는 이벤트 값만 설정합니다. PlayerJoinEvent에서는 순간이동하지 않고 후속 안전한 main task에서 teleportAsync를 호출합니다. 완료 후의 판단도 owner로 전달합니다. [Paper Join 계약](https://jd.papermc.io/paper/26.2/org/bukkit/event/player/PlayerJoinEvent.html), [스폰 선택](https://jd.papermc.io/paper/26.2/io/papermc/paper/event/player/AsyncPlayerSpawnLocationEvent.html), [이동 API](https://jd.papermc.io/paper/26.2/org/bukkit/entity/Entity.html#teleportAsync(org.bukkit.Location))를 확인했습니다.

격리 fixture는 별도 콘솔 명령으로 whitelist의 프로필 UUID를 사용해 연결합니다. `session-fixture`는 시험용 개인 행/인벤토리를 명시적으로 준비하고, `session-link-existing`은 기존 데이터만 읽어 연결합니다. 로그인 이벤트에서 신규 행을 만들지 않습니다. 재시작 후 실제 시험 프로필은 후자로 다시 연결할 수 있습니다. fixture 누락·중복·DB 오류는 조용히 초기화하지 않습니다.

이번 실제 어댑터의 범위는 일반36·방어구4·보조 손1입니다. 커서/컨테이너/엔더 상자·GUI 작업을 저장에 섞지 않도록 격리 모드에서는 인벤토리 클릭/드래그·외부 GUI·시설 상호작용·플레이어 명령·임의 이동을 제한합니다. 비지원 전투/피해도 제한합니다. 허용된 블록 변경·드롭/줍기·소모·손 교체/장비 변경은 캡처와 종료 저장에 연결합니다. 정상 게임의 E키·건축·전투 정책을 바꾼 것이 아닙니다. 초기 대기/도착 발판도 시험용이며 정식 로비/복귀 정책이 아닙니다.

## 자동 시험과 실제 플레이어의 구분

새 단위 시험은 복원/이동 완료 전 입장 차단, 이전 in-flight+최종 저장 후 재접속, 준비/이동 중 종료, stale·중복 종료, 이동/손상 데이터 거부, 저장 실패 dirty/revision/HOLD, 여러 세션의 장벽, 저장 표현의 의미 동등성을 검사합니다. 기존 저장·수명주기 회귀를 함께 실행합니다.

[SessionVerification](../src/main/java/io/github/jesterportfolio/hearth/verification/SessionVerification.java)과 기존 [실행 도구](../scripts/verify-lifecycle.py)의 `--sessions`는 실제 Paper·PostgreSQL·Bukkit Inventory/ItemStack을 사용하지만 연결/이동은 제어 포트입니다. 가짜 Player나 합성 로그인 이벤트는 만들지 않았습니다. 실제 인증·PlayerInventory 캡처/적용·클라이언트 순간이동·GUI 차단·이벤트 누락 여부는 미실행입니다.

첫 JVM은 명시적 fixture A/P1·A/P2·B/P3를 준비합니다. 실제 30초 경로에서 A/P1 수량31/revision1, 미변경 A/P2 수량5/revision0·B/P3 수량7/revision0을 확인합니다. SQL 잠금 아래 종료/재접속과 다중 장벽을 확인하고 최종 A/P1 수량29/revision3·A/P2 수량4/revision1을 저장합니다. 준비/이동 중 종료·늦은 결과 거부 후 정상 저장·언로드합니다.

두 번째 JVM은 같은 DB/매핑에서 기존 데이터를 읽어 복원하고 이동 실패와 실제 SQL 23514 거부를 주입합니다. A/P1의 최신 dirty 수량28·DB 기존 수량29/revision3과 HOLD를 보존하고 재접속을 거부합니다. A/P2 이동 실패도 기존 수량4/revision1을 보존합니다. A는 ERROR_HOLD, B는 정상 저장·언로드합니다. 이 명시적 실패 fixture의 미저장 HOLD는 종료 직전까지 유지한 것이며 저장 성공/자동 복구로 세지 않습니다. 정상 B 정리는 별도로 확인한 뒤 예상된 두 HOLD만 분리해 풀/worker를 종료합니다.

## 실제 클라이언트 준비

[S4-3 수동 도구](s4-3-verification.md)는 최초 준비와 첫 수동 진입을 같은 JVM으로 합쳐 재시작 포함 최대 두 회·기존 240초를 유지하도록 수정했습니다. 최종 자동 묶음은 이 준비 경로의 READY까지 검증하며 실제 행동 PASS로 표시하지 않습니다. 기존 두 수동 세션의 TTY/클라이언트 조작 전체는 미실행입니다.

현재 인증 클라이언트는 확인되지 않았습니다. 기존 SSH 연결 표시와 ssh 실행 파일은 있으나 sshd 유효 설정은 이 사용자에게 읽기 불가이므로 로컬 포워딩 허용을 단정하지 않았습니다. 사용자 PC에서 기존 허용 SSH 대상으로 다음 형태를 사용할 수 있는지 확인해야 합니다. SSH/방화벽/Cloudflare 설정은 변경하지 않았습니다.

```sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:25586:127.0.0.1:25585 ROCKY_SSH_ALIAS
```

사용자는 정상 인증된 Java Edition 26.2 프로필 이름과 기존 접속 경로만 준비하면 됩니다. 비밀번호/토큰은 요청하지 않습니다. 보호 시험에서는 지정 구역 설치/파괴·bypass·재시작을, 세션 시험에서는 기존 인벤토리 복원·허용 변경·종료 후 즉시 재접속을 수행합니다. [OpenSSH 로컬 전달](https://man.openbsd.org/ssh#TCP_FORWARDING)은 클라이언트 쪽 후보이며 실효 허용·게임 접속은 아직 미검증입니다. 사용자의 준비를 기다리는 제한시간 서버는 기동하지 않습니다.

## 안전·실패 이력·미검증

자동 통합은 최초 포함 최대3묶음, 매번 새 사용자 소유 경로·Paper 순차 최대2회/PG1개·600초(작업550+정리50)·Xmx1536MiB·RSS2.5GiB 중단·가용 RAM6GiB·디스크4GiB·ActiveProcessorCount2/nice+10·localhost를 적용합니다. 이전 시험 증거와 운영 환경·실제 월드·비공개 스토리는 변경하지 않았습니다.

첫 묶음 `/tmp/hearth-s4-11.6ds3_nh0`은 미변경 인벤토리 판정 실패로 중단했습니다. NBT를 다시 직렬화한 바이트 표현이 달라지는 경로를 확인해 ItemStack 의미 동등성으로 수정했습니다. 원본 결과는 FAIL이며 정상 drain 확인은 없었습니다. Paper/DB exit0 및 별도 소유 PID/그룹·25585/34769 포트 반환을 확인했습니다. 증거를 보존한 뒤 새 경로에서 재시험했습니다. 두 번째 묶음 `/tmp/hearth-s4-11.8lw9y3zl`은 통과했고 `NBT byte_equal=false`·의미 동등성/미변경 revision0을 확인했습니다. 최종 묶음과 자원 수치는 JSON을 따릅니다.

실제 클라이언트 전 행동, 엔더 상자/커서/GUI·추가 슬롯, 실사용 코옵 동시 접속, 재구성/포털·타 플러그인과의 전체 충돌, 임의 disable·commit 불명·강제 종료/장기 부하/누수·DB 프로세스 재시작·운영 배포는 미검증입니다. 제어된 정상 종료는 저장 장벽이 끝난 후 stop하는 경로이며 임의 종료의 무손실 보장은 없습니다. S4-3 실제 플레이어 보류와 S4 전체 진행 중을 유지합니다.


## 최종 결과

두 번째 통과 후 기존 fixture 읽기 재연결과 첫 JVM의 prepare-player/READY 경로를 연결해 세 번째 묶음 `/tmp/hearth-s4-11.uoxquuud`에서도 통과했습니다. 전체 **80.912초**, Paper/DB 모두 exit0·정상 drain/worker 종료·프로세스 그룹/포트 반환을 확인했습니다. 아래는 최종 묶음의 표본입니다.

| 대상 | 시작 완료 | 최대 RSS | 누적 CPU | 종료 소요 |
| --- | --- | --- | --- | --- |
| 첫 Paper | 14.137초 | 1,188.47MiB | 91.13초 | 3.588초 |
| 두 번째 Paper | 8.836초 | 941.50MiB | 30.59초 | 3.511초 |
| DB 보조 JVM | 준비 표식 확인 | 216.05MiB | 2.33초 | 0.107초 |

최소 가용 RAM 약11.230GiB이며 PostgreSQL 전체 프로세스 사용량은 별도로 측정하지 않았습니다. CPU는 표본 user+system 누적이며 TPS/MSPT·장기 성능 통과를 뜻하지 않습니다. 첫 실패 묶음은57.286초, 두 번째 통과 묶음은79.587초였으며 각 묶음의 한도는 확대하지 않았습니다.

마지막 소스 검토에서 DB 응답 대기 중 **월드 세대 자체가 무효화된 경우**도 인벤토리 적용 전에 거부하도록 순수 세션 조정자의 방어를 추가했습니다. 이 분기는 추가 단위 시험으로 확인했고, 기존 세션 정체성/이동 완료/참가 commit의 세대 방어도 유지합니다. 통합 묶음은 이미3회 사용했으므로 네 번째 서버 실행은 하지 않았습니다. **세 번째 네이티브 실행 JAR과 이 마지막 단위 방어가 포함된 최종 빌드 JAR은 다릅니다.** JSON에 각 해시와 단위만 수행한 변경을 구분하며 최종 JAR 자체의 Paper 재실행 성공으로 표현하지 않습니다.

최종 Gradle build/test/check와 단위/회귀 **36개(새 세션10개)**가 통과했습니다. 이전 WorldLifecycle·WorldPersistence·InventorySaveSession·InventoryTransition·V001/V002와 기존 검증 JSON은 그대로입니다. 최종 문서/언어/diff 검사와 보안 Git 훅 결과는 작업 보고 및 Git 이력에서 확인합니다.

최종 변경 Markdown8개·상대 링크199개·표/제목/코드 블록·일본어/한자 혼입 검사 오류0건, 한국어 문맥과 git diff --check·Python 구문·수동 콘솔 명령 범위를 확인했습니다. 비밀 검사는 기존 pre-commit/pre-push 훅을 사용합니다.
