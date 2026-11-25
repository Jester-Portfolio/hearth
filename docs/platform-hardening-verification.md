# 플랫폼 보완 4개 일괄 구현·검증

2026-11-25. 사용자 승인한 코드 점검의 네 항목을 묶어 수정했다. [구조화 증거](../verification/platform-hardening-result.json)를 따른다. 운영 서비스·원본 시험 자료·밸런스·획득처를 변경하지 않았다.

## 변경

1. **기동 입력:** 신규 배포 후보 format4는 명시한 입력과 별도로 plugins 직속 JAR, Hearth 직속 YAML/properties, enchants 전체 파일과 AuraSkills/WorldGuard/WorldEdit/BetterHud 설정을 자동 발견해 SHA 묶음을 만든다. 동적 users/userdata/backups/logs는 제외한다. 신규 파일 추가·경제 ID 파일·규칙 변경을 포함해 Python과 독립 JDK25 기동 검사가 같은 목록을 다시 계산한다. 명시 입력128개와 별개로 묶음은4096파일·파일당16MiB, 기동 트리100,000개 한도를 둔다. 전체 네트워크 snapshot 검증도 묶음을 대조한다. 과거 format1~3의 역사 검증은 유지하며 신규 후보는 다시 검토한다.
2. **임시 블록 기록:** 메인 스레드의 FileChannel 쓰기/force를 단일 순서 보장 worker로 옮겼다. 대기512개·기록64KiB, 기존 활성/예약 블록 합계256개로 제한한다. CREATE를 fsync한 후 owner 스레드에서 세션·월드·권한·원래 블록을 다시 확인하고 설치한다. 20,000개마다 CREATE_NEW 파일로 회전하며 기존 증거를 삭제/덮어쓰지 않는다. 횟수 누적만으로 전체 RPG를 HOLD하지 않는다. 실제 IO 실패/작업 거부 시 새 임시 효과만 보류하고 경고하며 원본 CREATE 증거를 유지한다. 이미 설치한 블록의 만료/해제 처리는 계속된다. drain은 예약된 설치와 기록 worker의 완료를 기다린다.
3. **설정 의미 검증:** 파일·GUI·reload의 공통 검증에 재료의 실제 블록 여부, mode·불리언·알 수 없는 인자·stat ID·불가능한 이벤트/효과 조합을 추가했다. Aura stat 존재도 확인한다. Java 확장 모듈은 등록 시 자체 validator를 함께 제공할 수 있다. 기본 105종은 모두 유지한다.
4. **메모리 정리:** clear에서 faces/statSeen을 제거하고 quit에서는 fall/rage/소유 투사체 참조도 제거한다. 종료 시 해당 맵을 비운다. 아이템·경험치·장기 쿨다운 영수증을 퇴장 정리 대상으로 삭제하지 않는다.

## 검사

| 범위 | 결과 |
| --- | --- |
| Python 관련40개 | PASS: 기존 후보/수용/복구/shell와 누락 입력·새 정책/플러그인·동적 데이터/링크 회귀 |
| Java 고유22개 | PASS: 기존 엔진9·기동7·worker journal3·새 스키마3. 트리거 보완 뒤 영향받은 스키마만 재실행 |
| Python→독립 JDK25 | PASS: 합성 검토 자료로 종료0, 경제 바인딩 파일 변조/새 정책 추가에서 각각 종료2 |
| 새 격리 Paper/PG | PASS 5개·29.582초: 잘못된 설정3종의 파일/세대 보존,105종 enable, worker 순서/회전·증거7개 보존 |

마지막 실행은 `/home/server/hearth-tmp/hearth-native-economy-run.8_vi6wty`이며 Paper1/PG1·CPU2·Paper heap1536MiB/RSS2560MiB·합산6GiB·600/550초·localhost/EULA/Player 합성 금지 경계를 유지했다. 프로세스 정상 종료·원본 config/월드 매핑/PG control 해시 보존과 정리를 확인했다. 게임 저장/DDL/PG 거래 계약이 바뀌지 않아 기존 DB 경합 행렬과 두 Paper 복원을 반복하지 않았다. 캐시 정리는 소스의 호출 경로를 확인했으며 실제 퇴장 이벤트 수용으로 표시하지 않는다.

최초 검사에서는 상대 plugin 경로를 전달해 DB helper가 IsolatedPaths를 찾지 못했다. Paper는 시작되지 않았고 원본 실패 결과를 `/home/server/hearth-tmp/hearth-native-economy-run.t4hugtir`에 남겼다. 절대 경로와 새 소유 workspace로 수정한 검사는 통과했다. 증거 로그·최종 JAR/코드 SHA는 구조화 기록에 연결한다.

## 한계

실제 인증 Player의 지연 설치·접속 종료/월드 이동·GUI 클릭, 실제 플레이/장기 부하·운영 배포는 미실행이다. journal의 회전은 보존 기간/자동 삭제 정책이 아니며 디스크 사용량은 계속 증가한다. 자동 무손실 장애 복구를 추가하지 않았다. IO 실패 후 수동 점검에는 기존 CREATE/RESTORE 증거를 사용하고 재시작으로 과거 효과를 자동 재실행하지 않는다. 일반 게임 효과가 있는 확장 모듈의 validator를 생략하면 해당 모듈의 고유 인자까지 대신 검증하지 않는다.
