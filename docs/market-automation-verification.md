# 시장 자동 운영·경제 에이전트 검증

2026-11-18. [ADR-0028](decisions/0028-autonomous-market-agents.md)의 부족량 기반 운송·모드 판단·영속 복원을 일반 생존 경제에 연결했다. 사용자는 **경제 에이전트만 사용**을 선택했다. 기본 `ai-mode: none`으로 자동 운영하며, 별도로 승인한 선택형 LLM 재시험 결과도 구분해 기록한다. [설정/관리](../infrastructure/network/market-automation.md)·[집계 JSON](../verification/market-automation-result.json)을 따른다.

## 구현

- 기존 도착/생산/소비 이후 출발지 비축량, 목적지 부족량, 현지 소비/생산 재료, 운송 중 수량과 경로 상한을 계산해 출하량을 자동 배분한다. 공급지별 비례 배분과 정수 잔여/목적지 경합의 틱 순환을 사용한다.
- BALANCED/RESTOCK/CONSERVE를 연속 판단·최소 유지 시간으로 안정화한다. 기존 생산/재료·지연 운송·가격/EMA/매매 스프레드/왕복 방어는 재사용한다. 에이전트는 계정 잔액/아이템을 쓰지 않는다.
- E007 컨트롤러의 정책 해시/틱/모드/후보/횟수/revision과 전환 이력을 경제 틱의 재고/출하/수급/시계와 원자 저장한다. CAS·롤백/commit 불명·중복 틱·legacy/native 소유권 경계와 PAUSE/재시작 복원을 유지한다.
- 선택형 LLM은 개인 정보 없는 숫자 집계와 허용 모드만 받고 최신 후보/정책/틱을 재검증한다. JSON Schema·명시적 준비·한 요청·최소 12경제 틱 간격/질의 6초·늦은 결과 폐기·실패 시 규칙 운영을 사용한다. 모델이 잔액/아이템/명령/임의 가격을 조작할 수 없다.

## 실행한 검사

| 검사 | 결과 |
| --- | --- |
| Java 단위·관련 회귀 | 고유 **48개 PASS**: 운송 8, 모드 판단 7, Director/설정 8, JSON 분류 3, 기존 경제 엔진 10, 기존 OllamaAssistant 12 |
| Python 합산 자원/기존 개별 한도 | **7개 PASS**. 기존 시계·AI/전체 PID 트리·PID 재사용·RSS/GPU 경계 회귀 |
| Java25 컴파일·JAR·격리 helper 준비 | PASS. 최종 JAR SHA-256 `157c75a4b88f06045af1cfe59da2ad531e99ccced15a072b27479012de3fdb88` |
| 최초 복사 PG·실제 Paper 두 JVM | **알고리즘/DB 33개 확인 PASS**, 전체는 선택형 AI 거부로 **PARTIAL**, 156.366초. 실제 예약 틱·부족 시 RULE 전환·동시 CAS 한 승자·실제 SQL 23514 롤백·commit 불명 확정 이력 한 번·계정 원본/기존 realm 보존·정확한 다음 JVM 복원 |
| 최종 JAR, 외부 AI 없는 한 JVM 복원 | **15개 확인 PASS / 19.166초**. 정책/모드/후보/횟수/시계와 이력·기존 행 보존, LLM 제거 후 상태 유지 |
| 별도 승인한 최종 JAR 실제 LLM 시험 1회 | **15개 확인 PASS / 127.300초**, 알고리즘/선택형 AI 모두 PASS. 고정 실제 모델의 허용 후보 응답 **수용 1/거부 0**, 원시 답변 저장 없음 |

실제 PG의 LLM 출처 모드 전환/commit 불명 검사는 **합성된 허용 Advice**로 검증했다. 실제 모델 시험은 실제 HTTP 추론·JSON 검증·허용 후보 수용을 증명한다. 실제 모델 선택으로 특정 시장 모드가 PG에 전환됐다는 별도 판정이나 장기 경제 품질을 뜻하지 않는다.

## 보완과 증거

최초 시험은 자동 시장·PG/두 JVM 복원을 통과했지만 모델의 자유 형식 응답을 허용 코드로 수용하지 못했다. 당시 진단만으로 응답 형식/후보 중 어떤 조건이 원인인지 단정할 수 없다. 실패 원본을 보존하고 명시적 JSON Schema/엄격한 객체 검증·정제된 실패 코드를 추가했다. 관련 회귀를 통과한 뒤, 이미 성공한 DB 신규 생성/경합 시나리오를 반복하지 않고 최종 코드의 복원과 승인된 새 AI 시험 한 번만 수행했다. 초기 단위 컴파일의 테스트 import/도우미 이름 충돌도 수정했으며 이후 해당 검사는 PASS다.

- 최초 전체 시험: `/home/server/hearth-tmp/hearth-native-economy-run.15eyijv8/market-result.json` (당시 JAR `2197463b66539723f581b41a25e32d5397c3fdb4c06085d557ce4360591aadfb`).
- 최종 AI 없는 복원: `/home/server/hearth-tmp/hearth-native-economy-run.515dkmjv/market-result.json`.
- 승인된 최종 AI 시험: `/home/server/hearth-tmp/hearth-native-economy-run.sm0iucnk/market-result.json`, 같은 경로의 `market-ai-result.json`은 실제 후보 수용, `market-result.json`의 `ai.result`에 지정한 소유 AI 하위 경로의 `run-result.json`은 합산 자원/실제 모델 관측이다.
- 빌드/고유 XML·Python·원본/최종 JAR·실행 로그: `/home/server/hearth-tmp/hearth-market.zgcdvvb5`.

고정 Paper 26.2 build134/Java25·WG7.0.19/WE7.4.4, 기존 승인된 비활성 세계와 PG/모델 아티팩트를 새 소유 경로에 검증 복사했다. 고정 Ollama 0.40.16/Qwen3 4B instruct Q4_K_M, 컨텍스트4096/출력128/스레드2·동시1을 재사용했다. 모델의 API/적재 상태·digest도 기존 백엔드 계약으로 검증한다.

모든 시험은 CPU2/nice10·최초600초/작업550초·준비90초/질의6초·Paper heap1536MiB/RSS2560MiB·AI RSS4GiB/합산RSS6GiB/GPU5GiB·호스트 가용6GiB·localhost·Paper 최대2/PG1을 유지했다. 최종 AI 시험 최대 관측 AI RSS 약 2,826MiB/합산4,084MiB/GPU3,130MiB, guard 실패 없음. 세 시험 모두 cleanup_errors=[]이며 소유 PID/포트 정리·입력 원본 해시/HOME 보존을 확인했다. 운영 프로세스/환경은 변경하지 않았다.

## 미검증·후속

실제 인증 클라이언트의 상점/개인 거래·월드 이동/접속·다인 동작, 장기 수급/화폐량·유통 효율/밸런스·운영 규모 부하·운영 배포는 수용하지 않았다. 시험의 빠른 모드 판단은 1경제 틱 평가/2회/유지2틱이며 제품 기본 12/2/24와 구분한다. 단위 검사는 일반 정책 경계·오래된 제안·모드 안정화·200틱 수량 보존을 포함한다. 다음은 콘텐츠/운영 수치와 장기 경제 균형 검토이며 실제 시험/배포의 승인 경계를 유지한다.
