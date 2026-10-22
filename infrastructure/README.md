# 인프라

Hearth 전용 배포, 영속 저장소, 백업·복구와 모니터링 설계를 위한 공간입니다. [ADR-0002](../docs/decisions/0002-stack-and-world-policies.md)에 따라 Docker Compose 배포와 기존 Zabbix 활용을 확정했습니다. PostgreSQL을 진행 상태 저장소로 사용하며 이미지·볼륨·네트워크·DB 배치·관측 항목·백업 세부 구성은 미정입니다.

현재 실행 가능한 배포 설정은 없습니다. 기존 Docker, Zabbix, Cloudflare Tunnel 및 시스템 설정은 변경하지 않습니다. 향후 관련 구성을 먼저 확인하고, 중요한 운영 변경에는 영향·검증·롤백 계획과 명시적 적용 승인을 준비합니다.
