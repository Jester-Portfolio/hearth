# 인프라

Docker Compose 배포와 기존 Zabbix 활용은 [ADR-0002](../docs/decisions/0002-stack-and-world-policies.md)를 따른다. PostgreSQL은 진행 상태 저장소다. 운영 이미지·볼륨·네트워크·DB 배치·상시 자원/관측 기준은 아직 확정하지 않았다.

[정지 백업·새 경로 복원과 관리자 대응](backup/README.md)을 준비했다. 도구는 정상 종료한 Paper/PG의 전체 묶음을 해시로 확인하고 새 경로로만 복원하며 서비스 중단/기동·운영 덮어쓰기·자동 보정을 하지 않는다. 검증 범위는 전용 기록을 따른다.

현재 운영 배포 구성은 적용하지 않았다. 기존 Docker·Zabbix·Cloudflare·SSH/방화벽·시스템 설정을 변경하려면 영향·검증·롤백 계획과 별도 명시 승인이 필요하다.
