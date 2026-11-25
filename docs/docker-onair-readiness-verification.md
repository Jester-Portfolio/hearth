# 경제 멀티서버 온에어 재검토·Docker 사전 검사

2026-11-25. 사용자의 전체 재검토·시험 후 Docker 실행 요청에 따라 현재 경제 멀티서버 플랫폼을 검사했다. **판정은 정식 온에어 보류**다. 최종 Java/자동 회귀와 실제 네트워크 복원 기동은 통과했으나 Docker 데몬 접근이 차단돼 컨테이너 실행은 하지 못했다. 실제 플레이·지속 부하와 운영값 선정도 완료되지 않았다. [구조화 증거](../verification/docker-onair-readiness-result.json), [직전 기능 보완](economy-followup-verification.md)을 따른다.

## 보완한 배포 결함

기존 생성 Compose와 단일 Paper 예제에는 콘솔 입력 유지 설정이 없었다. Hearth의 저장·종료는 비동기 `drain` 완료 확인을 요구하지만, 콘솔 입력을 열지 않으면 해당 명령을 전달할 경로가 없다. Java 서비스에 `stdin_open: true`, `tty: false`를 설정했다. PostgreSQL에는 콘솔 입력을 열지 않는다. 기존 비root·localhost·자원 제한·읽기 전용 루트·인증·수용/불변 입력 검사와 `restart: no`를 유지한다.

