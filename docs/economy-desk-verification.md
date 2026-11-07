# 다품목 상점·거래 기록·명시적 HOLD 정리

확인일: 2026-11-07. 기준: `1e3e362`. 이전 경제 계산·두 JVM 기록은 [기존 검증](economy-verification.md)에 보존한다. 이번 결과는 [JSON](../verification/economy-desk-result.json)에 기록한다.

## 변경

- NPC 상점의 상품 목록 → 선택 상품 구매/판매 → 본인 거래 기록 → 목록 복귀를 연결했다. `merchant-goods`와 상품 이름을 콘텐츠에서 읽으며 기존 `merchant-good` 단일 설정도 지원한다. 공개 fixture는 철괴·철광석 두 상품이다.
- 표시 가격·상품명/페이지 데이터는 `EconomyShop`으로 분리했다. 목록은 한 DB 작업에서 해당 지역의 취급 상품을 읽는다. 거래는 기존 최신 41슬롯 저장·CAS 경계를 그대로 사용한다.
- 기록은 소유 계정별 revision 내림차순·최대 20개 keyset 조회다. 다른 계정/realm 및 인벤토리 payload를 표시 DTO에 포함하지 않는다. 조회 오류를 빈 목록으로 바꾸지 않는다.
- E001·스토리 V001~V008은 보존했다. E002는 마이그레이션 버전 제약 확장과 `(realm_id, player_uuid, inventory_revision DESC)` 조회 인덱스만 추가한다. 기존 계정/거래 행은 변환하지 않는다.
- 거래 실패 후 요청·원본/예상 payload를 제한된 메모리에 보존하고, 같은 요청 영수증과 현재 DB revision/payload를 비교한다. 복구는 아이템/잔액 쓰기나 거래 재실행이 아니라 **오프라인·저장 정리가 끝난 HOLD 임대의 명시적 폐기**다. 이후 새 세션이 DB 값을 복원한다.
- 영수증과 예상 결과가 일치하면 `COMMITTED_RELOAD`, 영수증 없이 원래 snapshot과 일치하면 `NOT_COMMITTED_RELOAD`다. 더 최신 revision·다른 payload 등은 `REVIEW_REQUIRED`로 남긴다. 온라인·dirty·in-flight·교체된 임대·중복 요청·종료 중 요청은 정리하지 않는다.
- 이전 실패 future/오류는 보존한다. 월드 `ERROR_HOLD`/`RECOVERY_REQUIRED`를 해제하지 않으며 이전 세션에 값을 적용하지 않는다. 최초 접속/화폐/운영 정책을 추가하지 않았다.

## 실제 검사

| 구분 | 결과 | 범위 |
| --- | --- | --- |
| Gradle build/test/check | PASS | 새 상점/확인 판단 7개, 인벤토리 저장 8개, 세션 22개: 37개 고유 사례. 누락 import 수정 뒤 통과, 종료 경계 보완 후 영향받은 세션 검사만 재실행 |
| 콘텐츠/표시 | PASS | 복수 상품·단일 설정 호환, 잘못된 연결/중복 거부, 수량별 선택 상품 가격, 페이지 불변/경계, 영수증/revision/payload 판단 |
| 실제 격리 DB | PASS | E001의 기존 계정·영수증을 만든 뒤 E002 적용/재조회, 새 Store 인스턴스 재검증, 두 상품 구매/판매·재고/잔액/아이템 보존 |
| 거래 기록 | PASS | 23건의 20+3 페이지·순서/중복 방지, 개인/realm 격리, 조회 전후 계정/revision 불변, 빈 조회와 없는 계정 오류 구분 |
| 제어된 실패/정리 | PASS | 실제 SQL 롤백·commit 후 불명 응답 주입, 잘못된 소유자/revision·온라인·중복 확인 거부, 명시적 정리 후 새 내부 세션 복원·재차감/재지급 없음 |
| 데이터 보존 | PASS | 더 최신 계정은 과거 영수증으로 덮지 않음, 다른 realm 불변, 실패 증거·dirty·월드 HOLD·종료 경계의 단위 검사 |
| Paper 연결 | PASS | 실제 54슬롯 Inventory 객체의 두 상품/이름·선택 상품 구매/판매 버튼, 기존 41슬롯 불변. 내부 검사 총 62개 |
| 종료/최종 JAR | PASS | 월드 저장/언로드·DB/worker 종료, 소유 프로세스/포트 반환, 설치/실행/빌드 JAR 일치 |

새 `/tmp/hearth-s4-11.ByHQukNT`에서 `verify-lifecycle.py --sessions --economy --economy-desk` 한 묶음을 실행했다. 기존 90일 모의 계산과 두 JVM 전체 시험은 반복하지 않았다. 이번 변경은 조회 인덱스와 세션 정리/표시이며 계정 payload·거래 저장 형식은 그대로다. 복원은 같은 DB의 새 내부 세션에서 확인했고 **두 번째 JVM은 미실행**이다.

## 자원

- 전체 **40.283초**, Paper 한 JVM·격리 PostgreSQL 한 개. Paper 시작 13.730초, 종료 3.747초.
- Paper 관측 RSS 최고 **1,241,732KiB**, CPU 누적 **71.91초**. 호스트 가용 RAM 최저 약 **11.72GiB**.
- DB 시험 부모 RSS 최고 221,188KiB·CPU 2.28초이며 PostgreSQL 전체 자식 합계 최고치로 해석하지 않는다.
- 기존 localhost·Xmx1,536MiB·CPU2/nice+10·RSS2.5GiB 중단·가용6GiB·디스크4GiB·600초 한도를 유지했다. 운영 서비스·DB·월드·이전 증거는 변경하지 않았다.
- 최종 JAR SHA-256: `7a5120d7116b416c322010419ade5d9795725e338d65e6f10953e81022176c07`.

## 관리 명령과 한계

모든 명령은 경제 모듈이 준비된 **격리 개발 모드의 서버 콘솔 전용**이며 대상 realm은 해당 경제 fixture로 고정한다.

```text
hearthverify economy-history <player_uuid> [이전_revision]
hearthverify economy-pending <player_uuid>
hearthverify economy-inspect <player_uuid> <request_uuid>
hearthverify economy-recover <player_uuid> <request_uuid> <확인한_DB_revision>
```

먼저 기록/보류 요청을 확인하고 `inspect` 결과를 검토한다. 대상의 연결 종료와 안전한 확인 결과를 전제로 `recover`를 명시적으로 실행한다. 이 명령은 다시 DB를 확인하며 값이 바뀌면 거부한다. `REVIEW_REQUIRED`나 월드 HOLD는 강제로 해제하지 않는다. 기록이 없다는 이유로 거래를 재실행하지 않는다.

보류 ticket은 해당 JVM 메모리의 제한된 증거다. 재시작 후 ticket이 없으면 명령으로 임의 복구하지 않는다. DB 기록·로그·기존 장애 대응을 별도 점검한다. 운영용 감사/권한/백업 절차, 장기 기록 보관, 실제 네트워크 응답 유실은 이번 범위에서 검증하지 않았다.

실제 인증 플레이어의 NPC 클릭·GUI 페이지/드래그/숫자키·동시 행동·접속 종료/재접속 화면은 미검증이다. 제어 Port·Inventory 객체 검사를 실제 플레이 성공으로 표현하지 않는다. 일반 서버 기본 비활성, E1/E2·전체 S4 진행 중을 유지한다.
