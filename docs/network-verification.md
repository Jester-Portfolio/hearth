# 로비·경제 네트워크 구현/격리 검증

2026-11-18. 사용자 요청으로 스토리 추가 개발을 보류하고 [ADR-0025](decisions/0025-lobby-economy-network.md)의 로비·경제 멀티서버 네트워크를 구현했다. 기존 스토리 데이터/코드/미검증과 B안은 보존한다. 운영 서비스/컨테이너/SSH/방화벽·기존 터널은 변경하지 않았다.

## 구현

같은 Hearth JAR에 Paper와 Velocity 진입점을 분리했다. Velocity 4.3.1 build45와 기존 Paper26.2/Java25/WG/WE를 사용한다. 프록시는 정품 online 인증/modern 전달과 초기 로비 접속, 고정 로비↔경제 전환을 담당하고 PG/인벤토리를 소유하지 않는다. 로비는 기존 월드 UUID·안전 입장/WorldGuard 구역을 확인한 뒤 `/hearthserver` 가상 선택창을 제공한다. 별도 소지품/화폐를 지급하지 않고, 기존 로비 인벤토리/엔더/커서의 비어 있지 않은 원본은 보존하며 입장을 보류한다.

경제의 `/lobby`/선택창은 현재 세션·거래/채집/이동 상태를 확인하고 동결한 뒤 기존 PlayerSessions 장벽으로 최신 PG 저장을 확정한다. 그 뒤에만 독립 relay 비밀의 HMAC/버전/도메인·network ID·요청 nonce·유효 시간·고정 출발/목적을 담아 프록시에 요청한다. 소스/대상 주소·사용자 UUID·슬롯/잔액은 메시지 입력으로 받지 않는다. 프록시는 실제 인증 연결/백엔드 출처와 같은 연결의 단일 전환 허가를 확인하므로 기본 `/server`·다른 플러그인·호환 메시지 경로로 저장을 우회하지 못한다. 신뢰한 설치 플러그인을 악성 운영 코드로부터 격리한다는 의미는 아니다.

backend offline-mode만으로 인증을 허용하지 않는다. 실제 로드된 Paper modern enabled/online-mode/비밀, legacy/PROXY 비활성·localhost/whitelist를 확인한다. 직결 경제의 온라인 인증은 유지한다. 프록시는 초기 구성/대상 객체를 고정하고 reload/구성 교체에서 입장/전환을 HOLD한다. 알려진 대상 거절만 기한 안의 같은 소스 세션을 다시 허용하며 저장 실패/불명·전송 오류/시간 초과는 원본/동결을 보존한다. 로그아웃/세대 교체·중복/재전송은 새 세션에 적용하지 않는다.

선택창은 일반 저장 컨테이너의 아이템 조작/미리보기에서 분리했다. 현재 화면의 허가된 왼쪽 버튼만 요청하고 취소된 입력·Shift/숫자키/드래그·다른 슬롯/화면은 전환하지 않는다. `runtime_health.py`는 로비 집계를 읽을 수 있다. 새 설정 생성기는 기존 정상 경제 정의를 유지하고 새 private 폴더에 역할별 설정·서로 다른 비밀·해시 목록만 생성한다. 기존 월드/DB·서비스를 덮어쓰거나 새 EULA/월드를 생성하지 않는다. [구성/사용법](../infrastructure/network/README.md)을 따른다.

## 검사