[운영 가이드](../infrastructure/network/platform-operations.md#네트워크-배포-후보)에 `docker compose attach --sig-proxy=false`로 경제 서버 콘솔에 접속해 `hearthsurvivaladmin drain` 성공 후 `stop`을 입력하는 절차를 연결했다. 실제 컨테이너의 입력 전달은 미검증이며 SIGTERM만으로 비동기 drain 완료를 주장하지 않는다. [Docker 공식 attach 문서](https://docs.docker.com/reference/cli/docker/compose/attach/)와 [Compose 명세](https://github.com/compose-spec/compose-spec/blob/main/05-services.md)를 기준으로 한다.

## 실행한 검사

| 범위 | 최종 결과 | 확인한 것 |
| --- | --- | --- |
| 전체 Java 빌드·회귀 | PASS, 200클래스 1,490개 | 실패·오류·skip 0. 현재 최종 JAR 생성/정합성 |
| 전체 Python 회귀 | PASS, 고유 122개 | 격리·거래 시험 경로·설정·배포·수용·백업/복구 도구 |
| Compose 변경 후 영향 회귀 | PASS, 33개 | 위 122개의 부분집합. 배포/복구/수용/기동과 Java 콘솔 유지 |
| 실제 네트워크 전체 복원 | PASS, 14개 | 정지 snapshot에서 proxy/lobby/economy/PG를 새 경로로 복원, 최신 JAR로 실제 기동·관측·정상 drain/종료, 원본 행/파일·snapshot 보존 |
| 단일 Paper/네 역할 Compose | PASS, 두 구성 | 설치된 Compose CLI의 구문/정규화와 콘솔 설정. 이미지 주소는 fixture이며 실행 증거가 아님 |
| Docker 이미지·컨테이너 | BLOCKED / NOT_RUN | 현재 계정의 데몬 접근 거부. 빌드·pull·up·컨테이너 저장/재기동/복원 미실행 |

전체 Gradle 명령은 `build stageNetworkRuntime` 한 번으로 실행했다. 같은 Java 소스의 성공 회귀를 반복하지 않았다. 이후 변경은 Python 배포 생성기/Compose와 문서뿐이므로 관련 33개 Python만 다시 검사했다. 전체 Python은 `python3 -m unittest discover -s scripts -p 'test_*.py'`, 영향 회귀는 `test_network_release test_network_recovery test_network_acceptance test_network_start`다.

실제 네트워크 실행은 `/home/server/hearth-tmp/hearth-network-run.8ocnjdok`이며 `verify_network_recovery.py`의 기존 EULA·CPU2/nice10·전체600초/작업550초·Paper2/PG1/Proxy1·합산RSS6GiB·localhost 한도를 유지했다. 99.840초, 합산 RSS 최대 표본 약2,628.04MiB, 네 프로세스 모두 exit 0·정리 오류 없음이다. 무접속 관측이며 목표 동접 수용량이나 컨테이너 메모리 예산이 아니다. 현재 JAR의 SHA와 원본/로그 SHA는 구조화 증거에 기록했다.

첫 복원 입력은 직전 거래 후속의 축소 fixture였고, 기존 검사가 요구하는 설정 이력 아카이브를 포함하지 않아 **프로세스 기동 전** 거부됐다. `/home/server/hearth-tmp/hearth-network-run.6yq41_ps`의 FAIL/원본은 보존하고, 기존 전체 플랫폼 PASS 원본 `hearth-native-economy-run.w93tiz9k`에서 새 경로를 만들어 최종 통과했다. 검사를 약화하거나 원본에 가짜 이력을 추가하지 않았다. Compose CLI가 `tty: false`를 정규화 출력에서 생략하는 것은 기본값 false로 비교했다.

## Docker 실행 차단과 운영 경계

`docker version`은 `permission denied while trying to connect to the docker API at unix:///var/run/docker.sock`, 기존 권한으로 확인한 `sudo -n docker version`은 `sudo: a password is required`로 종료했다. 계정은 UID/GID1000이며 소켓은 root:docker 0660이다. 그룹/소켓 권한·sudo·기존 데몬/컨테이너·SSH/방화벽·패키지를 변경하지 않았다. 설치된 rootless 도구도 읽기 전용으로 확인했으나 위임된 controller는 memory/pids뿐이다. 기존 CPU 제한을 낮춰 우회하거나 새 데몬을 설치/기동하지 않았다. [Docker rootless 자원 제한](https://docs.docker.com/engine/security/rootless/tips/#limiting-resources)의 조건과 구별한다.

정상 종료한 새 복원본으로 현재 생성기의 별도 private `docker-review-candidate`를 만들고 inspect/Compose 구문을 확인했다. **이미지는 검토용 fixture, 수용은 incomplete인 후보**다. 실제 배포 가능한 이미지 선정/수용 완료를 뜻하지 않으며 불리언/인증서/원본 표식을 수정해 기동 검사를 우회하지 않는다. 기존 생성 후보의 과거 수용도 새 실행 범위에 자동 승계하지 않는다.

정식 온에어까지 남는 항목은 다음과 같다.

1. 승인된 Docker 실행 경로에서 실제 고정 Java25/PG 이미지를 검증하고 새 격리 컨테이너로 기동·native 라이브러리·읽기/쓰기 권한·콘솔 drain·종료·재기동·일치 백업 복원을 확인한다. 현재 계정에는 데몬 접근 권한이 없다.
2. 실제 정품 클라이언트의 프록시 접속/서버 이동·인벤토리 거래·사망/리스폰·GUI와 지속 부하를 수용한다. 이번 시험은 Player/이벤트 입력을 합성하지 않았다.
3. 운영 CPU/RAM/힙·디스크/로그·백업 주기/보존/오프사이트·복구 목표와 공개 접속 경로를 선정한다. 현 후보는 Linux host network와 localhost 수신 기준이며 외부 공개를 구성하지 않았다. 시험값을 운영값으로 채택하지 않았다.
4. 준비된 기능의 활성화와 최소 안전 정책을 검토한다. 현재 개발 기본값 비활성/미정 정책 HOLD를 해제한 상태로 간주하지 않는다. 획득처·세부 밸런싱·레벨 디자인은 기존 요청대로 이번 범위에 추가하지 않는다.

검사 결과만으로 정식 운영을 승인하지 않았으며 운영 서비스/데이터는 변경하지 않았다. 기본 기능 구현 상태, 자동 검증 PASS, 컨테이너 수용, 실제 플레이 수용을 별개로 보고한다.
