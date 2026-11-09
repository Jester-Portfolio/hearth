# 제작·공개 코스 누적 배치 최종 통합

확인일: 2026-11-09. 기준 `7dd0750` 이후의 [일괄 제작](crafting-batch-verification.md)·[제작 준비](crafting-preparation-verification.md)·[코스 진행판](playthrough-overview-verification.md)·[관련 기록](course-records-verification.md)을 하나의 최종 코드/JAR로 검증했다. **앞서 환경 제한으로 남았던 Gradle 빌드·필수 Paper/격리 DB 통합을 완료했다.** 상세 명령·해시·검사 표식·자원·원시 로그 경로는 [최종 검증 JSON](../verification/pending-batch-completion-result.json)에 둔다. 전체 S4 완료·실제 플레이·운영 배포 승인과 구분한다.

## 최종 아티팩트와 관련 검사

- Gradle `jar test stageLifecycleRuntime`으로 컴파일·JAR 생성·관련 단위/회귀 **171개 PASS**. 범위는 제작, 제작 의뢰/준비, 인벤토리 저장/세션, 코스/기록/스마트폰의 20개 테스트 클래스다. 다른 두 실행 경로는 `stageLifecycleRuntime`만 실행했으며 같은 단위 검사를 반복하지 않았다.
- 최종 JAR: `/tmp/hearth-pending-completion.WU7DeV0c/Hearth-0.1.0-SNAPSHOT.jar`. **1,757,119바이트**, SHA-256 `ee779233b1235feab73998c23e87e7023d853e38a4b863f1c7127a81204ee90a`. 세 통합 실행의 설치 JAR과 모두 일치한다.
- JAR의 주요 기능 클래스 출처·플러그인/API/런타임 라이브러리 버전, 활성화 게이트 20개 기본 false, 동봉 재료 출처 참조, 테스트 클래스 제외 PASS. 임시 검토 패키징 대신 Gradle task graph로 만든 결과다.

## 필수 Paper·PostgreSQL 통합

각각 새 사용자 소유 `/tmp/hearth-s4-11.*`에서 PostgreSQL 1개와 Paper 1 JVM을 순차 실행했다. 모드별 플래그·필수 인자는 최종 JSON의 명령을 따른다.

| 모드 | 세션 검사 | 전체 소요 |
| --- | --- | --- |
| `crafting` | 37개 PASS | 33.466초 |
| `crafting-quests` | 67개 PASS | 52.411초 |
| `play-preparation` | 42개 PASS | 44.594초 |

- `crafting`: 8회 순정 결과·총 재료·실제 상세/준비 Inventory 객체, 이름/보조 손 재료 제외, 재료·공간 부족의 전체 원본 보존, 케이크 4개/양동이 12개, 시설 한정 제작법 링크, 기존 비동기 저장 지연·최신 dirty·실제 CAS 실패/HOLD·다른 진행 보존.
- `crafting-quests`: 판자/막대기/도구 일괄 제작, 도구 4개를 만들어도 개인 목표와 source 저장은 한 번, 개인 보고·후속 완료, source/퀘스트 CAS 충돌 원본 보존, 같은 JVM의 기존 월드/인벤토리 재연결.
- `play-preparation`: 복수 목표의 공동 재료·조달 장소 공개 필터, 실제 책/Dialog 객체, 진행판의 본인 DB revision/추적·HOLD 거부, 개인 발견 기록→일지→최초 연대기→이전 정보, 새로고침과 41슬롯/개인·공유 상태 조회 불변.
- 세 실행 모두 소유 프로세스 종료·포트 반환·정리 오류 없음 PASS. 기존 600초/작업 550초, Paper 힙 1536MiB/RSS 중단 2560MiB, 호스트 가용 RAM 6144MiB, CPU 2/nice +10·localhost·EULA 경계를 유지했다. 운영 서비스·기존 DB/월드·과거 증거는 변경하지 않았다.

## 앞선 차단과 재개 중 수정

앞선 localhost 소켓 생성 및 `.git` 읽기 전용 차단은 당시 세션의 관측이다. [원래 검토 JAR/단위 결과와 차단 근거](../verification/pending-batch-review-result.json)는 보존했으며, 현재 환경에서는 소켓 실행과 원격 fetch가 정상이다.

첫 Gradle 시도는 기존 Kotlin DSL 불변 캐시의 보고서 누락으로 설정 단계에서 실패했다. 원본 캐시를 삭제하지 않고 새 임시 Gradle home에 기존 의존성만 복사했다. 다음 설정 시도의 `--tests` 옵션 위치 오류는 `test` 바로 뒤로 옮겨 해결했다. 두 시도 모두 소스 테스트 실행 전 실패이며 최종 Gradle 관련 테스트는 통과했다. JAR 점검기의 첫 실행도 클래스 경로 누락으로 시작하지 못해 새 임시 경로에서 점검기를 컴파일한 뒤 통과했다. 통합 실행 세 묶음은 모두 첫 실행에 PASS했다.

## 미검증·다음 경계

실제 인증 클라이언트의 제작/GUI/책/스마트폰 클릭·채집·NPC 보고·왕복과 가독성은 사용자 별도 요청 전 보류다. 제어 세션과 실제 Paper 객체 검사를 실제 플레이 성공으로 취급하지 않는다. 저장/복원 계약은 불변이라 두 번째 JVM/DB 재시작을 추가하지 않았다. 장기 부하·TPS/MSPT·운영 배포도 실행하지 않았다. 후속 개발은 PROJECT_STATUS의 남은 S4 기능 범위를 따르며 운영 적용은 별도 승인 대상이다.
