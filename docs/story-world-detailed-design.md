# 스토리 월드 S3 상세 설계 초안

- 작성·확인일: 2026-10-23. 기준 커밋: `fc438a86d56205c3a60038ce8b8a922a072ccbff`.
- 상태: **S3 진행 중 / 제안**. ADR-0001·0002의 확정 사항을 구체화하는 후보안이며 신규 정책 승인이 아닙니다. 스키마·상태 전이·복구 절차는 미구현·미검증입니다.
- 선행 결과: [S2 빌드/API 검토](stack-build-verification.md). S2 완료는 구현 경로·위험·시험 기준 정리까지이며 런타임 동작 보증이 아닙니다.

## 1. 확정 경계와 모듈 책임 후보

[ADR-0001](decisions/0001-story-world-multiworld.md)의 단일 프로세스 B안, [ADR-0002](decisions/0002-stack-and-world-policies.md)의 Paper 26.2·Java 25·Gradle·단일 JAR 내부 모듈·PostgreSQL·템플릿/콘텐츠 분리·보호/채집 원칙을 유지합니다. 아래부터 확정 원칙을 제외한 구체 모델은 제안입니다.

| 내부 책임 후보 | 소유할 상태·행위 |
| --- | --- |
| 진행·참가자 | run_id, 멤버십·권한, 세션별 현재 컨텍스트 |
| 월드 수명주기 | 템플릿 복제·배치 매핑·로드·저장·언로드·복구 상태 |
| 콘텐츠 | 불변 템플릿/콘텐츠 버전 참조와 정의 ID 해석 |
| 영속 상태 | PostgreSQL 트랜잭션·진행별 DAO·스냅샷·체크포인트 |
| 보호·인벤토리 | 행위별 구역 판정·상태 전환 차단·개인 상태 적용 |
| AI 연결 경계 | 인증된 진행·플레이어 범위의 읽기 DTO 제공. AI 구현 기술은 미정 |

모듈의 실제 패키지·Gradle 멀티프로젝트·연동 라이브러리는 확정하지 않습니다. 월드/플레이어/인벤토리는 서버 스레드에서 캡처·반영하고 DB·파일·AI I/O에는 불변 DTO·바이트만 넘깁니다. event handler에서 DB 응답을 기다리지 않도록 권한·구역·콘텐츠를 버전 고정 캐시로 판정합니다. 필요한 캐시가 없거나 복구 중이면 변경·입장을 차단하는 후보입니다.

## 2. 식별 모델

| 식별자 후보 | 의미·불변식 |
| --- | --- |
| run_id (UUID) | 싱글 또는 코옵의 논리 진행. 파티장·멤버 변경·이전으로 바뀌지 않음 |
| party_id (UUID, nullable) | 코옵 구성 식별. run_id나 월드 UUID와 동일시하지 않음 |
| world_instance_id (UUID) | 해당 진행에 속한 하나의 물리 월드 인스턴스 레코드 |
| logical_role | main, gathering 등 역할. 차원 구성·채집 소유권은 후속 결정 |
| physical_uuid / world_key / directory_ref | 실제 Paper 월드의 UUID·키·허용 루트 하위 경로. 글로벌 UUID·키 충돌 검사 |
| server_id / placement_epoch | 배치 위치와 쓰기 세대. 현재 B안은 한 프로세스지만 A안 이전에 재사용 |
| player_uuid / session_epoch | 계정과 현재 접속/전환 세대. 늦은 DB·AI·이동 응답을 거부하는 기준 |

접근 컨텍스트는 `(mode, scope_id, run_id?, player_uuid, session_epoch, placement_epoch)` 후보로 정의합니다. 클라이언트가 보낸 run_id·월드 이름을 신뢰하지 않고 서버의 참가 관계·배치 매핑과 대조합니다. 물리 월드 ID가 우연히 같더라도 DB 진행 데이터를 선택하는 근거가 되지 않습니다. 하나의 scope는 하나의 데이터 소유권 경계이며 story는 run_id, economy는 후속 결정할 realm 식별자를 쓰는 후보입니다.

## 3. 템플릿·콘텐츠 버전과 최초 복제

