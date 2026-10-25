# S4 사전 조사 — 기성 플러그인 적합성 검토

- 확인일: 2026-10-25 (Asia/Seoul). 저장소 기준: `be4a2917347372962124ac6e6c59f94c6d5f3d88`.
- 범위: 공식 배포 메타데이터·문서·공개 소스 열람. **플러그인 다운로드·설치·빌드·서버 실행·성능 시험은 하지 않았습니다.**
- 상태: WorldGuard는 [ADR-0014](decisions/0014-worldguard-protection-engine.md)의 채택을 유지합니다. WorldEdit는 도구 및 의존성 검토 대상, Multiverse-Core는 미채택 후보입니다. 아래 버전은 조사 시점의 관측값이며 Hearth 설치 버전 확정이 아닙니다.
- S2 완료·S3 진행 중·S4 실제 구현 미착수. [S3 감사 C1](s3-completion-audit.md)은 추천안 작성 후에도 사용자 최종 승인 전 미해결입니다.

## 1. 판단 원칙과 증거 구분

새 기능을 구현하기 전에 기성 플러그인을 조사하고 Paper 26.2·Java 25 호환성, 라이선스, 유지보수, API, 성능, 충돌, 진행별 격리 및 동적 월드 수명주기를 비교합니다. 적합하면 재사용하고 Hearth 고유 정책만 연동합니다. 부적합하거나 유지보수 비용이 크면 자체 구현과 비교하고 근거를 남깁니다. 발견·지원 버전 표시·API 존재는 채택 승인이나 실제 호환성 검증을 대신하지 않습니다.

| 표시 | 의미 |
| --- | --- |
| 공식 확인 | 아래 출처에서 읽은 배포 정보·문서 계약·태그 소스 선언. 실행 성공을 뜻하지 않음 |
| 검토 추천·추정 | Hearth 요구와 비교한 판단·통합 후보. 새 아키텍처 채택이 아님 |
| 미검증 / S4 | 실제 아티팩트·조합·동작·부하를 시험해야 함. 이번 결과는 모두 미실행 |

공식 배포 페이지의 Minecraft/Paper 지원 범위와 Java 25에서의 실제 플러그인 실행은 별개의 증거입니다. 이번에 확인한 자료만으로 세 플러그인의 Java 25 조합 시험 성공을 증명할 수 없습니다. 이전 [S2 최소 빌드](stack-build-verification.md)는 Hearth 최소 플러그인과 Paper API 참조 검증이며 WG/WE/MV 검증이 아닙니다.

## 2. 버전·배포·라이선스 관측

