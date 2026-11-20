# 거주 시스템 구현과 격리 검증

2026-11-20. 사용자 지시에 따라 [ADR-0030](decisions/0030-residential-and-resource-worlds.md)의 시스템을 먼저 구현하고 세부 수치/지형·밸런싱을 분리했다. 기본 거주 보호/구획, 개인 월드 구매·확장·공동 거주, 기존 월드/상점·이동과 저장을 연결했다. [사용법/설정](../infrastructure/network/housing.md)·[집계 증거](../verification/housing-system-result.json)를 따른다.

## 구현 범위

- `/월드`의 공통 3월드와 개인 거주 진입에서 구매/차액 확장·집 입장점·등록 계정 초대·수락/거절·공동 거주/방문·탈퇴/해제·기본 구획 배정을 제공한다. 구매와 시스템 활성화는 기본 비활성이고 예제 수치는 운영값이 아니다.
- E008은 native realm에 묶인 정책/가격표, 소유자당 하나의 주택·월드 UUID/seed/단계, 결제 요청·초대/역할·구획을 저장한다. 같은 경제 realm 잠금에서 잔액/요청/소유 상태를 원자 확정하며 native payload를 수정하지 않는다. 가격표 버전은 주택에 고정하고 새 표로 기존 확장 가격을 덮지 않는다.
- 개인 FLAT 월드의 생성/저장·경계 확대·WorldGuard 반영 후 입장을 연다. UUID/seed/건축물·소유권을 유지한다. 메타데이터/실제 UUID/seed·작업 표식/보호 형상이 어긋나면 재생성하지 않는다. 사용 시 로드하고 유휴 시 저장/언로드하며 입장 청크는 로드 기간에 유지한다.
- 일반 보호는 기존 WorldGuard다. 공용 거주는 지정 구획 소유자만 수정하고, 개인 월드는 소유자/수락한 거주자만 건축한다. 방문자/해제 중인 권한은 열린 보관함의 클릭/드래그도 막는다. 초대 변경의 DB/보호 반영 실패, commit 불명과 늦은 접속/세대 요청은 HOLD/원본 보존이다.
- 개인 접근권한/경계를 메뉴뿐 아니라 포탈·다른 teleport·리스폰/로그인에도 확인한다. 공통 자원 오버월드/지옥을 연결하고 개인 네더·미등록 차원/End 진입은 열지 않는다. 일반 인벤토리·엔더/잔액은 같은 native/경제 데이터를 계속 쓴다.
- 상태/읽기 전용 요청 조회와 저장 종료를 연결했다. 관측에는 실제 로드된 개인 월드 수를 포함하고, 거주 런타임 HOLD는 비준비로 표시한다. 기존 Paper 전체/PG 전체 오프라인 백업 형식은 변경하지 않았다.

## 검사 결과

| 검사 | 결과 |
| --- | --- |
| Java 단위/관련 회귀 | **고유 37개 PASS**: HousingSettings 7·HousingState 3·WorldTravel 10·NativeTransactions 15·NativeMenuRules 2. 차액/역행 거절·가격표 해시·구획 경계/중복·초대/방문/보류 권한·기존 이동/거래 방어 |
| 실제 복사 PG | **21개 확인 PASS**. 동시 구매 한 소유권·예약 무차감·다른 요청 주인 거절·월드 UUID 고정·실제 SQL 23514 전체 롤백·취소 세대 무차감·commit 불명 영수증 한 번·재요청 중복 차감 없음·기존 가격표 고정·수락 경합/역할/탈퇴·공용 구획 경합/멱등/정원·외부 Context/E008 checksum 거절 |
| 입장 청크 보완 뒤 실제 Paper 두 JVM | **27개 확인 PASS / 41.642초**. 실제 개인 월드/WG/가격·확장/기존 블록·오프라인 소유자와 독립된 입장·역할 해제·공용 구획·언로드/재로드·두 JVM 복원/원본 보존. 원시 최종 판정은 포트 bind 검사 때문에 FAIL, 별도 종료 재확인 PASS로 정리함 |
| 최종 JAR의 기존 상태 한 JVM 복원 | **27개 확인 PASS / 19.314초**, 최종 outcome=PASS·cleanup_errors=[]. 같은 입장 청크의 입장점 재지정/유지·독립된 읽기 전용 영수증 조회도 확인 |
| Python TCP 종료 검사 | **3개 PASS**. 실제 listener는 열림으로, 미사용 포트와 실제 TCP 연결 종료 뒤 TIME_WAIT는 닫힘으로 판정 |
| Java25 컴파일/JAR·helper·문서 | PASS. 최종 JAR SHA-256 `bf5e1717c15366867c32dfd7ab71f72b7f6deec285233dd4637803859083fabe` |

