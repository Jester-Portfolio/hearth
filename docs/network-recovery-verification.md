# 네트워크 전체 복구·기동 바인딩 후속 검증

2026-11-21. [ADR-0036](decisions/0036-network-snapshot-and-launch-binding.md), [사용법](../infrastructure/network/network-recovery.md), [구조화 증거](../verification/network-recovery-result.json)를 따른다. 기존 배포 후보/단일 Paper 백업을 네트워크 전체 복구에 연결했고 운영 서비스는 적용하지 않았다.

## 변경

- offline_backup의 기존 단일 Paper 포맷을 유지하고 proxy/lobby/economy/postgres(+검토 assets)를 받는 network-backup-v1을 추가했다. 같은 파일 검사·world lock·전체 inventory/원본 재검증·외부 영수증·새 경로 복원·90초/12GiB/100,000개 기술 상한을 재사용한다.
- network_recovery는 검토 후보와 네 역할의 snapshot을 결합한다. 실행 표식/핵심 미확정 작업·가동 PG/잠긴 월드는 거부하고, 부분 결과를 성공 영수증으로 바꾸지 않는다. 원래 경로와 별개로 bundle의 각 역할 파일과 검토 해시를 대조한다. 새 경로 복원은 데이터·원본 영수증을 보존하고 서비스 경로만 다시 연결한 format2 후보를 만들며 수용 표시를 전부 false로 초기화한다.
- 새 후보는 PG도 별도 역할/파일/수용 상태를 확인한 뒤 기동한다. Java 기동 검사는 역할 불일치·실행 표식·월드 잠금을 거부한다. 환경값은 eval/Compose 변수 보간 없이 원문 그대로 전달한다. PostgreSQL에 Java를 요구하지 않고 SHA-256 도구/기존 PG 진입점을 사용한다.
- Velocity에 기존 RuntimeLease 계약의 프록시 표식을 연결했다. 실행 중 snapshot을 거부하고, 정상 종료·연결자0·동일 표식 확인 뒤에만 자신이 만든 파일을 정리한다. reload/HOLD가 입장을 열거나 실행 표식을 지우지 않는다.

## 검사

| 항목 | 실행 결과 | 의미 |
| --- | --- | --- |
| Java 단위·회귀 | PASS 19개 | 역할별/구버전 기동 검사·실제 FileLock·RuntimeLease 원본/중복/변조 보존·기존 네트워크 설정/프로토콜 |
| Python 고유 검사 | PASS 35개 | network config7/release7/recovery6/start3 + 기존 backup12. 네 역할·cold 이력·원본 경로 없이 검증·변조/활성 표식/중복 출력 거부·literal 비밀번호·PG 수용/해시 거부 |
| 실제 복구 수용 | PASS 14개 확인 | 전체 snapshot 검증/새 경로 복원, 실제 Velocity1/Paper2/PG1, 현재 상태 보고, 프록시 status ping, live snapshot 거부·정상 종료/표식 정리, 원래 계정/보관 ID/스토리 값 보존 |
| Compose/셸 | PASS 구문 | 새 역할/파일 마운트와 no dotenv 구성. Docker 이미지/서비스는 실행하지 않음 |

실제 수용은 기존 성공/정지 소유 경제 플랫폼과 로비 자료에서 새 검토용 네트워크를 준비한 뒤, **백업에서 복원된 네 역할**을 실행했다. 테스트 구성/비밀·포트는 새 격리 자료에서만 준비했고 운영값으로 채택하지 않는다. 최종 JAR은 세 역할 모두 동일하다. 복원본의 native 경제/RPG·설정/자원 보관·현재 WorldGuard/AuraSkills 구성이 실제 기동했다. PG 비교는 원래 스토리 및 계정/인챈트/플랫폼·자원 행을 포함하며 정상 기동 중 갱신되는 경제 시계와 혼동하지 않는다.

한 Paper의 기존 DB 경합/수급/아이템 행렬을 다시 실행하지 않았다. 저장 포맷과 복원 연결이 바뀐 범위에 실제 복원 PG/새 JVM을 사용했다. 프록시 status ping은 로그인이나 서버 이동이 아니다. 인증 Player/가짜 Player를 만들지 않았다. 최종 소스 입력 사전 검사는 별도의 읽기 전용 검사로도 확인했고 기존 성공 자료의 실행 표식/PG PID를 통과 조건에 포함한다.

## 자원·한계

전체 98.710초, 합산 RSS 최대 표본2,569.89MiB. 기존 CPU2/nice10·전체600초/작업550초·Paper2/PG1/Proxy1·Paper heap1536MiB/RSS2560MiB·Proxy heap256MiB/RSS512MiB·합산RSS6144MiB·localhost/EULA·소유 격리 한도를 유지했다. 모든 프로세스가 정상 종료됐고 cleanup_errors=[]·소유 포트 반환·원본 설정/PG control 해시 보존을 확인했다. 상세 원시 경로/해시·표본은 결과 JSON에 보존한다.

실제 클라이언트 접속/전환·클릭, 실제 부하/장기 운영, 이미지 빌드·컨테이너/운영 기동·실제 장애 전환/정전·온라인 PG 백업/PITR는 미검증이다. 실행자 소유 데이터만 복원하며 자동 chown·EULA 동의·HOLD 해제·기존 경로 덮어쓰기·서비스 시작을 하지 않는다. Paper가 정상 기동 중 자체 갱신한 검토 파일도 변경으로 취급하므로 재기동 전 현재 정지 데이터를 새 후보로 검토한다. 복원 영수증이나 구조 검사 PASS가 실제 수용 완료/운영 승인을 대신하지 않는다.
