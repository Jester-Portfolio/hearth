# 격리 개인 방문 퀘스트·NPC 검증

기준 커밋은 `37cc064f1291bac6de8e1aae38c5261644713241`입니다. 시작 시 main·작업 트리·스테이징이 깨끗했고 `git fetch origin` 후 origin/main과 일치했습니다. 이번 범위는 승인된 최소 개인 퀘스트이며 S4 전체 완료나 운영 배포가 아닙니다. [플러그인 비교](plugin-suitability-review.md#격리-npc개인-방문-퀘스트-검토--2026-10-26)의 근거로 별도 외부 플랫폼을 채택하지 않고 Paper 주민 하나와 기존 저장 경로를 연결했습니다.

## 구현한 플레이 흐름과 저장 경계

1. 서버가 검증한 기존 개인 진행·인벤토리·월드 복원이 끝난 ACTIVE 세션만 NPC와 상호작용합니다.
2. `시험 안내원`을 우클릭하면 채팅에 중립적인 안내와 **수락** 선택이 나옵니다. 대화 없이 명령을 보내거나 NPC에서 4블록 이상 떨어지면 수락/보고하지 않습니다. 4블록은 이 격리 시험의 상호작용 거리이지 운영 확정값이 아닙니다.
3. 수락 저장 성공 후 `ACTIVE`가 됩니다. 지정 지점 방문은 이동 이벤트로 감지하며 `REPORT_READY` 저장 성공 후 보고 대기를 안내합니다. 수락 전에 방문하면 상태를 바꾸지 않습니다.
4. 같은 NPC에게 돌아가 **보고**를 선택하면 `COMPLETED` 저장 성공 뒤 완료를 표시합니다. 완료 후 반복 수락·보고·방문은 다시 적용하지 않습니다.
5. 종료·재접속과 새 JVM에서는 기존 DB 기록을 읽습니다. 첫 수락만 퀘스트 행을 만들며 기존 개인 진행 행이 없으면 거부합니다. 로그인 시 인벤토리나 진행을 새로 생성하지 않습니다.

콘텐츠는 [quest-demo.yml](../src/main/resources/quest-demo.yml)에 있으며 격리 서버의 `plugins/Hearth/quest-demo.yml`로 복사합니다. NPC 이름/좌표·대사/선택지·목표 구역은 Java 밖에서 관리합니다. 자동 hot reload·운영 콘텐츠 버전 이관은 추가하지 않았습니다. 주민 좌표는 `(3.5,64,5.5)`, 목표는 `x/z 12~14, y 64~67`인 가상 시험 지점입니다. 보상·아이템 소모·경제·컷신·전투·AI·스마트폰은 없습니다.

[V003](../src/main/resources/db/migration/V003__personal_quest.sql)은 `story_run_id+player_uuid+quest_id` 기본 키와 기존 개인 진행에 대한 복합 외래 키를 사용합니다. V001/V002는 변경하지 않았습니다. `NOT_ACCEPTED`는 **유효한 개인 진행에 퀘스트 행이 아직 없는 경우**이며 손상/접근 오류를 뜻하지 않습니다. 수락은 중복 INSERT 거부, 후속 전이는 기존 phase·revision CAS로 보호합니다. 기존 인벤토리와 개인 진행의 일반 상태 값을 덮어쓰지 않습니다.

DB worker/연결 풀과 Paper owner executor를 재사용합니다. 변경마다 쓰는 것은 드문 퀘스트 단계 전이뿐이며 이동 패킷마다 DB 쓰기·아이템 클릭 원장·전체 체크포인트는 없습니다. 같은 키의 in-flight 작업이 있으면 중복 요청을 거부합니다. 이전 세션 저장을 기다린 뒤 새 세션이 DB를 다시 읽고, 옛 세션 콜백은 현재 UI/진행에 적용하지 않습니다. 실제 완료 저장 실패 시 보고 대기와 기존 revision을 보존하고 HOLD하며 성공 메시지를 보내지 않습니다. commit 불명도 자동 재실행하지 않습니다.

퀘스트 작업은 기존 월드 저장 장벽에 포함합니다. 실패 장벽을 정상 언로드로 취급하지 않으며, 성공한 언로드 뒤 캐시를 해제합니다. 운영자가 실패 로그/DB를 대조한 후 수동 대응해야 하는 범위는 그대로 남습니다. 강제 종료의 무손실 자동 복구를 보장하지 않습니다.

## NPC·보호·일반 서버 비활성

- 기본 `quest-verification-enabled: false`입니다. 기존 새 `/tmp/hearth-s4-11.*` 표식·세션/수명주기 설정과 JVM 플래그에 더해 `hearth.verification.quests=true`가 있어야 생성합니다. 일반 서버의 명령 등록은 게임 동작을 활성화하지 않습니다.
- 기본 주민 엔티티는 AI/중력/충돌을 끄고 무적·비영속으로 생성합니다. 식별 태그와 월드별 UUID만 추적하고 `World`/`Entity` 객체를 장기 보관하지 않습니다. 월드/관련 청크 로드 시 중복을 정리하고 하나만 준비합니다. 월드 언로드 때 추적을 해제하며 플러그인 종료 시 본인 태그 엔티티만 정리합니다.
- NPC가 있는 한 블록 열에만 WorldGuard `INTERACT=ALLOW`, `PASSTHROUGH=ALLOW` 구역을 둡니다. 기존 BUILD·건축 구역·진행 접근 권한은 바꾸지 않습니다. 기존 구역이 콘텐츠와 다르면 덮어쓰지 않고 거부합니다. 실제 이벤트가 이미 취소됐으면 대화를 시작하지 않습니다.
- 주민 거래창은 열지 않습니다. 기존 커서/컨테이너 GUI/엔더 상자 제한과 인벤토리 저장 경계를 유지하며 채팅의 정확한 수락/보고 명령만 내부 처리합니다. 다른 보호 기능이 취소한 명령은 처리하지 않습니다.
- 외부 Citizens/BetonQuest 설치나 NPC 플랫폼의 운영 아키텍처 채택은 하지 않았습니다.

## 검증 구분

| 완료 조건 | 검사 경계 | 결과 근거 |
| --- | --- | --- |
| 수락 전 목표 진입 무효 | 단위·실DB 제어 호출, 실제 콘텐츠 구역 판정 | NOT_ACCEPTED 유지, REPORT_READY 전이 거부 |
| 수락→목표→보고 대기→완료 | 실제 Paper/PG에서 같은 저장 조정자 호출 | phase/revision `ACTIVE/0 → REPORT_READY/1 → COMPLETED/2` |
| 중복·늦은 콜백 | 단위의 대기 Future/세션 교체, 실제 DB 중복/CAS | 중복 쓰기·옛 세션 적용 거부; 완료 revision 불변 |
| DB 실패 | 실제 시험 DB trigger가 완료 UPDATE를 SQLSTATE23514로 거부 | 보고 대기/revision1 유지, HOLD·장벽 실패, 다른 개인/진행 불변 |
| 종료/재접속·새 JVM | 내부 세션 재연결·실제 월드/PG·두 Paper JVM | A/P1 완료/revision2, A/P2 보고 대기/revision1, B/P1 미수락 유지 |
| 진행/개인 격리 | 실DB A/P1·A/P2·B/P1 및 기존 세션 회귀 | 복합 키 조회·다른 개인/진행 불변, 잘못된 개인 참조 거부 |
| NPC 포함 월드 재로드 | 실제 Paper 엔티티·WorldGuard·IDLE 저장/언로드 | 반복 ensure에도 하나, 언로드 추적 해제, 재로드 하나·보호 구역 복원 |
| 실제 NPC 클릭·채팅 선택·이동 | 인증 클라이언트 | **미실행**. 위 내부 호출·API 검사를 실제 플레이로 간주하지 않음 |

## 실행 결과와 자원

Gradle `build test check` 및 JUnit **43개(새 퀘스트 6개 포함), 실패/오류/skip 0**을 확인했습니다. 기존 세션·인벤토리·수명주기 단위 회귀를 포함합니다. 기존 별도 `inventoryIntegrationTest`는 V003 기대 버전으로 갱신·컴파일했지만 이번에는 별도 DB로 재실행하지 않았습니다. 실DB 검사는 아래 기존 Paper/DB 도구 안에서 수행했습니다.

- 첫 묶음 `/tmp/hearth-s4-11.a3lpsq3j`: 53.950초 PASS. 초기 보고 대기 전이의 SQL 실패를 검사했습니다.
- 최종 묶음 `/tmp/hearth-s4-11.0yry4h2e`: **54.884초 PASS**. 완료 UPDATE 실패를 보강하고 채팅 명령 등록·기존 취소 존중·목표 중복 억제·수동 상태 조회를 반영한 최종본입니다. 자동 실행 한도 3묶음 중 2묶음을 사용했습니다.
- 최종 빌드/두 JVM 설치 JAR SHA-256: `84793c22bd2178d9b75071154f84bd8cc44ac5f5cc4fa7979f7fd9fc4ccad2b2`. 실행 후 모든 src/main·Gradle 입력 해시와 일치하며 이후 게임 코드를 변경하지 않았습니다.
- 두 묶음 모두 Paper는 순차 두 번, PG는 한 개였습니다. 정상 종료 exit0·DB worker/풀 정리·소유 프로세스 그룹·게임/DB 포트 반환을 확인했습니다. 기존 운영 서비스와 이전 증거는 변경하지 않았습니다.

| 최종 묶음 | 시작 완료 | 최대 표본 RSS | CPU 시간 | 종료 소요 |
| --- | --- | --- | --- | --- |
| 첫 Paper |14.800초|1,037.57MiB|64.86초|3.658초|
| 두 번째 Paper |8.663초|928.14MiB|32.20초|3.516초|
| DB 보조 JVM |준비 표식 확인|205.27MiB|2.26초|0.208초|

최소 표본 가용 RAM 약11.339GiB입니다. CPU2/nice+10·Xmx1536MiB·RSS2.5GiB 중단·가용 RAM6GiB·디스크4GiB·localhost·600초 제한을 지켰습니다. DB 보조 JVM RSS는 PostgreSQL 자식 전체 메모리가 아닙니다. 고정 Paper 업데이트 안내·JOML Unsafe·수동 저장 경고는 그대로 기록했으며 예상한 SQL 거부를 일반 성공으로 바꾸지 않았습니다. TPS/MSPT·누수·장기 안정성은 측정하지 않았습니다.

[결과 JSON](../verification/personal-quest-result.json)은 시도별 결과/자원·원시 로그 해시·최종 빌드 입력과 단위/실DB/실제 Paper/미실행을 구분합니다. 수락·방문·보고의 실제 클라이언트 이벤트는 아직 실행하지 않았고, 내부 호출로 상태 전이를 검증한 것입니다. 외부 NPC 플랫폼의 부하·호환성, 운영 DB 업그레이드, 콘텐츠 변경/핫리로드, 강제 종료 복구도 미검증입니다.

문서 검사는 변경 Markdown 8개·상대 링크 219개에서 오류 0건, 표/제목/코드 블록·일본어/한자 혼입 오류 0건입니다. `git diff --check`, Python 구문·결과 JSON·최종 빌드 입력 및 기존 검증 증거 불변도 확인했습니다. 비밀 검사는 기존 Git 커밋/푸시 훅을 사용합니다.

## 실제 클라이언트 후속 절차

사용자 준비를 확인하기 전에는 제한시간 서버를 시작하지 않습니다. [기존 수동 절차](player-session-manual-test.md)의 새 경로 생성·`stageLifecycleRuntime`·프로필/SSH 준비를 그대로 사용하되 실행 인자에 `--quests`를 추가합니다. 자동 시험의 실패/HOLD 데이터는 사용하지 않습니다.

```sh
python3 scripts/verify-lifecycle.py --workspace "$HEARTH_MANUAL_ROOT" --java /home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/java --plugin build/libs/Hearth-0.1.0-SNAPSHOT.jar --artifact-source /tmp/hearth-s4-2.vnd2kki4 --accept-eula --sessions --quests --manual-player --operator-ready --profile PROFILE_NAME
```

`FIXTURE READY` 후 한 단계씩 진행합니다.

1. 접속·복원 후 수락 전에 목표 `(13,64,13)`을 방문하고 NPC에게 돌아옵니다. 여전히 수락 선택이어야 합니다.
2. NPC 우클릭→채팅 수락 선택. 운영자는 `hearthverify session-status PROFILE_NAME`의 `MANUAL_QUEST phase=ACTIVE revision=0`을 확인합니다.
3. 목표를 방문하고 보고 대기 안내를 확인합니다. 상태는 `REPORT_READY revision=1`이어야 합니다.
4. NPC에게 돌아와 보고 선택. `COMPLETED revision=2`와 완료 안내를 확인합니다. 반복 클릭/보고로 revision이 늘지 않아야 합니다.
5. 종료·빠른 재접속과 IDLE 이후 재입장에서 완료 표시를 확인합니다. 첫 JVM을 정상 종료하고 두 번째 JVM의 자동 `session-link-existing` 준비 후 다시 접속해 완료를 확인합니다.

실제 시험에서는 NPC 상호작용이 WorldGuard에 막히지 않는지, 거래창/아이템 변경이 발생하지 않는지, 대화 선택과 이동 감지가 실제로 이어지는지 함께 확인합니다. 계정 비밀번호·토큰은 요청하지 않습니다. S4-3의 B 실제 행동·일부 bypass 전환/전체 반복은 별도 미완료이며 이번 독립 구현을 막지 않습니다.
