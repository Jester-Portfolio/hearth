# ADR-0036: 네트워크 전체 snapshot과 역할별 기동 바인딩

- 날짜: 2026-11-21
- 상태: 후속 운영 준비 구현 선택. 운영 미적용.
- 관련: [ADR-0035](0035-platform-operations-and-retention.md), [검증 기록](../network-recovery-verification.md).

하나의 경제 네트워크를 복구할 때 proxy·lobby·economy·전체 PostgreSQL을 같은 정지 snapshot으로 묶는다. 기존 한 Paper용 backup-v1은 유지하고 network-backup-v1을 추가한다. 실제 복원은 새 경로만 사용하며 원래 역할·파일·UUID/데이터·전달 비밀의 짝을 유지한다. 복원된 경로를 반영한 후보를 새로 만들고 과거 수용 표시를 모두 초기화한다. 원래 소스 경로가 없어도 bundle/외부 영수증으로 역사 snapshot을 검증할 수 있어야 한다.

기동 후보 format2는 PG를 포함한 네 역할에 검토 파일/역할/수용 상태를 바인딩한다. 환경 파일은 Compose dotenv 보간 대신 검토한 원문을 그대로 읽어 전달한다. 프록시는 실제 실행 표식을 소유하며 정상 종료 확인 전 새 실행/백업으로 이전 표식을 덮어쓰지 않는다. 이 결정은 자동 장애 전환·온라인 백업/PITR·운영 재시작·수용 승인 자동화를 추가하지 않는다. 이미지/운영 배치·실제 클라이언트/장기 부하는 별도다.
