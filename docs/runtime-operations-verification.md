# 정상 기동 보호·운영 사전 점검/관측

2026-11-18. 실제 플레이/레벨 디자인을 제외한 시스템 보완이다. 운영 서비스·Docker 데몬·Zabbix·SSH/방화벽·기존 영속 데이터는 변경하지 않았다.

## 구현

스토리 정상 모드는 준비 단계에서 등록한 PG 불변 연결을 **확인만** 한다. 연결이 없거나 콘텐츠/월드 소유가 다르면 HOLD하며 기존 월드를 새 진행으로 채택하지 않는다. 명시적인 새 진행 등록은 기존 별도 준비 모드가 담당한다. 기존 `ensure`는 격리 fixture 등 명시적인 등록 경계에서만 유지한다.

경제는 월드 로드 전에 기존 `.hearth-economy-world`의 존재를 기억한다. 기존 소유 월드에서 PG realm이 없으면 재고/화폐를 재초기화하지 않고 HOLD한다. 기존 realm도 콘텐츠와 시장 키를 검증하며 누락/SQL 실패를 성공으로 처리하지 않는다. 최초 검토한 미소유 경제 월드의 초기 등록과 새 계정 0/PAUSE 정책은 유지한다. 소유 표식 삭제/자동 재확정 경로는 추가하지 않았다.

기본 비활성인 `runtime-observation-seconds`는 컨트롤러 단계·월드/HOLD 수·기동 nonce·TPS/MSPT/힙·AI 준비 이력만 로그로 기록한다. DB/HTTP/모델 호출을 추가하지 않으며 질문/답변·개인 ID·주소/비밀번호를 받지 않는다. `runtime_health.py`는 제한된 로그의 정확한 서버 스레드 접두사·스키마/시간/nonce를 확인한다. 과거 READY, 채팅 위장, 깨진 최신 보고서, 심볼릭 링크와 종료를 정상 준비로 판정하지 않는다. `controller_ready`와 준비 이력은 전체 게임 효과·현재 모델 적재/AI 품질·PG 종단 간 수용의 보증이 아니다.

오프라인 `operatorConfigAudit`는 기존 파서·콘텐츠 해시·인증/모드/AI 구성·월드 소유 표식/진입 청크 존재·필수 플러그인 명세를 읽고, 새 private 출력에 정적 manifest를 만든다. SQL/월드 로드/HTTP/환경 변수 값 읽기를 하지 않는다. JDK 전용 기동 검사기는 외부에 고정한 manifest 해시와 정적 파일을 다시 확인하고, Paper가 수정할 수 있는 인증/EULA 속성은 의미로 재검사한다. 동적 월드/PG 내용의 무결성은 이 manifest의 검증 대상이 아니다.

[운영 검토 구성](../infrastructure/deployment/README.md)은 Java25/PG 진입점, 필수 예산·경로·digest 변수의 Compose 후보, 고정 Zabbix Text 항목 양식과 drain/백업/짝 복원 롤백 절차를 담는다. Linux host network 후보는 아직 채택된 운영 토폴로지가 아니다. 자동 initdb/chown/EULA 동의·운영 설정 덮어쓰기·표식 삭제·자동 재시작을 하지 않는다. 별도 운영 승인 후 검토한 이미지로 빌드/실행해야 한다.

## 검사와 증거