WorldGuard의 [일반 보호/우선순위·멤버십 계약](https://worldguard.enginehub.org/en/latest/regions/flags/)을 참고하고, 실제 Paper에 로드한 구역에 UUID 도메인 질의를 수행했다. 이는 실제 플레이어의 블록/보관함/포탈 이벤트 성공 판정이 아니다. 월드 블록 보존 검사는 격리된 새 개인 월드에 서버 API로 표식을 설치하고 확인했으며 가짜 Player·인증 우회는 사용하지 않았다.

## 보완과 원본 증거

첫 묶음에서 PG 21개는 통과했고 개인 월드 생성/결제 후 입장 검사에서 `HOUSING_ENTRY_UNSAFE`가 발생했다. 공통 월드와 달리 개인 입장 청크를 유지하지 않은 경계를 보완했다. 이미 통과한 DB 행렬은 반복하지 않고 새 소유 경로에서 Paper 두 JVM만 검사했다. 이후 유휴 월드 재진입의 시간 갱신·같은 청크 입장점 재지정·권한 변경 중 열린 보관함·관측/읽기 전용 진단을 묶어 보완하고 최종 한 JVM 복원만 추가했다. 순수 모델/기존 거래·이동 코드의 성공 단위 검사는 반복하지 않았다.

두 JVM 묶음의 원시 결과는 모든 기능 검사/종료가 성공했지만 `bind` 실패로 OWNED_PORT_REMAINS를 기록했다. 당시 판정으로는 listener와 TIME_WAIT를 구분할 수 없었다. 실제 소유 PID 부재·모든 기록 TCP 포트 닫힘과 원본 결과 SHA-256을 후속 파일에 보존했다. 종료 검사에 ECONNREFUSED 확인을 추가하고 실제 소켓 회귀 3개로 검증했다. 원시 FAIL 결과/로그/JAR은 수정하지 않았다. 최종 복원은 이 제한된 후속 증거를 명시해 검증 복사했고 최종 정리도 PASS다.

- 최초 묶음/PG: `/home/server/hearth-tmp/hearth-native-economy-run.qznxvxku/native-economy-result.json`, 같은 경로의 `database-console.log`.
- 두 JVM 기능 검사: `/home/server/hearth-tmp/hearth-native-economy-run.pdoztmq7/native-economy-result.json`.
- 종료 독립 재확인: `/home/server/hearth-tmp/hearth-housing.2p4zbq4h/cleanup-followup.json`.
- 최종 복원: `/home/server/hearth-tmp/hearth-native-economy-run.23izkoe2/native-economy-result.json`.
- 단위 XML·빌드/각 단계 JAR·Python 결과: `/home/server/hearth-tmp/hearth-housing.2p4zbq4h`.

고정 Paper26.2 build134/Java25·WG7.0.19/WE7.4.4와 승인된 비활성 세계/PG의 새 복사본을 사용했다. 모든 실행은 localhost·CPU2/nice10·최초600초/작업550초·Paper heap1536MiB/RSS2560MiB·합산RSS6GiB·호스트 가용6GiB·최대Paper2/PG1을 유지했다. 자원 상한/시계를 늘리지 않았고 운영 서비스·SSH/방화벽·기존 세계/DB/이전 증거를 변경하지 않았다. 신규 공용 시험 바닥은 기존 표식 제한 fixture가 새 복사본에서만 준비했으며 제품의 지형 편집 기능이 아니다.

## 미검증과 후속 범위

실제 정품 클라이언트의 메뉴/구매/초대·공유/해제·열린 보관함·블록/간접 변경·포탈/리스폰·끊김/재접속·동접, 운영 규모/장기 부하는 미검증이다. 기존 오프라인 백업에 개인 월드가 포함되는 구조는 유지했지만 별도의 전체 백업 묶음 생성/복원 시나리오는 이번에 반복하지 않았다. 실제 지형/시설·상품/가격/크기/정원/운영 한도와 밸런스는 사용자 지시대로 추후 확정한다. 자원 월드 세대 교체/자동 초기화는 구현/실행하지 않았다. 운영 배포나 실제 플레이 수용 완료를 뜻하지 않는다.