- 공간 템플릿은 `(template_id, template_version, checksum, server/data_version)` 불변 manifest로 관리하는 후보입니다. 원본은 플레이 중 쓰지 않는 사전 제작 데이터이며 신규 복제에만 사용합니다.
- 콘텐츠 정의는 `(content_id, content_version)`으로 NPC 배치·대화·퀘스트·안정된 정의 ID를 관리합니다. 템플릿과 별개이며 호환 template 범위·좌표 기준·지원 데이터 버전을 manifest로 연결하는 후보입니다.
- 신규 진행은 사용할 두 버전을 명시적으로 고정합니다. 새 템플릿 추가가 기존 진행의 버전을 자동 변경하지 않습니다. 기존 월드의 신규 지형·건축물 적용 방식은 **미정**이며 덮어쓰기하지 않습니다.
- 기존 콘텐츠 변경은 안정된 quest/NPC ID를 보존하고 이전→새 버전 변환표·검증·백업·되돌리기 조건을 준비한 뒤 적용하는 후보입니다. 호환되지 않는 진행은 구버전을 유지하거나 입장을 막고 검토합니다. 삭제된 정의를 임의 초기화·재보상하지 않습니다.
- 저장소의 콘텐츠 형식(JSON/YAML 등), 버전 게시·배포 도구와 적용 시점은 미정입니다. Paper/Minecraft 데이터 변환의 역방향 호환을 가정하지 않습니다.

최초 생성 후보 절차:

1. DB에서 run·선택 버전·배치 예약을 `PROVISIONING`으로 만들고 operation_id를 고정합니다. 동일 요청은 같은 작업에 합류합니다.
2. I/O 작업에서 허용 루트의 staging 디렉터리에 템플릿을 복제하고 완료 manifest·파일 체크섬을 확인합니다. 경로 탈출·링크·불완전 복사본을 거부합니다. 하드링크 공유 쓰기를 사용하지 않는 후보입니다.
3. 월드 UUID·키·엔티티/NPC 참조의 충돌 및 포털 목적지를 검증합니다. 복제 도구별 식별자 재생성 방법은 S4 검증 과제이며 기존 파일을 추측으로 삭제하지 않습니다.
4. staging 완료를 기록하고 같은 파일시스템의 공개 경로로 전환하는 후보입니다. 디렉터리 전환과 DB 커밋은 원자적이지 않으므로 작업 journal로 재시작 시 중단 지점을 구분합니다.
5. 서버 스레드의 안전한 시점에서 `createWorld`로 로드, 물리 매핑·콘텐츠·진행 데이터 일치 확인 후 준비 완료를 기록합니다. null·예외·기존 다른 월드 반환 시 격리합니다. 입장 허용은 초기화가 모두 성공한 뒤입니다.

## 4. 월드 수명주기 상태 전이 후보

상태는 월드 인스턴스 단위이고 run 전체는 모든 필수 월드·DB 상태가 준비됐을 때만 사용 가능합니다. 아래 전이는 구현·DB에 적용하지 않았습니다.

| 전이 | 사전 조건·처리 | 실패 시 후보 처리 |
| --- | --- | --- |
| PROVISIONING → OFFLINE | 복제·manifest·매핑 완료 | FAILED로 격리, 기존 원본·다른 진행 보존 |
| OFFLINE → LOADING | 참가 권한, 호환 버전, 체크포인트 일치, 단일 소유권 획득 | RECOVERY_REQUIRED; 임의로 새 빈 월드 생성 금지 |
| LOADING → READY | Paper 로드·DB·NPC·보호 초기화 완료 | 입장 차단, 이미 로드한 월드 정리 여부 확인 |
| READY → ACTIVE | 현재 세대에 속한 입장 예약·이동·개인 상태 복원 완료 | 이동 실패면 예약 해제 또는 원래 위치 복원 |
| ACTIVE → IDLE | 참가자·입장 중 요청 0, 마지막 퇴장 처리 완료 | 입장 예약이 생기면 ACTIVE 유지 |
| IDLE → QUIESCING | 유휴 대기 만료, 새 입장 예약 차단, 상태 변경·작업 정지 | 재접속은 대기시킴. 유휴 시간 수치는 미정 |
| QUIESCING → SAVING | 비행 중 DB·보상 작업 정리, 사용자·월드 작업 스냅샷 | 타임아웃이면 ERROR_HOLD, 언로드하지 않음 |
| SAVING → UNLOADING | 아래 일관된 저장/체크포인트 준비 완료 | DB/파일 실패 시 ERROR_HOLD, 입장·변경 차단 |
| UNLOADING → OFFLINE | 안전한 호출 시점의 unloadWorld 성공 확인, 참조·티켓 해제 | false·예외면 실제 로드 상태 유지, 재시도 판단 |
| 임의 상태 → RECOVERY_REQUIRED | 장애·저장 세대 불일치·소유권 불명 | 데이터 보존, 자동 입장 금지, 검증된 복구 절차 사용 |

