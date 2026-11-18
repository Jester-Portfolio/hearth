# 일반 생존 3월드·인벤토리 선택 검증

2026-11-18. [ADR-0026](decisions/0026-vanilla-survival-world-selection.md)의 `survival` 실행 경로와 [설정 안내](../infrastructure/network/survival.md)를 구현했다. 현재 경제 Paper 안에 거주·야생·지옥을 두고 `/월드`/`/world` → 가상 27칸 인벤토리 → 고정 안전 지점 이동을 연결한다. 로비는 기존 별도 Paper이며 스토리 추가 개발은 보류한다.

## 구현·검사

- 새로운 일반 생존 경로는 RPG 세션·인벤토리 복원·전투 제한을 등록하지 않는다. 같은 Player의 native 아이템·엔더·경험치·체력을 유지하는 이동 경로이며, 일반 채집/전투/사망/포탈/End 규칙은 Minecraft에 맡긴다.
- UI는 현재 월드 표시·고정 세 버튼·왼쪽 클릭만 허용한다. 취소/하단/드래그/Shift/숫자키·오래된 화면/연결은 거부한다. UI 순수 규칙 3개 PASS, 실제 Paper의 3개 ItemStack 렌더 객체 PASS다.
- 준비 결과는 group·key/UUID/seed·안전 입장점으로 고정한다. 새 준비 opt-in·기존 PG 매핑/개인 파일/미등록 custom 월드 거부·준비 실패 표식과 저장 원본을 보존한다. 저장/설정/안전 경계 7개, 실행 모드 6개·보고서 3개 PASS다.
- 실제 이동 전 준비·현재 연결/원래 위치·고정 목표를 검사한다. 확정 거절만 원래 위치에서 해제하며 불명/시간 초과는 HOLD다. 사망 시 이전 토큰을 폐기하고 원래 native future 완료 전 새 이동을 막는다. 순수 제어 포트 검사 10개 PASS다. 해당 포트는 실제 Player가 아니다.
- 로비 복귀는 같은 인증 연결 동결 → native save API 호출/새 개인 파일 확인 → 기존 서명 요청 순서다. 파일 무변경/예외/경로 링크 거부·원본 보존·atomic replace 영수증을 단위로 확인했다. 실제 Player.saveData→프록시 전환은 미실행이다.
- Python 설정/네트워크 overlay·health 21개 PASS다. `survival_config.py`는 새 0600 파일만 쓰며 기본 생성 비활성이다. 네트워크 생성기는 같은 economy 역할의 survival YAML도 받는다. 기존 경제 설정 지원은 유지한다.

총 **Java 고유 29개·Python 21개 PASS**, Java25 컴파일/JAR PASS다. 처음 통과한 무변경 검사는 반복하지 않았고, 생명 상태 보완 후 영향받은 WorldTravel 10개만 다시 실행했다. 최종 JAR SHA-256은 `c22c6bc0db293b9632a63400830ce156d556b10a86212b419194f836a653148c`다.

## 실제 격리 Paper

| 범위 | 결과·증거 |
| --- | --- |
| 최초 3월드 준비 → 정상 종료 → 두 번째 JVM에서 allow-create=false 복원 | PASS 36.956초, [집계 JSON](../verification/survival-world-result.json). 실제 NORMAL/NORMAL/NETHER·안전 자연 입장점·UUID/seed/입장 바닥·registry 불변, native autosave·RPG listener 부재, 원본 설정/경제 매핑/PG control/기본 메타데이터 보존 확인 |
| 사망/늦은 native 작업 경계 보완 후 최종 JAR의 기존 시험 월드 복원 | PASS 16.85초. 앞 시험을 읽기 전용 원본으로 새 소유 디렉터리에 복사해 기존 세 월드/GUI 객체·동일 UUID/seed/입장 바닥·registry·실제 listener 등록과 정상 종료를 확인. 무관한 초기 생성 두 JVM은 반복하지 않음 |

첫 증거는 `/home/server/hearth-tmp/hearth-survival-run.visv0e2q/survival-result.json`, 최종 증거는 `/home/server/hearth-tmp/hearth-survival-run.23gi6af9/final-restore-result.json`이다. 빌드/XML·정리 전 코드별 JAR·최종 복원 실행문은 `/home/server/hearth-tmp/hearth-survival.pug51cqs`에 보존한다. 각각 소유 PID/포트 정상 종료·cleanup_errors=[]다. 초기 두 JVM은 최종 생명 상태 보완 전 JAR이며 집계 JSON에 두 해시를 구분했다. 월드 준비/저장 계약은 보완에서 바꾸지 않았다.

공식 고정 Paper26.2 build134/Java25·WE7.4.4/WG7.0.19를 사용했다. 완료한 비활성 S4-11에서 공식 런타임·기본 차원만 읽기 전용 복사했으며 기존 개인 파일/PG/Hearth 경제 매핑은 시험에 채택하지 않았다. 새 야생만 표준 FLAT 생성기로 비용을 제한했고 일반 설정 기본값은 NORMAL이다. CPU2/nice10·600초/작업550초·Paper heap1536MiB/RSS2560MiB·합산RSS6GiB·호스트 가용6GiB·localhost/소유 경계를 유지했다. 운영 서비스/원본/SSH/방화벽/터널 변경은 없다.

## 남은 범위

실제 정품 계정의 메뉴 클릭·세 월드 teleport·바닐라 전투/사망/아이템·엔더/경험치 유지·지옥 안전 입장·포탈·생명 변경/재접속·네트워크 복귀는 미검증이다. GUI 객체/순수 포트/콘솔 PASS로 이를 대체하지 않는다. 실제 클라이언트 시험은 별도 요청 전 보류다.

이번 모드는 기존 PG 화폐/상점/생산을 자동 채택하지 않는다. 해당 일반 생존 경제 연결·거주지 소유/보호·야생 리셋 정책·운영 규모/배포·서버별 분산 인벤토리·기존 데이터 이전은 후속 범위다. 기존 economy 모드와 PG 데이터는 보존한다.