- Java **34개 PASS**: 네트워크/인증/메시지·재전송·연결/저장 경계 24개와 기존 경제 설정 6개를 묶어 실행했다. 후속 GUI 경계 4개만 별도 실행했다. main/Velocity/integration fixture 컴파일·단일 JAR 두 진입점/외부 API 미포함 확인 PASS.
- Python **7개 PASS**: 새 private 설정/기존 출력·정의 보존/정상 모드·포트/경로/비밀 경계 6개, 새 로비 관측 모드 1개. 이전 로그 관측 검사 전체를 반복하지 않았다.
- **최종 같은 JAR**의 Velocity 1개·실제 Paper 2개(로비/경제)·새 복사 PG 1개 동시 기동/격리 검사 **PASS**, `/home/server/hearth-tmp/hearth-network-run.4jen__de`, **38.56초·13개 확인**, `cleanup_errors=[]`.
- 프록시의 실제 서버 목록 status ping과 실제 두 역할 기동, Paper에 실제 로드된 modern 설정 검증, 다른 forwarding 비밀과 직결 fallback의 거부를 확인했다. status ping은 로그인/사용자 행동이 아니다.
- 실제 경제 월드/PG의 제어 Port에서 source 동결→최신 슬롯 저장 확정→프록시 요청 해제 순서를 확인했다. 확정 대상 거절/퇴장 뒤 저장 원본·revision을 유지했다. 실제 PG UPDATE `23514` 실패는 원본 payload/revision과 요청 미전송을 보존했고 같은 원본 revision의 두 UPDATE 경합은 한 번만 성공했다. 이 Port는 인증 Player·실제 이동/클릭을 만들지 않는다.
- 기존 통과 비활성 격리 자료를 새 경로에 복사하고 시험 복사본의 경제 절대 매핑 경로를 명시적으로 연결했다. 원본의 설정/매핑/월드 메타데이터·PG control 파일 해시를 보존했다. 새 PG의 모든 기존 스토리/회원 테이블 행 수·내용 해시도 네트워크 처리 전후 일치했다. 운영/기존 소스 DB에 연결하지 않았다.
- 실제 Velocity `/velocity reload` 이벤트에서 네트워크 입장/이동 게이트가 HOLD로 닫혔다. 정상 경제 drain·두 Paper/프록시/PG 종료와 프로세스·포트 반환을 확인했다.

각 최종 시험은 **하나의 600초 최초 시계/550초 작업**, 최대 Paper2/PG1/프록시1, 합산 RSS 중단6GiB·Paper heap1536MiB/RSS2560MiB·프록시 heap256MiB/RSS512MiB·호스트 가용6GiB·nice10을 유지했다. 마지막 두 프로필은 프로세스 트리의 CPU affinity도 기존 허용 CPU 중 최대2개로 좁혔다. 최종 합산 RSS 최대 표본은 **2163.63MiB(약2.11GiB)**다. 자원 표본을 운영 동접 수용량/상시 예산으로 환산하지 않는다.

## 실패 원본과 수정 범위

첫 `/home/server/hearth-tmp/hearth-network-run.wcp3hzye`는 경제 기동이 HOLD여서 **FAIL**이었다. 프록시/로비는 준비됐으나 경제 내부 생성기에도 직결 전용 인증 검사가 남아 있었다. 검토에서 새 복사본의 절대 경로 매핑도 확인해, 원본을 그대로 둔 새 시험 준비 경계에서만 경로를 명시했다. 알려진 실패 경제 역할은 정상 종료를 요청하고 원시 로그/JAR/결과를 보존했다.

수정 후 `hearth-network-run.8ia1hnkn`은 **38.968초·10개 확인 PASS**였다. 이후 가상 GUI의 일반 컨테이너 처리 분리·실제 인증 비밀/fallback 거부·프록시 reload/대상 교체 방어를 최종 코드에 통합해 위 새 프로필로 검사했다. 같은 성공 코드를 이유 없이 반복하거나 원래 FAIL을 덮어쓰지 않았다. 기존 스토리/거래/채집의 전체 SQL 행렬은 반복하지 않았다.

최종 JAR `/home/server/hearth-tmp/hearth-network.7fycjhxn/Hearth-final-safe.jar`, SHA-256 `70beb6114ae8feca20780900bfc44a739ee1325ed675dcf4be06d4c3ad1d9843`. 설치한 세 역할 JAR도 일치했다. [결과 JSON](../verification/network-result.json)에 원시/단위/실패 증거와 상한을 보존한다.

**미검증:** 실제 정품 프로필의 프록시 인증/초기 입장·GUI 클릭/서버 전환/끊김·재접속/코옵, 고정 Paper/Velocity 외 조합·실운영 규모/장기 부하·이미지/Docker 기동/운영 배포. 실제 Player/인증을 우회해 성공으로 처리하지 않았다. 스토리 개발 재개·경제 다중 writer/샤딩/HA·PG/월드 자동 이관·운영 failover를 구현/승인한 것은 아니다. 실제 시험과 운영 전환은 기존 별도 요청/승인 경계를 유지한다.