메인 스레드에서 실행하더라도 월드 틱 중 로드·언로드는 피해야 합니다. `isTickingWorlds()` 검사와 다음 안전한 스케줄 시점으로 미루는 처리가 필요하며 정확한 시점은 런타임 검증 대상입니다. `World.save(true)`의 writer 대기 비용도 측정해야 합니다. 저장 실패를 무시하거나 성공하지 않은 언로드를 OFFLINE으로 기록하지 않습니다.

접속: 플레이어의 허용 진행 선택 → run 입장 예약·session_epoch 갱신 → 필요한 월드 로드에 합류 → DB 개인 상태 로드 → 서버 스레드에서 인벤토리·위치 적용과 이동 → 성공 확인 후 조작 허용 후보입니다. 초기 스폰/로그인 단계에서 안전한 대기 공간 또는 접속 지연이 가능한지는 S4 검증 과제입니다. 초기 스폰이 타 진행 데이터를 노출하지 않아야 합니다.

퇴장: 진행 전환 전에 GUI·커서·거래·아이템 이동을 안정화하고 개인 상태 캡처·저장 완료 후 세션 소유권을 넘깁니다. 연결 종료 이벤트에서 무기한 DB 대기하지 않으며 실패 시 저장 미완료 상태로 잠그고 재접속 복원을 중단하는 후보입니다. 단순 로그아웃은 파티 탈퇴가 아닙니다. 강제 종료에 대비해 주기 저장도 필요하지만 주기·허용 유실 시간은 미정입니다.

이동: `teleportAsync` future를 메인 스레드에서 join/get하지 않습니다. 완료 후 Boolean·예외·세션·배치·참가 권한을 확인합니다. 포털·사망·부활·명령 이동도 같은 진행 입장 경계를 사용합니다. 베드·부활 위치는 run과 논리 월드 역할을 포함하며 타 진행으로 자동 복귀시키지 않습니다.

## 5. PostgreSQL 데이터 범위와 스키마 후보

SQL·DB 서버 실행은 하지 않았습니다. 아래는 물리 DDL이 아닌 키·열·제약 후보입니다. PostgreSQL 세부 버전·마이그레이션 도구·드라이버·풀과 DB 인스턴스/스키마 분리 방식은 미정입니다.

| 테이블 후보 | 핵심 열·키·제약 |
| --- | --- |
| progress_scope | scope_id UUID PK, mode(story/economy), 상태. UNIQUE(scope_id,mode) |
| story_run | run_id UUID PK=scope_id FK, party_id nullable, content_id/version, state, revision bigint, committed_checkpoint_id nullable. scope mode=story는 검증/복합 FK로 강제 |
| party_member | PK(run_id,player_uuid), role, membership_state, membership_version. run FK; 파티 변경은 row version으로 경쟁 감지 |
| world_instance | world_id UUID PK, run_id FK, logical_role, template_id/version, physical_uuid, world_key, directory_ref, server_id, placement_epoch, state. UNIQUE(run_id,world_id), 물리 배치 충돌 제약 |
| player_progress | PK(run_id,player_uuid), revision, last_world_id, 위치·체력·XP·개인 변수. FK(run_id,player_uuid)→party_member; FK(run_id,last_world_id)→world_instance |
| quest_progress | PK(run_id,owner_kind,owner_id,quest_id), content_version, stage, variables jsonb, revision. run 소유는 owner_id=run_id, 개인 소유는 참가자 ID. 소유 유형 검증 필요 |
| npc_relation | PK(run_id,player_uuid,npc_definition_id), 관계 값·revision. 참가자 복합 FK |
| story_branch / npc_state | PK(run_id,definition_id), 선택·생존/활동 상태·revision·content_version. run 공통 상태 |
| inventory_snapshot | PK(scope_id,player_uuid), revision, payload bytea, format_version, minecraft_data_version, checksum, checkpoint_id. 플레이어별이며 파티 공유 상자가 아님 |
| transfer_operation | operation_id UUID PK, player_uuid, source_scope, target_scope, session_epoch, 단계, source_revision, 대상 snapshot revision. 세션당 미완료 전환 하나 |
| reward_ledger | PK(run_id,quest_id,completion_sequence,player_uuid), operation_id UNIQUE, 지급 단계. DB 지급 기록만으로 실제 아이템 exactly-once 보장하지 않음 |
| checkpoint / checkpoint_player | checkpoint_id PK와 run_id·generation·manifest hash·단계; checkpoint_player PK(checkpoint_id,player_uuid), snapshot revision. checkpoint별 DB 상태 snapshot/이력 필요 |
| lifecycle_operation | operation_id PK, run_id/world_id, 종류·전이 전후 상태·epoch·단계·오류. 재시작 복구용 journal |

