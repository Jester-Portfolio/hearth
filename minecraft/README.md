# Minecraft 영역

- [story/](story/README.md): 싱글 플레이·협동 스토리 월드.
- [economy/](economy/README.md): 경제 중심 멀티플레이 월드.

두 디렉터리는 설계 자료와 향후 비밀 정보가 없는 설정을 위한 공간입니다. Paper 26.2·Java 25·Gradle과 단일 JAR 내부 모듈화를 사용합니다. 진행 상태 저장소는 PostgreSQL이며 실제 데이터 경로·스키마는 미정입니다. 순정 Java Edition을 대상으로 별도 모드를 최소화하고 필요시 서버 리소스팩을 제공합니다. 상세 결정은 [ADR-0002](../docs/decisions/0002-stack-and-world-policies.md)를 따릅니다. 현재 서버 파일이나 실행 설정은 없습니다.
