# 기존 경제 시스템의 일반 생존 연결 검증

2026-11-18. 사용자가 일반 생존 멀티서버에도 기존 경제 시스템을 넣도록 요청했다. [ADR-0027](decisions/0027-native-survival-economy-integration.md)의 `survival-economy` 연결로 기존 계산·PG 거래·공개 정보/안내를 재사용했다. [사용법/설정/관리](../infrastructure/network/native-economy.md)를 따르며 스토리 추가 개발·운영 환경 변경은 없다.

## 구현

- 거주·야생·지옥이 같은 PG 화폐/경제 realm을 사용한다. 새 계정 잔액 0·오프라인 PAUSE, 지역 생산/소비·투입 재료 제련·지연 운송·재고/수급·EMA 가격·등록 사건은 기존 엔진/저장소를 재사용한다.
- `/경제`·`/돈`, 실제 구성 상인/`/상점`의 수량/구매·판매 견적·최종 확인, `/거래`의 두 계정 제안·양쪽 동의, 시장/시설·운송/사건·상품 공급망/안내·본인 거래 기록을 연결했다. 새 공급자/Vault/QuickShop은 설치하지 않았다.
- 평상시 native 인벤토리·엔더·경험치·체력·전투/사망을 유지한다. PG payload를 입장/재접속 때 적용하지 않는다. 거래 순간 최신 41슬롯을 그림자 CAS로 준비한 뒤 기존 원자 PG 거래를 실행하고 같은 접속/원본에만 적용한다. 커서/준비 실패는 금융 작업 전 확정 거절한다.
- E006은 native realm을 생존 group/세 월드 식별자 해시에 묶는다. 기존 RPG realm/다른 그룹/다른 월드·legacy runtime 혼용, PG 누락 시 재초기화를 거부한다. 기존 E001~E005 파일/PG 데이터·스토리/세계 registry를 보존한다.
- SQL 실패·CAS/commit 불명·사망/접속 종료·늦은 응답·native 적용/저장 실패는 작은 요청 표식/원본/영수증을 유지하는 HOLD다. 재접속/재시작 때 자동 재제출·아이템 복원/재지급하지 않는다. 콘솔 pending/inspect·등록 사건 예약과 정상 drain을 제공한다.
- 상인 실제 객체 교체·거리/월드·접속 토큰/화면을 확인하며 오래된 조회/화면·취소된 입력·허용하지 않은 클릭·드래그·커서 아이템으로 거래를 시작하지 않는다. 개인 거래는 기존 제안 revision/양쪽 확인·3분 만료를 사용한다. 거래/계정 준비/개인 거래 제안 중 월드/로비 이동을 시작하지 않는다.

## 검사 결과

[집계 JSON](../verification/native-economy-result.json)에 원본 증거·코드별 해시·자원 상한을 기록했다.

| 검사 | 결과 |
| --- | --- |
| Java 단위·회귀 | 고유 **19개 PASS**: NativeTransactions 15·설정 2·메뉴 입력 2. 적용 전 양쪽 검증, SQL/commit 불명·재생 영수증·원본 변조·사망/늦은 콜백·시간 초과·native 저장 실패·중복 클릭·HOLD 재시작/손상·커서 준비 거절 포함 |
| Python 설정 | **6개 PASS**: 기존 새 파일/권한/미덮어쓰기와 경제 overlay·잘못된 연결/경로의 출력 거절·YAML 예약어 이름의 문자열 보존 |
| Java25 컴파일·JAR·격리 helper 준비 | PASS. 최종 JAR SHA-256 `a628cd7bf69e8790ec7a6ed3f6cd0fac562cae8d81382186611373bd16579ccd` |
| 복사 PG + 실제 생존 Paper 두 JVM | **36개 확인 PASS / 38.84초**. E006/소유권·실제 CAS/23514/다른 Context·checksum 거절, 실제 상인/코덱·상점/개인 거래·4 Tick의 생산/운송/사건/공개 조회, 잔액/시세/기록/HOLD 복원·기존 행 보존·정상 drain 확인 |
| 최종 제어/상인 UI 보완 후 기존 경제 상태의 한 JVM 복원 | **19개 확인 PASS / 18.484초**. 이전 통과 시험의 새 읽기 전용 복사 원본으로 최종 JAR 기동·기존 잔액/시세/거래/보류 유지·다시 요청 차단·기존 다른 realm/스토리 행 보존·정상 종료 확인 |

두 JVM 증거는 `/home/server/hearth-tmp/hearth-native-economy-run.4yuwb1yv/native-economy-result.json`, 최종 복원 증거는 `/home/server/hearth-tmp/hearth-native-economy-run.yxsv_e00/native-economy-result.json`이다. 전자는 마지막 커서 준비 거절/상인 객체 확인 보완 전 JAR이며 집계에 두 해시를 구분했다. 보완 뒤에는 영향받은 NativeTransactions 15개와 컴파일·최종 복원만 다시 실행했고 신규 생성/DB 경합 시나리오는 반복하지 않았다. 빌드/XML·코드별 JAR·Python 결과는 `/home/server/hearth-tmp/hearth-native-economy.a2nt0hpz`에 보존한다.

같은 고정 Paper26.2 build134/Java25·WE7.4.4/WG7.0.19와 복사 PG를 사용했다. 승인된 비활성 세계/PG에서만 새 소유 경로로 복사했으며 원본 설정·registry/매핑·PG control은 불변이다. CPU2/nice10·600초/작업550초·Paper heap1536MiB/RSS2560MiB·합산RSS6GiB·호스트 가용6GiB·localhost·최대 두 Paper/단일 PG를 유지했다. 두 시험 모두 소유 PID/포트 종료·cleanup_errors=[]다. 첫 시험의 최대 Paper RSS는 약 1,107MiB, 최종 복원은 약 908MiB다. 짧은 기동/제어 관측은 동접 부하 수용 기준이 아니다.

## 실제 Player와 구분

Paper 검사의 InventoryPort는 실제 Bukkit 코덱/아이템 봉투와 PG를 사용하는 **제어 모형**이다. 실제 Player·로그인·클릭·Player.saveData·세계 효과를 합성하지 않았다. 모형의 native 저장 호출 횟수는 실제 개인 파일 저장 성공 근거가 아니다. 실제 Player.saveData→파일 확인 연결은 구현했으며 기존 파일 영수증의 단위 근거와 별개로 실제 계정 수용이 필요하다.

실제 정품 계정의 메뉴/상인 클릭·연속 구매/판매·커서/제작/상자/개인 거래·아이템/엔더/경험치·사망/재접속·3월드/로비 전환과 동접/부하·긴 장애 수용은 미검증이다. 실제 클라이언트 시험은 별도 요청 전 보류다. 콘텐츠 수치/상인 배치·거주지 소유/보호·야생 리셋·운영 배포/예산/기존 데이터 이전도 별도다. 파일/PG/메모리의 완전 자동 원자 복구나 E1~E3 전체 완료를 주장하지 않는다.
