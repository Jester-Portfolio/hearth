# 네트워크 수용 기록·배포 연결 검증

2026-11-21. [ADR-0037](decisions/0037-network-acceptance-dossier.md), [사용법](../infrastructure/network/network-acceptance.md), [구조화 증거](../verification/network-acceptance-result.json)를 따른다. 기존 배포 후보의 수용 불리언을 검토 범위·원본 증거·롤백 사본과 연결했다. 운영 서비스는 적용하지 않았다.

## 변경

- format3 후보는 JAR/설정·경로·이미지/예산·백업 정책·기동 어댑터·경제 바인딩을 scope에 묶는다. 불리언만 all-true로 바꿔 완료 후보를 만들 수 없다.
- 운영자가 보고한 네 수용 종류의 결과와 SHA별 복사 증거를 불변 event/CAS head로 기록한다. 최신 FAIL/NOT_RUN이 이전 PASS를 덮고 과거 증거는 보존한다. 이는 증거 무결성과 문맥 확인이며 시험 사실의 자동 인증이 아니다.
- pending 의도→증거→event→원자 head를 fsync하고 중단 위치에 따라 동일 의도 재요청/명시적 resume으로 마무리한다. 충돌/손상은 원본을 보존하고 거부한다.
- 외부 영수증으로 네 역할 전체 snapshot을 검증하고 payload의 network UUID·경제 realm/group을 대조한다. 완료 후보 snapshot에는 해당 저널/증거도 보존한다. 새 경로 복원은 수용과 scope를 초기화한다.
- Java 세 역할과 PG 기동에 scope·증거·현재 head를 연결한다. 이후 실패/미확정 기록은 기존 후보의 새 기동을 차단한다. 실행 중인 프로세스를 자동 중단하지 않는다. 과거 format1/2 역사 검증을 유지한다.

## 실행한 검사

| 범위 | 결과 | 확인 내용 |
| --- | --- | --- |
| Python 고유 검사 | PASS 37개 | 설정7·후보7·복구6·shell 기동5·새 저널12 |
| Java | PASS 6개·컴파일 | 기존 기동/역할/세계 잠금 + 완료 scope/변조·최신 head/중단 차단 |
| 독립 JDK25 실행 | PASS, 종료0/2 | 합성 파일로 정상 기동 검사 통과→이후 FAIL head에서 거부. Player/실제 게임 동작 아님 |
| 실제 기존 snapshot 연결 | PASS | 이전 네트워크 격리 시험의 외부 SHA로 전체 사본 검증·같은 network/realm/group 확인 |
| Compose 구문 | PASS | config --quiet만 실행, 이미지 빌드/컨테이너 기동 없음 |

새 저널 검사는 같은 head 경합의 단일 확정, 기록 전/후 중단 복구, 증거 변조·미실행 관측의 client/load PASS 거부, 다른 경제 데이터의 롤백 혼용 거부, 역사 증거 포함 백업·새 경로 복원의 수용 초기화를 확인했다. 완료 후보를 합성 결과로 만드는 테스트는 계약 단위 검사이며 실제 수용으로 세지 않는다.

최종 안전 보완 뒤 영향받은 Java6개와 독립 JVM을 다시 확인했다. 전체 Python 배치37개 이후 변경된 저널의 완료 후보 재귀 사용 차단은 해당12개, 예산 scope 구별의 추가 검사는 해당1개만 재확인했다. 변경 없는 전체 검사/실제 Paper·PG·Velocity 실행은 반복하지 않았다. 게임 이벤트·SQL/게임 저장 계약은 바꾸지 않았으며 [직전 실제 네트워크 복원 실행](network-recovery-verification.md)의 증거는 보존한다.

원시 증거는 `/home/server/hearth-tmp/hearth-acceptance.upzkf98r`에 있고 최종 JUnit 결과/코드·상태·후보 SHA는 구조화 증거에 연결했다. 최초 준비 자료와 최종 안전 보완 뒤 자료는 별도로 남겼다. 기존 snapshot/운영 데이터는 수정하지 않았다. 새로운 게임 서비스·DB/월드 프로세스를 시작하지 않았다.

## 미검증과 다음 범위

최종 private 검토 자료의 상태는 client/load/restore/release_regression **NOT_RUN**, rollback **PASS**다. 기존 시험 PASS를 새 검토 범위에 자동 승계하지 않았고 미완료 seal이 출력 생성 전에 거부되는 것을 확인했다. 실제 클라이언트·동접/장기 부하·현재 릴리스 전체 수용, 실제 이미지 실행/운영 전환·백업 스케줄/오프사이트·Zabbix 적용은 남는다. 운영 예산·보존/RPO/RTO·이미지의 실제 값은 선정하지 않았다. 후속은 보류된 실제 수용/운영 결정 또는 새로 확인된 기능 결함이며 밸런스·획득처를 선행 조건으로 요구하지 않는다.