- Java **11개 PASS**: 집계 스키마/읽기 상태 3개·AI 수동 관측 1개, 기동 manifest/변조·인증·미정리 표식·심볼릭 링크·EULA/포트/모드 7개. 변경한 검사만 실행했다.
- Python **9개 PASS**: 정상/비준비, 다른 기동/오래된 시각, 채팅 접두사 위장, 잘못된 스키마/숫자/중복 키, 부분 행·경고·최신 완결 오류와 제한 로그/파일 경계를 검사했다. 첫 8개 성공 후 추가한 잘못된 최신 완결 행 1개만 따로 실행했다.
- 최종 JAR의 정상 스토리 **두 JVM/PG PASS**, `/home/server/hearth-tmp/hearth-s4-11.tsn13qf2`, **46.108초·관련 assertion 15개**, `cleanup_errors=[]`. 실제 누락 연결/콘텐츠 변경 거부·경제 realm 첫 등록 경합 1회·기존 소유 누락 거부·실제 시장 SELECT `42P01`·새 JVM의 시계/revision/개인 인벤토리/공유 원본을 확인했다. 현재 READY 보고서의 읽기 검사와 정상 drain/종료 뒤 CLOSED의 역사 로그 판정을 구분했다.
- 같은 JAR의 정상 경제 **두 JVM/PG PASS**, `/home/server/hearth-tmp/hearth-s4-11.pk72h4r6`, **48.442초·관련 assertion 20개**, `cleanup_errors=[]`. 새 소유 격리 fixture에 기존 경제 소유 표식을 명시해 정상 기동의 재초기화 금지 분기를 실행하고 기존 계정/잔액·시장/운송·PAUSE·스토리 원본과 종료를 확인했다. 이전 거래/채집 SQL 행렬은 반복하지 않았다.
- 별도 JVM의 오프라인 감사 **13개 PASS**: 비활성 공개 합성 자료의 새 사본에서 스토리/경제/해시 채집 정의 파싱, 기존 출력 보존, 해시/필수 AI/경제 초기 잔액/미정리 표식·링크 거부와 실패 시 성공 출력 미생성을 확인했다. 기존 증거는 읽기만 했으며 PG/HTTP/모델 없이 통과했다.
- 실제 `gradle operatorConfigAudit`의 인자/클래스패스 연결도 새 출력에서 PASS했다. 기존 클래스 검사와 별도로 실행 경로를 확인했으며 네이티브 검사는 반복하지 않았다.
- 설치된 **Docker Compose v5.5.0의 `config --quiet` PASS**, 두 shell 구문과 승인 표식 부재의 즉시 거부 PASS. 검토용 가짜 digest/비존재 데이터 경로만 사용했으며 Docker 이미지 조회/빌드/실행은 하지 않았다. 이 결과는 실제 이미지 호환성·운영 배포 성공이 아니다.

첫 두 통합 시도 `/home/server/hearth-tmp/hearth-s4-11.obnukwlp`(33.032초)와 `hearth-s4-11.k0lmjzyr`는 **검증 코드의 오류 코드 기대값** 때문에 FAIL이었다. DB worker 안의 누락 예외는 `INVALID_STATE`로 변환되지만 read 이후 비동기 검증 예외는 `STORY_BINDING_CONFLICT`로 남는 차이를 반영했다. 실패 원본·해당 JAR/로그/풀 종료 분류 오류를 보존하고 최종 새 격리 경로에서 통과했다. 원래 FAIL을 PASS로 덮어쓰지 않았다.

최종 JAR `/home/server/hearth-tmp/hearth-runtime-safety.qo69wcaj/Hearth-reviewed.jar`, SHA-256 `cb914bcae6807557b5362ab6ebd7be63a67e10f57d1610fe93a5b82f0d01ea82`. [결과 JSON](../verification/runtime-operations-result.json)에 원시/단위/CLI 증거를 연결했다. 각 native 시험은 기존 CPU 2/nice10·Paper heap1536MiB/RSS 중단2560MiB·호스트 가용6GiB·총600/작업550초·최대2Paper/1PG를 유지했다.

실제 클라이언트·Docker 이미지 빌드/컨테이너 실행·실제 Zabbix 수집/권한·운영 배포/장애 전환·장기 부하/AI 응답 품질은 **NOT_RUN**이다. 운영 자원/SLO·토폴로지/이미지·보존/오프사이트·RPO/RTO는 미정이며 이 코드가 운영 승인을 대신하지 않는다.