| 후보 | 공식 배포 및 관측 버전 | 26.2 관련 근거·한계 | 라이선스·유지보수 관측 |
| --- | --- | --- | --- |
| WorldGuard | 공식 설치 안내에서 연결하는 [Bukkit 배포 목록](https://dev.bukkit.org/projects/worldguard/files/): 최신 7.0.20(2026-10-16)은 MC 26.3 표시. 26.2를 명시한 7.0.19(2026-09-18)는 MC 26.2–26.3 표시 | [7.0.19 plugin.yml](https://github.com/EngineHub/WorldGuard/blob/7.0.19/worldguard-bukkit/src/main/resources/plugin.yml)의 `api-version: 26.2`, `depend: [WorldEdit]`. 7.0.20을 26.2용 최신이라고 자동 선택하지 않음 | [7.0.19 LICENSE](https://github.com/EngineHub/WorldGuard/blob/7.0.19/LICENSE.txt): LGPL v3 및 동봉 코드 고지. 최근 배포 존재는 확인, 장기 지원 보장은 아님 |
| WorldEdit | EngineHub 공식 [Hangar 7.4.6](https://hangar.papermc.io/EngineHub/WorldEdit/versions/7.4.6), 2026-10-15, Paper 1.21.4–26.3 표시 | 지원 범위에 26.2 포함. [7.4.6 plugin.yml](https://github.com/EngineHub/WorldEdit/blob/7.4.6/worldedit-bukkit/src/main/resources/plugin.yml)은 Bukkit용·STARTUP 로드, Vault soft dependency. WG와의 조합 실행은 미검증 | [7.4.6 LICENSE](https://github.com/EngineHub/WorldEdit/blob/7.4.6/LICENSE.txt): 전체 GPL v3 및 포함 라이브러리 고지. 최근 정식 배포 확인 |
| Multiverse-Core | 공식 [Hangar 5.8.1](https://hangar.papermc.io/Multiverse/Multiverse-Core/versions/5.8.1) 및 [GitHub 릴리스](https://github.com/Multiverse/Multiverse-Core/releases/tag/5.8.1), 2026-08-28 | Paper 1.18.2–26.2 표시. [5.8.1 build.gradle](https://github.com/Multiverse/Multiverse-Core/blob/5.8.1/build.gradle)은 서버 API 1.21.11 참조: 빌드 대상과 배포 지원 표시는 별개. Java 25 런타임 보증으로 확대하지 않음 | [5.8.1 LICENSE](https://github.com/Multiverse/Multiverse-Core/blob/5.8.1/LICENSE.md): BSD-3-Clause. 릴리스는 월드 이름/키 불일치·스폰/틱 관련 수정 포함. 계속되는 변경에 따른 회귀 시험 필요 |

라이선스 이름만으로 Hearth JAR 통합·수정·재배포 조건을 모두 충족했다고 판단하지 않습니다. 사용 버전을 정하면 해당 LICENSE/제3자 고지, API 연동·코드 복사·동봉 여부와 배포 조건을 다시 확인합니다. 우선 독립 플러그인과 공개 API 연동을 비교하며 JAR 병합이나 소스 복사를 승인하지 않습니다. 메타데이터/문서의 `latest`는 변하므로 S4 시험 때 정확한 Paper 빌드·Java·플러그인 버전·체크섬·설정을 함께 기록해야 합니다.

## 3. WorldGuard / WorldEdit 적합성

### 공식 확인

- [WorldGuard 설치 문서](https://worldguard.enginehub.org/en/latest/install/)는 Bukkit/Paper 계열과 **WorldEdit Bukkit 플러그인**을 요구합니다. 7.0.19 태그의 hard dependency도 확인했습니다. WorldEdit를 제작 때만 설치한 뒤 제거할 수 있다고 가정하지 않습니다. 다만 최종 조합과 운영 설치는 아직 승인·시험하지 않았습니다.
- WG 7.0.19의 [의존성 카탈로그](https://github.com/EngineHub/WorldGuard/blob/7.0.19/gradle/libs.versions.toml)는 WorldEdit 7.4.4와 Paper API 26.2 계열을 참조합니다. 이는 빌드 선언이며 WorldEdit 7.4.4만 허용한다거나 최신 7.4.6과의 조합 성공을 뜻하지 않습니다.
- [RegionManager 문서](https://worldguard.enginehub.org/en/latest/developer/regions/managers/)는 월드별 구역 목록, manager 조회 실패/null 가능성, 변경 후 지연 자동 저장 및 완료까지 blocking하는 명시적 저장/로드 API를 설명합니다. 자동 저장 예약만으로 언로드 경계의 저장 완료를 판단하지 않습니다.
- [저장 문서](https://worldguard.enginehub.org/en/latest/regions/storage/)는 기본 월드별 YAML, PostgreSQL 드라이버 미지원·SQL 사용 비권장을 명시합니다. Hearth PostgreSQL은 진행 상태 정본이고 WG 저장소를 대체하지 않습니다. 월드 폴더 복사만으로 WG 설정까지 복제된다고 가정하지 않습니다.
- WG의 [플래그](https://worldguard.enginehub.org/en/latest/regions/flags/)·[우선순위](https://worldguard.enginehub.org/en/latest/regions/priorities/) API/정책을 활용합니다. 유체·화재 등 일부 고빈도 플래그는 설정 활성화와 부하 확인이 필요합니다.
- WE는 [공개 API](https://worldedit.enginehub.org/en/latest/api/)와 [EditSession](https://worldedit.enginehub.org/en/latest/api/concepts/edit-sessions/)을 제공합니다. 세션 종료는 블록 큐 처리 경계입니다. 구역 선택·편집 재사용 후보이며 전체 월드 파일 복제·진행 DB 저장을 대신하지 않습니다.

### Hearth 충족 범위와 추천

**WorldGuard: 채택 유지·재사용 추천. WorldEdit: 의존성/제작·관리 용도 조건부 추천.** 일반 보호 엔진을 다시 만드는 비용을 줄일 수 있습니다. 구역 중첩·플래그·건축/간접 변경은 WG, 콘텐츠 권한·다른 run 진입 차단·내부 변경 범위·채집 리셋은 Hearth 책임을 유지합니다. WorldEdit API로 런타임 지형 편집을 수행할지는 별도 후보이며 기본 보호 채택으로 자동 결정하지 않습니다.

동적 월드 연동 후보는 [S3 §4·§13](story-world-detailed-design.md)와 ADR-0014를 따릅니다.

| 경계 | 연동 후보·미검증 조건 |
| --- | --- |
| 템플릿 복제 | 지형과 보호 템플릿 버전을 따로 추적. 원본 읽기 전용 유지, 새 물리 월드에 별도 구역 저장소/참조 연결. 이름·키·UUID 충돌 및 다른 run 참조 거부 |
| 로드/입장 | 물리 매핑 → 해당 manager 준비 → 구역/콘텐츠 검증 → 개인 데이터 복원 후 입장. null·로드 오류 시 해당 월드 입장 제한, 다른 manager로 대체 금지 |
| 활성 변경 | 일반 플레이는 WG 판정 사용. NPC/퀘스트 직접 월드 변경은 Hearth가 run·허용 범위를 확인. WG가 모든 내부 API 변경을 자동 차단한다고 가정하지 않음 |
| 저장/언로드 | 필요한 DB·월드·구역 저장 결과 확인 후 언로드. WG blocking I/O를 메인 스레드에서 기다리지 않는 조정 방안 시험. 모든 Bukkit 호출을 비동기로 옮긴다는 뜻은 아님 |
| 재로드/채집 새 세대 | 기존 영속 구역 보존, 올바른 월드 manager 재연결. 구세대 콜백·이전 월드 참조 차단. 플러그인 내부 manager 해제 시점/API는 실제 선택 버전에서 확인 |

**위험·성능 미검증:** 동적 월드 수에 따른 manager/구역 인덱스, 세션·편집 기록, 월드 참조 잔존, 대량 편집/구역 저장 I/O, 보호 이벤트 조회 및 고빈도 플래그 부하. 편집 권한·bypass와 게임 접근 권한을 분리하고 제작용 관리 명령을 일반 플레이어에게 노출하지 않는 방안을 시험합니다. 의존 플러그인 업데이트 시 함께 회귀 검증해야 합니다.

## 4. Multiverse-Core 적합성

### 공식 확인

- [명령 문서](https://mvplugins.org/core/fundamentals/commands-usage/)는 생성·복제·로드·언로드를 제공합니다. clone은 기존 MV 월드를 원본으로 월드/설정을 복사합니다. unload는 등록 정보·폴더를 삭제하지 않으며, 플레이어 잔류 시 별도 처리 없이 성공하는 작업이 아닙니다. 저장 생략 옵션은 Hearth 정상 저장 경로의 대안으로 사용하지 않습니다.
- [개발 API](https://mvplugins.org/core/developers/api-usage/) 및 [WorldManager Javadoc](https://multiverse.github.io/Multiverse-Core/javadoc/latest/org/mvplugins/multiverse/core/world/WorldManager.html)이 공개되어 있습니다. 안전 이동 API에는 safety 검사 제어가 있습니다. 문서 예제의 callback 형태만으로 모든 작업이 비동기라고 판단하지 않습니다.
- [5.8.1 WorldManager 소스](https://github.com/Multiverse/Multiverse-Core/blob/5.8.1/src/main/java/org/mvplugins/multiverse/core/world/WorldManager.java)의 clone은 폴더 복사 후 import/설정 전달, unload는 Bukkit 언로드와 MV 상태 정리를 수행합니다. 복제 전 원본 저장 옵션·복사 실패·이름/키 충돌 처리가 있습니다. **복사와 DB·WG 저장의 원자성, 모든 파일 작업의 비동기성은 보장된 것으로 읽지 않습니다.**
- [월드 속성](https://mvplugins.org/core/fundamentals/world-properties/)에는 auto-load·spawn 메모리 유지·스폰 조정·리스폰 목적지 등이 있고, [전역 설정](https://mvplugins.org/core/reference/configuration-file/)에는 접속·리스폰 관련 처리가 있습니다. 기본값/정확한 동작은 선택 버전 시험 대상으로 남깁니다.
- [5.8.1 plugin.yml](https://github.com/Multiverse/Multiverse-Core/blob/5.8.1/src/main/resources/plugin.yml)은 Vault·PlaceholderAPI를 soft dependency로 선언합니다. Core 사용만을 위해 자동 추가하지 않습니다. [공식 안내](https://mvplugins.org/core/)는 Inventories/Portals 등을 별도 하위 플러그인으로 구분합니다. **Core만으로 Hearth PostgreSQL 인벤토리·퀘스트·NPC 격리가 해결되지 않습니다.**

### Hearth 충족 범위와 추천

**조건부 추천 — 비교 시험 후보, 현재 미채택.** 공개 관리 API와 오류 결과·관리 도구를 재사용할 수 있습니다. 그러나 Hearth는 어느 방안에서도 논리 진행·단일 작업·저장 완료·접근 권한을 조정해야 하므로 MV 설치가 자체 수명주기 책임을 없애지는 않습니다. 검토되지 않은 Inventories/Portals 도입·인벤토리 정본 변경도 승인하지 않습니다.

| 기준 | MV API 활용 후보 | Paper API + Hearth 수명주기 관리자 후보 |
| --- | --- | --- |
| 생성/복제/원본 보존 | 기존 복제·등록/오류 API 재사용. 불변 템플릿을 MV에서 어떻게 참조할지, 저장 옵션이 원본을 쓰지 않는지 확인. clone 직후 로드될 수 있는 경로와 Hearth staging 절차 조정 필요 | 불변 템플릿을 staging 복제·검증한 뒤 Paper 로드. 부분 복사·UUID/키 충돌·중단 정리를 직접 관리해야 함 |
| 논리/물리 식별 | DB의 story_run_id/world_instance와 MV 등록·실제 키/UUID/폴더의 매핑 유지. alias를 DB 식별자로 쓰지 않음 | DB와 Paper 매핑 중심, MV 등록 중복은 없음. 식별자 재생성·매핑 검증은 여전히 필요 |
| PostgreSQL 격리 | MV는 물리 관리 보조. run+player 접근·개인/공유 데이터·30초 저장 순서·보상은 Hearth 책임 | 같은 Hearth 책임. Paper 월드 분리가 DB 격리를 자동 보장하지 않음 |
| WG 구역 | MV clone이 외부 WG 구역 파일을 완전 복제한다고 가정하지 않음. 이벤트 순서·연결/저장/해제 별도 연동 | 동일하게 WG 연동 필요. Hearth가 로드 경계를 직접 조정하나 성공 보장은 아님 |
| 유휴 언로드/재접속 | Hearth IDLE/저장 장벽 뒤 MV unload 호출 후보. 자동 로드·관리 명령·중복 이벤트와 충돌 방지 필요 | Paper createWorld/unloadWorld 결과와 Hearth 상태 직접 연결. 실패/취소/재접속 경합 구현 부담 |
| CPU·RAM | 활성 청크/엔티티 비용 외 MV 월드 등록·작업·설정/리스너 추가 비용 예상. 실제 증가량/참조 회수 미측정 | 추가 MV 계층은 없지만 자체 구현이 더 가볍다고 보장할 수 없음. 활성 월드 비용은 공통 |
| 유지보수/충돌 | MV 버전·API 변화와 자동 이동/리스폰·포털·spawn 조정 통합 부담. 별도 안전 이동이 보스 정확 좌표/방향 복귀를 바꾸지 않도록 시험 | 라이브러리 의존은 적으나 복제/저장/운영 오류 처리 직접 유지. Paper 변경 대응 부담 |

[Paper 26.2 Server API](https://jd.papermc.io/paper/26.2/org/bukkit/Server.html)의 createWorld/unloadWorld 및 월드 틱 중 작업 제약은 두 방안 모두 피할 수 없습니다. [S3 수명주기](story-world-detailed-design.md)의 저장 장벽·오류 격리·원본 보존을 우선하며, MV를 쓰더라도 Hearth와 MV가 각각 독립적으로 같은 월드를 생성/언로드하지 않도록 **하나의 조정 경로**를 두는 설계 후보입니다.

검토 추천은 Paper API+최소 Hearth 관리자 방안을 비교 기준으로 두고 MV가 복제/관리 구현량을 충분히 줄이는지 시험하는 것입니다. MV의 장점이 연동·유지보수 부담보다 크고 아래 시험을 충족하면 채택을 제안합니다. 그렇지 않으면 자체 관리자를 비교 선택합니다. **이번 문서는 어느 월드 관리 구현도 최종 채택하지 않으며 WG 채택은 변경하지 않습니다.**

## 5. S4 최소 시험 계획 — 전부 미실행

별도 실행 승인을 받은 격리 시험 환경에서 동일한 가상 템플릿·두 story_run·개인 상태를 사용합니다. WG/WE 공통 기반에 MV 유무만 달리 비교합니다. 실서비스 설정·실제 월드·비공개 서사를 사용하지 않습니다. 정확한 반복 수·동시 월드/플레이어 수·허용 지연/메모리 예산은 시험 전 정하며 이번에 수치를 발명하지 않습니다.

| ID | 시험 입력 | 성공 기준·관측 |
| --- | --- | --- |
| PL1 | 고정 Paper 26.2 빌드·Java 25, WG/WE 선택 버전 로드; MV는 별도 조합 | 의존성/클래스로더/API 오류 없는 활성화·정상 종료. 실제 버전/체크섬/로그 기록. 버전 표기만으로 통과 금지 |
| PL2 | 같은 템플릿으로 run A/B 생성, 동시 중복 생성 요청·대상 경로 충돌·복사 중 실패 | 원본 전후 체크섬 일치, 독립 물리 식별/DB 매핑, 한 작업만 완료, 불완전 복사본 입장 금지·타 run 보존. 원본 저장 옵션/UUID 처리 관측 |
| PL3 | WG 동일 구역 ID를 서로 다른 월드에서 사용·한쪽 변경·언로드/재로드 | 각 manager/영속 설정·건축 권한 독립, 구역 저장 보존. manager null/저장 실패는 해당 범위 제한·성공 오기 없음 |
| PL4 | [ADR-0014 W1~W13](decisions/0014-worldguard-protection-engine.md) 전부 | 설치/파괴·경계/중첩·피스톤/폭발/유체/화재/엔티티 변경·시설·NPC/퀘스트 내부 변경·다른 run 침범·채집 리셋 책임대로 동작. 권한/bypass·고빈도 설정 기록 |
| PL5 | 마지막 퇴장→IDLE, 대기 중 재입장, 저장 중 입장, unload 취소/실패·반복 reload | 필요한 개인/월드/WG 저장 완료 뒤만 OFFLINE. 예약 취소/복원 순서, 참조/티켓 정리. 등록된 유휴 월드가 불필요하게 자동 재로드되지 않음 |
| PL6 | 명령/포털/사망/로그인/리스폰과 일반 이동·보스 정확 위치/방향 재접속 | Hearth 접근/인벤토리 복원 경계 우회 없음, 타 run 노출 없음. 보스 위치 안전 보정 없음, 죽음/횟수 보존, 조건부 부활 위치 정책은 별개 |
| PL7 | 두 run 퀘스트/NPC/인벤토리 변경·스토리/경제 전환·지연 저장 콜백 | 다른 범위 변경 없음, 오래된 저장 덮어쓰기/중복 보상 없음. Core/추가 플러그인을 PostgreSQL 정본 대체로 사용하지 않음 |
| PL8 | 채집 reset 준비 실패·단일 요청·새 세대 로드/WG 연결 | ADR-0013대로 실패 시 ERROR_HOLD·원본 보존, 중복 reset/구세대 진입 없음. MV regen을 템플릿 초기화와 동등하다고 가정하지 않음 |
| PL9 | 동일 청크/엔티티/활성·유휴 월드 조건에서 MV 유무 비교, 편집/보호 이벤트·반복 로드 | MSPT/TPS·CPU·힙/GC·가능하면 RSS·I/O·로드/언로드 지연·월드/manager/태스크 잔존 비교. 허용 예산 내 동작·지속 증가 여부 판정. 단순 언로드로 즉시 OS 메모리 반환을 요구하지 않음 |
| PL10 | 저장/로드 실패·늦은 콜백·운영 명령과 수명주기 경합 | 단일 조정 경로·세대 확인·최소 오류 로그, 타 run 정상 유지. 손상 데이터를 빈 월드로 대체하거나 자동 무손실 복구를 약속하지 않음 |

시험 기록에는 입력 순서·설정·전후 파일/DB/물리 월드/WG 상태·실제 결과·오류·미실행 항목과 선택 근거를 남깁니다. WE 편집 중 메인 스레드 점유·세션/clipboard 잔존, WG 구역 저장 완료와 unload 이벤트 순서, MV 기본 이동/자동 로드 해제 가능성이 주요 관측 대상입니다. 라이선스/보안·호환성·유지보수 비용까지 검토한 후 별도 채택 결정을 기록합니다.

## 6. C1 추천안과 단계 경계

[S3 감사 C1 추천안](s3-completion-audit.md)에서 온라인 참가자만 보스 시작 명단/난이도에 포함하는 방식을 검토합니다. 온라인 길목 사망자의 자동 부활과 이미 시작한 도전 명단의 재접속 권리는 유지합니다. 오프라인 길목 참가자의 이번 도전 제외는 **미승인 추천**이며 플러그인 선택으로 결정할 수 없습니다. S3 완료 판정과 S4 실제 구현 착수는 별도 사용자 검토·지시를 기다립니다.
