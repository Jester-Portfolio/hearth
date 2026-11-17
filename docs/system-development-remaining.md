# 시스템 개발 잔여 경계

기준 2026-11-18. 실제 플레이 시험·레벨 디자인/실제 서사·보상 수치/밸런스는 이번 요청에서 제외한다. 기능 구현, 자동 기술 검증, 품질 수용과 운영 적용을 구분한다. 아래 구현 반영이 전체 게임/S4~S7 완료 선언은 아니다.

| 섹터 | 현재 반영한 시스템 | 제외 항목 밖에서 남은 것 |
| --- | --- | --- |
| 스토리 정상 실행 | [준비/불변 등록](story-preparation-verification.md), [정상 기동](story-runtime-verification.md), [시설/채집·호위/전투·거점 연결](story-modules-verification.md), [확정 효과·읽기 전용 복구](story-operations-verification.md), [전체 저장 공동체 명단](story-community-verification.md) | 콘텐츠 변경은 호환 HOLD를 유지한다. 자동 이관/기존 진행 재채택은 승인 정책이 아니며 새로 구현하지 않는다 |
| S4 기능·필드 전투 | [기능별 기존 구현/미검증](s4-remaining-work.md), [필드에 인던 고정 명단·사망/관전/복귀 연결](field-combat-verification.md) | **오프라인 참가자 승리 목표 반영은 미지원**이며 이번에 정책을 질문했다. 활성 전투 무손실 자동 재현은 승인 범위가 아니다. 실제 인증 수용과 콘텐츠 범위는 제외 항목으로 남긴다 |
| 채집 물리 배치 | 스토리/경제의 기존 월드 안 진행별 전용 구역, 공유 세대/CAS·안내/퇴장·초기화/복원 | ADR-0013의 독립 물리 채집 월드 채택은 미정이다. 기존 전용 구역 확정 또는 독립 월드 구현 여부를 질문했다 |
| 개인 정보·NPC 공개 | [지역/세션별 가시성·대화 선택지](npc-views-verification.md), [권한 있는 게임 정보](game-information-verification.md), [최신 자료/이력 격리](s5-grounded-followup-verification.md) | 실제 콘텐츠 전체에 대한 비밀/권한 수용은 별도다. 현재 필터가 없는 것으로 보고 같은 기능을 재구현하지 않는다 |
| 경제 | [전용 정상 모드/PG 잔액·0/PAUSE](economy-runtime-verification.md), [양쪽 확인 상품 거래](economy-peer-trade-verification.md), [정상 채집·독립 저장/초기화](economy-gathering-verification.md), 기존 생산/수급·가격·무역/사건·관리 진단 | 운영 프로세스 배치/자원·보존/복구·기존 콘텐츠/데이터 이전 정책이 미정이다. 현재 데이터는 보존하고 불명/불일치는 HOLD한다 |
| AI 앱/격리 | [일반/게임 목적 분리](s5-knowledge-boundary-verification.md), 과학 노트·정확 계산/비교·자료/후속 대화·접속 캐시, [명시적 준비](s5-ai-preparation-verification.md), [동시 요청 수용 계측](s5-ai-observation-verification.md) | **일반 과학 모델 품질 HOLD**는 해제하지 않았다. 고정 노트/계산 설명의 부분 성공과 구분한다. 일반 정답률·장문/자료 인용 품질의 수용 범위/판정, 필요하면 모델/프롬프트 추가 개선이 남는다 |
| 성능/운영 AI | [목표와 기존 관측](s5-performance-acceptance-plan.md), [한 시계의 Paper/PG/AI 합동 관측](s5-joint-observation-verification.md) | 운영 CPU/RAM/GPU 예산·질문 빈도/종류·응답/게임 성능 SLO·지속 부하 시간/실패율 기준이 미정이다. 격리 상한/네 논리 세션을 실제 4명 운영 용량으로 인정하지 않는다 |
| 백업·관측·배포 | [정지 전체 백업/새 경로 복원](offline-backup-verification.md), [누락 PG 연결의 재초기화 금지·오프라인 감사/기동 manifest·집계 관측·배포 후보](runtime-operations-verification.md) | 이미지 digest/UID·토폴로지·운영 예산/보존/오프사이트/RPO/RTO 결정, 실제 이미지 빌드/기동·Zabbix 수집/권한·운영 복구/전환 수용과 **별도 운영 승인**이 필요하다 |

현재 확정 정책에 따라 독립적으로 진행할 수 있던 위 기능의 구현·관련 자동 검사·문서·Git 반영은 수행했다. 오프라인 던전 목표와 채집 물리 배치는 위의 미지원/미정 범위를 보존하고 사용자 답변 전 해당 정책에 의존한 변경을 하지 않는다. 미정 정책을 임의로 확정하거나 실제 시험/운영 변경으로 넘어가서 잔여를 0으로 만들지 않는다. 새 기능을 만들거나 같은 성공 검사를 반복하는 일도 다음 목표로 삼지 않는다.

다음 실행 순서는 남은 품질/운영 수용 기준 결정 → 그 기준에서 필요한 최종 AI/부하·이미지/관측 검증 → 운영 전환 계획 검토/별도 승인이다. 승인 없는 Docker/Zabbix/서비스 변경과 실제 클라이언트 시험은 진행하지 않는다. 판단과 현재 사실의 정본은 [PROJECT_STATUS](PROJECT_STATUS.md), 실행 구성은 [운영 검토용 문서](../infrastructure/deployment/README.md)를 따른다.