외래 키는 run_id를 함께 포함해 다른 진행의 월드·참가자를 가리키지 못하게 하는 후보입니다. 예: player_progress의 `(run_id,last_world_id)`는 world_instance의 동일 복합 키만 참조합니다. 단순 world_id FK와 애플리케이션 필터만으로 이 교차 참조를 맡기지 않습니다. [PostgreSQL 제약 문서](https://www.postgresql.org/docs/current/ddl-constraints.html)를 근거로 검토했으며 제약 간 정확한 DDL·마이그레이션은 미시험입니다.

jsonb는 유동적인 콘텐츠 변수에만 사용하고 run·소유자·버전·참조 무결성은 구조화된 열과 제약으로 유지하는 후보입니다. owner_kind별 FK가 복잡하면 quest_progress를 run_quest와 player_quest로 분리하는 대안이 있습니다. 단일 테이블/분리 테이블 선택은 제안 상태입니다.

월드 파일은 블록·청크·컨테이너·엔티티의 물리 상태를 소유합니다. PostgreSQL은 진행·관계·분기·참가자·개인 상태·작업 이력을 소유합니다. 엔티티는 논리 NPC ID를 서버 매핑으로 연결하고 DB의 npc_state에서 재구성하되 중복 생성·삭제 판정은 검증합니다. vanilla playerdata와 DB가 인벤토리를 동시에 독립적인 정본으로 저장하지 않도록 DB snapshot을 정본 후보로 두고 서버 파일의 초기 로드 상태를 안전한 입장 절차에서 교체하는 설계를 검토합니다. 구현 라이브러리와 이벤트 순서는 미정입니다.

진행 유출 방지: 모든 DAO는 서버에서 검증된 scope 컨텍스트를 필수로 받으며 UUID만으로 조회하지 않습니다. 캐시·작업·AI 조회 키에 mode/scope_id와 필요 시 player_uuid·epoch를 넣습니다. RLS는 보조 후보이며 전용 역할·풀 연결 컨텍스트 초기화까지 시험하기 전 격리 보장으로 쓰지 않습니다. 서버 명령·콘텐츠 스크립트·관리 도구도 같은 경계를 통과해야 합니다.

## 6. 코옵·인벤토리 전환 후보

코옵은 같은 run·퀘스트 공통 단계·월드 변화를 공유하지만 각자의 개인 목표·인벤토리·관계를 따로 저장합니다. 싱글 초대는 기존 run에 참가 관계 추가 후보이며 새 월드 복제와 같지 않습니다. 중간 합류의 과거 보상·탈퇴자 자산·파티장 부재·분할/병합·중복 진행 참여는 **정책 제안/미정**입니다. 구성 변경만으로 진행을 합치거나 덮어쓰지 않습니다.

스토리 scope와 경제 scope 인벤토리는 독립 snapshot입니다. 전환 후보는 조작 잠금 → source snapshot DB 확정·전환 PREPARED → target snapshot 로드·이동·적용 → 세션 소유권과 전환 APPLIED 확정 → 조작 해제입니다. 서버 스레드 적용과 DB commit 사이 장애는 자동 원자적이지 않습니다. PREPARED/APPLYING 중 장애 시 source·target을 동시에 활성화하지 않고 복구 검증 전 잠급니다. 아이템 드롭·상자·거래 등 외부 효과가 있었다면 snapshot 복원만으로 되돌리지 않습니다.

보상은 ledger·인벤토리 revision을 하나의 DB 트랜잭션으로 갱신하고 그 revision을 서버에 적용하는 후보입니다. 이미 지급된 아이템의 외부 사용까지 자동 exactly-once라고 주장하지 않습니다. 테스트에서 중복·유실이 남으면 지급·전환 흐름을 재설계합니다. ItemStack 바이트에는 포맷·Minecraft 버전·체크섬을 붙이고 다운그레이드·손상·알 수 없는 포맷은 비우지 않고 복원을 차단합니다. storage·armor·offhand·ender chest·선택 슬롯·cursor/열린 GUI 처리 범위를 명시해야 합니다.

## 7. 저장·비정상 종료 복구 후보

**월드 파일과 DB는 분산 트랜잭션이 아니며 일반 저장만으로 무손실을 보장하지 않습니다.** 두 데이터의 마지막 일관된 지점과 허용 유실 시간(RPO)·복구 시간(RTO)은 미정입니다. 주기 DB snapshot만 저장해 최신 월드 파일에 덮어씌우는 복구는 금지하는 후보입니다.

체크포인트 후보 절차:

1. run 작업·입장을 QUIESCING으로 잠그고 미완료 이동·보상·DB 작업을 정리합니다. 늦은 결과는 epoch로 거부합니다.
2. checkpoint generation을 PREPARING으로 기록하고, 서버 스레드에서 개인·퀘스트·NPC 상태 DTO를 캡처합니다. DB live row와 별개로 이 generation을 복원할 수 있는 snapshot/버전 이력을 저장합니다.
3. 월드 저장 flush·정상 언로드를 수행하고 해당 시점부터 더 쓰지 않는 파일 묶음을 확보합니다. 대용량 복사는 I/O 작업에서 수행합니다. 로드 중 파일의 단순 복사를 일관된 백업으로 간주하지 않습니다. 실제 순서·엔티티 flush·디렉터리 내구성은 검증 대상입니다.
4. 월드 파일·NPC/DB 상태·개인 snapshot의 generation과 체크섬을 manifest로 연결합니다. 파일 fsync/원자적 이름 전환과 DB COMMITTED 표식을 어떻게 조정할지 구현 설계가 필요합니다. 공개 완료 전 일관성 검사가 실패하면 이전 정상 checkpoint를 보존합니다.
5. DB에 checkpoint COMMITTED와 run의 마지막 정상 세대를 갱신합니다. 월드가 이미 언로드됐으면 OFFLINE, 다시 로드할 경우 입장 전에 같은 세대를 확인합니다.

빈번한 유휴 저장마다 전체 복사하는 비용이 클 수 있으므로 일상 저장과 복구용 checkpoint 주기를 분리하는 대안이 있습니다. 그 경우 정상 저장은 성공해도 최신 checkpoint 이후 유실 위험이 남습니다. 증분 snapshot·스토리지 선택·주기는 **제안**이며 측정 없이 채택하지 않습니다.

| 장애 지점 | 후보 복구 동작 |
| --- | --- |
| 템플릿 복제 중 종료 | 미완료 manifest·operation 확인, staging 접근 차단. 기존 원본/진행을 삭제하지 않고 재시도 여부 판정 |
| DB 저장 실패·타임아웃 | 조작·입장을 잠그고 미완료 작업 기록. 재시도는 operation_id로 멱등 처리; 디스크의 성공만으로 저장 완료 선언 금지 |
| 월드 저장·언로드 실패 | 실제 로드 상태와 journal 대조, ERROR_HOLD. 최신 DB만 되돌리거나 프로세스를 강제 종료하지 않음 |
| 파일 준비 후 DB commit 전 종료 | PREPARING 후보를 자동 정상으로 채택하지 않고 manifest·DB snapshot과 대조; 불일치하면 이전 정상 세대 복원 검토 |
| commit 결과가 모호한 연결 종료 | 같은 operation/generation의 DB 최종 상태 조회 후 재시도; 무조건 중복 commit/보상 금지 |
| 서버 비정상 종료 | dirty 상태 감지 → RECOVERY_REQUIRED → 파일·DB 세대 대조. 일치 증거 없으면 입장 차단 후 마지막 일관된 파일+DB 묶음 복구 |
| 손상·버전 미지원·디스크 고갈 | 원본·오류 증거 보존, 자동 빈 월드/빈 인벤토리 생성 금지. 승인된 복구·롤백 절차로 진행 |

복구는 복사본에서 먼저 검사하고 관련 진행만 정지한 상태에서 파일·DB snapshot을 같은 세대로 복원하는 후보입니다. 운영 데이터 덮어쓰기·복원·삭제는 별도 승인이 필요합니다. 백업 매체·보존·용량·오프사이트·정전 내구성은 미정·미검증입니다. 배치 lease 만료만으로 이전 프로세스의 쓰기가 멈췄다고 간주하지 않으며, A안 이전에는 단일 writer fencing·원본 보존·배치 전환·롤백 시험이 추가로 필요합니다.

## 8. 보호와 자원 채집

확정 원칙: 스토리는 전체 보호·지정 구역 건축 허용, 경제는 주요 도시·공용 시설 등 지정 구역 보호·일반 지역 자유도 확대, 주요 자원 수급은 양쪽 전용 채집 인던 중심입니다. 건축 지형 변경과 채집은 별도 정책입니다.

구역 후보 키는 `(scope_id, logical_world_role, region_id, policy_version)`이며 정수 블록 좌표 AABB·허용 행위·권한을 갖습니다. 경계 포함 규칙과 구역 중첩 우선순위는 S3 검토 항목입니다. 기본 후보는 명시적 금지 우선, 불명확한 진행/정책은 변경 거부입니다. 경제 일반 지역도 다른 진행 접근·아이템 복제·시스템 시설 변경을 허용하는 뜻은 아닙니다.

| 행위 축 | 후보 판정·이벤트 | 미검증 위험 |
| --- | --- | --- |
| 직접 건축·철거 | BlockBreak/Place/MultiPlace, 버킷·아이템 상호작용 | 양손·다중 블록·지정 구역 경계에서 일부만 허용되는 경우 |
| 상자·기능 블록·엔티티 | PlayerInteract·InventoryClick/Drag, entity interact·damage, hanging/vehicle 등 | 건축 허용이 상자/시설 사용 권한까지 자동 부여하지 않음 |
| 간접 변경 | explosion·piston extend/retract·fluid·fire·growth·entity block change 등 | 시작점 밖에서 보호 구역으로 유입, 목적지·모든 영향 블록 검사 |
| 외부 코드·명령 | 명령·콘텐츠 변경을 내부 정책 API로 통일 | 이벤트를 발생시키지 않는 외부 플러그인 직접 변경은 이벤트 취소만으로 막을 수 없음 |
| 채집 | 전용 인던과 scope별 채집/반출 허용 규칙 별도 적용 | 생성·초기화·재생성·반출·공유 여부는 미정 |

이벤트 카탈로그는 구현 전 더 구체화해야 하며 위 전체 이벤트의 컴파일·런타임 검증을 했다는 뜻이 아닙니다. S2에서는 대표적인 일부 이벤트 API만 참조 컴파일했습니다. PlayerInteract는 블록 사용·손 아이템 사용을 구분하고 양손 이벤트를 고려합니다. 무조건 모든 인터랙션을 취소하면 정상 퀘스트·스마트폰도 막을 수 있으므로 행위별 정책이 필요합니다.

채집 인던이 개인/파티/공용인지, 독립 서버인지, 입장·귀환·재생성 시점과 아이템 귀속은 **미정**입니다. 후보로 부모 scope의 인벤토리 문맥을 유지할 수 있으나 이는 확정하지 않습니다. 건축 구역에서 부서진 블록의 드롭·광물·작물 정책도 미정이며 건축 허용을 무제한 자원 채집 허용으로 해석하지 않습니다. 인던 수명주기를 설계하기 전 실제 자동 초기화를 만들지 않습니다.

## 9. 후속 테스트와 성공 기준 (모두 미실행)

시험은 별도 승인된 환경·테스트 데이터에서 수행합니다. 실패·미실행을 성공으로 표시하지 않습니다. 횟수·동시 이용량·시간/메모리 한계는 시험 시작 전 확정하며 다음은 기능 성공 기준 후보입니다.

| ID·단계 | 시험 | 성공 기준 |
| --- | --- | --- |
| T1 / S4 | 두 템플릿 복제·로드, 반복 요청 | 서로 다른 물리 식별자·같은 요청 하나의 작업, 원본/기존 월드 보존. null·예외 시 타 진행/빈 월드로 대체 안 함 |
| T2 / S4 | 파티 동시 입장·마지막 퇴장·재접속 경합 | 하나의 로드/저장 소유자, 다른 진행 접근 0건, 상태 준비 전 조작 불가 |
| T3 / S4 | unload false·writer 지연·DB 타임아웃 주입 | 성공 표식 오기록 0건, 원본/이전 checkpoint 보존, 불일치 시 입장 차단 |
| T4 / S4 | 각 checkpoint 단계에서 강제 종료 후 복구 | 파일+DB 같은 세대만 공개, 보상/인벤토리 중복·유실 여부 대조, 정의한 RPO 밖 손실 불허 |
| T5 / S4 | 같은 계정 두 story run·economy 이동, 사망·재접속·전환 중 종료 | scope별 아이템·XP·관계 격리, 보상 재처리 중복 0건, 손상 payload를 빈 상태로 덮어쓰지 않음 |
| T6 / S4 | DB 교차 run 참조·권한 위조·오래된 session 응답 | 복합 FK·DAO가 거부, 타 진행 조회/변경 0건. SQL migration·동시성 시험 필요 |
| T7 / S4 | 구역 내외 건축·다중 블록·유체·피스톤·폭발·엔티티·양손 우회 | 보호 구역의 비허용 변화 0건, 허용 건축/정상 퀘스트 동작, 전체 변경 전후 대조 |
| T8 / S4 | 콘텐츠·템플릿 버전 추가/비호환 migration | 기존 월드 불변, 삭제/변경 ID가 진행 초기화·중복 보상을 유발하지 않음, rollback 세대 확인 |
| T9 / S4 | async DB·I/O 지연과 async 접근 감시 | Bukkit 비동기 위반·메인 스레드 future 대기 0건, 늦은 응답이 새 세션 상태 변경하지 않음 |
| T10 / S5 | AI 검색·캐시·대화·탈퇴 중 지연 응답 | 다른 진행/개인의 비공개 정보 유출 0건. 모델·AI 구현은 미정 |
| T11 / S6 | 활성 진행·플레이어·NPC·AI 중첩 부하 | 사전 합의 CPU·RAM·I/O·MSPT/GC·입장 지연 한계 만족, 반복 언로드 후 지속 참조 누수 없음 |

S3 완료 전에는 아래 정책 제안 검토, 스키마/전이/복구 모순 해소, 콘텐츠 참조·보호 이벤트 목록과 검증 입력/결과 기록 형식까지 준비해야 합니다. 현재는 초안 작성만 완료했고 구현을 시작하지 않았습니다.

## 10. 결정 대기 목록

- 스키마별 owner 구분, 인벤토리 정본/저장 포맷·전환 정책, 캐시·RLS 사용과 DB 연결 기술.
- 유휴 시간·진행 시간 경과, checkpoint 방식·주기·RPO/RTO·저장 실패 재시도 한도, 안전한 로그인 대기 방식.
- 중간 합류·과거 보상·탈퇴 자산·파티 분할/병합과 소유권 부재 정책.
- 템플릿/콘텐츠 manifest 형식·버전 호환·migration/rollback, 기존 월드 신규 지형 적용 방식.
- 보호 구역 충돌·행위별 권한·건축 드롭과 채집 반출 정책, 채집 인던 소유/초기화/재생성.
- 경제 멀티 프로세스 분리 여부와 AI 세부 기술은 그대로 미정.

확정 설계 변경이 필요한 항목은 사용자 검토 후 후속 ADR로 관리합니다. 이번 초안은 ADR-0001·0002를 변경하지 않으며 서버·컨테이너·DB 실행이나 운영 변경을 승인하지 않습니다.
