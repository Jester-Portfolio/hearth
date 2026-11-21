# 네트워크 정지 snapshot·복원

[검증/한계](../../docs/network-recovery-verification.md), [기존 후보](platform-operations.md#네트워크-배포-후보)를 함께 사용한다. 프록시·로비·경제·전체 PG와 검토 후보를 하나의 복구 묶음으로 보존한다. 온라인 백업/자동 장애 전환이나 서비스 중단 명령이 아니다.

## 보존과 외부 영수증

기존 검토 후보를 inspect하고 네 역할이 정상 종료된 뒤 별도 private 경로에 생성한다. 서비스 종료/운영 변경은 기존 별도 승인 절차를 따른다.

```sh
python3 scripts/network_recovery.py create \
  --release /PRIVATE/candidate/release.json \
  --destination /PRIVATE/fresh-recovery --offline-confirmed
python3 scripts/network_recovery.py verify \
  --source /PRIVATE/fresh-recovery --recovery-sha256 SAVED_EXTERNAL_SHA256
```

출력 recovery_sha256을 묶음 밖의 신뢰할 수 있는 기록에 보관한다. 이 SHA가 recovery.json → backup manifest → 네 역할/검토 파일을 연결한다. 묶음 안에 있는 SHA를 그대로 신뢰해 검증하지 않는다. 파일에는 전달 비밀/DB 환경 등도 포함되므로 기존 소유자/비공개0700·0600 경계를 사용하고 Git에 올리지 않는다.

전체 Paper·PG와 프록시 설정/비밀을 같은 묶음에 담는다. WorldGuard/Aura·native 인벤토리·PG 계정·인챈트·설정 보관·자원 cold 영역도 포함한다. 실행 표식, PG PID, world session lock, 핵심 native/death 미확정 작업이 있으면 정상 정지 snapshot으로 확정하지 않는다. 부분 디렉터리·원본/표식을 삭제해 우회하지 않는다.

## 새 경로 복원

```sh
python3 scripts/network_recovery.py restore \
  --source /PRIVATE/fresh-recovery --recovery-sha256 SAVED_EXTERNAL_SHA256 \
  --destination /PRIVATE/fresh-restored
python3 scripts/network_release.py inspect /PRIVATE/fresh-restored/candidate/release.json
```

대상은 새 경로여야 한다. 원래 소스가 없어도 snapshot 자체의 원래 역할/파일 해시와 영수증으로 검증한다. 복원은 네 서비스 경로만 새 위치로 바꾸고 파일·UUID·네트워크 ID·전달 비밀·포트·가격/정책 데이터를 보존한다. 원래 후보와 새 후보의 해시를 network-restore.json에 기록하고 **네 수용 표시를 모두 초기화**한다. 복원/정적 검사 성공을 client/load/restore/release 회귀 수용으로 자동 승격하지 않는다. 서비스는 시작하지 않는다.

복원 실행자의 UID/GID와 프로필의 소유자 선택이 달라지면 먼저 별도 검토가 필요하다. 자동 chown이나 root 가정은 없다. 기존 경로·다른 시점의 PG/월드를 섞지 않고 문제가 있으면 부분 복원본과 원본을 함께 보존한다. 임의 비밀 회전·포트 변경은 이 복원의 대상이 아니다.

## 역할별 기동 연결

새 network_release 후보는 Java 세 역할과 PG 모두에 역할별 properties/검토 SHA/수용 상태를 묶는다. Java는 JDK25/비root·원본 입력·역할·실행 표식·세계 잠금을 확인한다. PG도 false 수용 표시나 바뀐 파일이면 postgres 실행 전에 거부한다. PG 이미지에는 POSIX sh/sha256sum와 검토한 버전의 PostgreSQL 도구가 필요하다. 과거 format1/2 후보는 기존 파일 그대로 검사/백업할 수 있으며 복원 뒤에는 현재 format3로 재생성하며 수용 표시는 초기화한다. format3의 [수용 저널](network-acceptance.md)과 증거는 완료 후보의 snapshot assets에 보존하지만 새 복원 후보의 완료 표시로 승계하지 않는다.

runtime.env는 Compose env_file에서 제거했다. 검토한 세 환경값을 실제 진입점에서 원문 그대로 읽어 export하며 $, 따옴표, 공백, #, =를 dotenv 보간하거나 eval하지 않는다. 중복 키·제어 문자/줄바꿈 값·알 수 없는 변수는 거부한다. 비밀 값을 명령 출력이나 영수증에 복사하지 않는다. 프록시는 proxy-runtime.active를 소유하고, 연결자0/정상 종료/동일 파일 확인 뒤에만 정리한다. 남은 표식은 수동 확인 대상으로 보존한다.

실제 이미지/컨테이너/운영 적용·클라이언트·장기 부하 수용과 별도 운영 승인은 계속 필요하다. 정상 기동이 검토 파일을 갱신하면 다음 기동 전에 현재 정지 데이터를 새 후보로 검토한다. snapshot 복원본으로 승인된 격리 수용을 진행하고 실패하면 기존 완전한 짝으로 복귀한다.
