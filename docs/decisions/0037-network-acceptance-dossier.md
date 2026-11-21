# ADR-0037: 검토 범위에 연결된 네트워크 수용 기록

- 날짜: 2026-11-21
- 상태: 승인된 후속 범위의 구현 선택. 운영 미적용.
- 관련: [ADR-0036](0036-network-snapshot-and-launch-binding.md), [검증 결과](../network-acceptance-verification.md), [사용법](../../infrastructure/network/network-acceptance.md).

운영 후보의 네 수용 불리언만으로 완료를 선언하지 않는다. 검토 입력/JAR·경로·이미지/예산·백업 정책·기동 어댑터·경제 바인딩을 하나의 scope로 만들고, 운영자가 보고한 실제 클라이언트·부하·복원·릴리스 회귀 결과와 원본 증거 SHA를 CAS 저널에 묶는다. 증거 무결성 검사는 시험 사실의 자동 인증이나 운영 승인이 아니다. 미실행·실패가 있으면 완료 후보를 만들지 않는다.

롤백은 외부 영수증으로 전체 네트워크 snapshot을 검증하고 network UUID·경제 realm/group을 대조한다. 완료 후보 format3는 저널 head·scope·증거 SHA를 Java/PG 기동 경로에 연결한다. 새 실패/미확정 기록으로 head가 달라지면 기존 후보의 새 기동을 차단하되 실행 중인 서비스를 자동 중단하지 않는다. raw 증거는 private에 보존하고 head만 읽기 전용 마운트한다.

저널 중단은 pending 의도·불변 event·원자 head 공개와 명시적 재개로 처리한다. 과거 후보·snapshot은 역사 검증을 유지하며, 복원 뒤에는 경로/수용을 초기화해 새 범위에서 다시 검토한다. 기존 서비스·DB·게임 저장 형식·밸런스는 변경하지 않는다.
