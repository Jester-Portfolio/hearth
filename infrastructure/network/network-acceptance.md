# 네트워크 수용 기록과 완료 후보

[ADR-0037](../../docs/decisions/0037-network-acceptance-dossier.md), [검증 결과](../../docs/network-acceptance-verification.md)를 따른다. 이 도구는 운영자가 보고한 결과와 원본 증거를 같은 검토 범위에 묶는다. 시험을 실행하거나 진위를 자동 인증하지 않으며, 운영 승인도 대신하지 않는다. 실제 클라이언트·장기 부하는 별도 요청/허용 범위에서 수행한다.

## 준비와 기록

[배포 검토 양식](release-review.example.json)의 acceptance는 최초에는 모두 false로 둔다. 네 역할의 입력/JAR·경로·인증 설정·이미지/예산·백업 정책, 생성기 기동 어댑터와 native 경제 realm/group 바인딩을 scope SHA-256에 연결한다. 완성되지 않은 경제 데이터로도 미완료 후보를 검토할 수 있지만 realm/group 없이는 완료 후보를 만들 수 없다. 같은 입력의 출력 폴더 변경만으로 scope는 바뀌지 않는다.

```sh
python3 scripts/network_release.py plan /PRIVATE/review.json --output /PRIVATE/candidate
python3 scripts/network_acceptance.py init --candidate /PRIVATE/candidate/release.json --output /PRIVATE/dossier
python3 scripts/network_acceptance.py status --dossier /PRIVATE/dossier
```

상태의 head_sha256을 다음 변경의 `--expected-head`에 사용한다. 변경마다 새 head를 받는다. 같은 head의 동시 변경은 하나만 확정된다. 아래 기록은 실제로 수행한 해당 검토 범위의 시험 결과일 때만 PASS로 입력한다. reference는 공백 없는 짧은 영문/숫자·`_./-` 식별자다. 원본 파일의 SHA-256은 별도로 확인해 전달한다.

```sh
python3 scripts/network_acceptance.py record --dossier /PRIVATE/dossier \
  --expected-head HEAD_SHA256 --kind client --outcome PASS \
  --method authenticated-client --reference client-session-1 \
  --artifact /PRIVATE/client-result.json --artifact-sha256 ARTIFACT_SHA256
```

| kind | method | 필요한 결과 |
| --- | --- | --- |
| client | authenticated-client | 정품 클라이언트의 실제 접속/이동/클릭 수용 |
| load | real-player-load | 선정한 실제 동접/부하·지속 관측 수용 |
| restore | restored-runtime | 같은 검토 범위의 복원본 실행 수용 |
| release_regression | release-regression | 해당 릴리스의 필요한 전체 회귀 |

outcome은 PASS/FAIL/NOT_RUN이다. 각 종류의 최신 기록이 유효하며 과거 기록을 삭제하지 않는다. JSON의 outcome이 PASS가 아니거나 client/load 증거의 actual_client가 NOT_RUN이면 PASS 기록을 거부한다. 따라서 `platform_soak.py`의 무접속 관측만으로 실제 클라이언트/부하를 통과시킬 수 없다. 이는 일반적인 증거 진위 판별기가 아니며 입력한 운영자 보고의 책임을 대체하지 않는다. 증거는 기록당16MiB·저널256개 상한이며 private 디렉터리에 SHA별 복사한다. 상태에는 자격 증명 원문을 넣지 않는다.

## 짝이 맞는 롤백과 완료 후보

이전 JAR/설정·proxy/두 Paper/전체 PG의 [정지 snapshot](network-recovery.md)을 별도로 준비한다. 외부에 보관한 recovery SHA로 bundle 전체를 검증하고 snapshot payload의 network UUID·경제 realm/group이 현재와 같은지 검사한다. 과거 format1/2 검토 자료도 원래 경로에 의존하지 않고 검증한다.

```sh
python3 scripts/network_acceptance.py rollback --dossier /PRIVATE/dossier \
  --expected-head HEAD_SHA256 --snapshot /PRIVATE/rollback-snapshot \
  --recovery-sha256 TRUSTED_RECOVERY_SHA256
python3 scripts/network_acceptance.py seal --dossier /PRIVATE/dossier \
  --expected-head NEW_HEAD_SHA256 --output /PRIVATE/fresh-complete-candidate
python3 scripts/network_release.py inspect /PRIVATE/fresh-complete-candidate/release.json
```

seal은 네 종류의 최신 PASS·같은 scope·검증한 롤백을 모두 요구하고 롤백을 다시 확인한다. all-true 불리언 입력만으로는 format4 완료 후보를 생성할 수 없다. 원래 미완료 후보·기록은 유지한다. 완료 후보를 새 저널의 원본으로 사용하지 않는다. 입력/JAR/설정/예산/경로/경제 바인딩/기동 코드가 바뀌면 새 미완료 후보와 저널을 만든다. 과거 기록을 자동 승계하지 않는다.

format4는 acceptance.properties의 scope·증거 SHA·head를 네 역할의 기동 검사에 묶는다. Compose에는 raw 증거 대신 공개 가능한 state 디렉터리만 읽기 전용 마운트한다. 파일 교체를 관측할 수 있도록 head 파일 자체가 아닌 디렉터리를 마운트한다. 이후 FAIL/NOT_RUN 또는 추가 기록·미확정 기록으로 head가 바뀌면 기존 완료 후보의 inspect와 새 기동이 거부된다. 다시 PASS가 모여도 새 head로 완료 후보를 만들어야 한다. 이미 실행 중인 서비스는 자동 중단하지 않는다.

별도 운영 승인·비root·JDK25·입력 해시·정지/실행 표식과 세션 잠금 검사는 그대로 필요하다. 과거 format1/2/3 후보의 정적 검증/역사 복구는 유지하지만 신규 후보/복원 후보는 format4다. 실제 복원은 새 경로이고 수용 표시를 모두 false로 초기화한다. 완료 후보를 snapshot에 넣을 때는 해당 시점의 저널/증거도 private assets에 보존한다. 역사 자료가 live 저널로 자동 재사용되지는 않는다.

## 기록 도중 중단

기록 의도를 state/pending.json에 먼저 저장한 뒤 증거·event·head를 fsync한다. pending이 있으면 완료 판정/기동은 거부한다. event까지 저장됐거나 head만 확정된 중단은 현재 head를 확인하고 `resume`으로 마무리한다.

```sh
python3 scripts/network_acceptance.py resume --dossier /PRIVATE/dossier --expected-head CURRENT_HEAD_SHA256
```

증거/event 저장 전에 중단됐다면 동일 head·동일 입력/증거의 record를 재요청한다. 다른 결과로 pending을 덮어쓰지 않는다. 손상·누락·충돌은 원본을 보존하고 거부하며, 저널 파일이나 pending을 임의 삭제해 완료 상태를 만들지 않는다. 이 도구는 게임 보상/아이템 효과를 재실행하지 않는다.

format4는 [중요 입력 자동 대조](../../docs/platform-hardening-verification.md)의 설정/플러그인 목록을 기동 시 재계산한다. 새 규칙/플러그인 추가와 경제 바인딩 파일 변경도 새 후보 검토가 필요하다.
