# S4 사전 조사 — 기성 플러그인 적합성 검토

- 최초 확인·재확인일: 2026-10-25 (Asia/Seoul). 최초 기준 `be4a291`·이번 재확인 기준 `2df743f74d6b17bddebb9f7acfd9ff20c88f05dd`. 기존 검토를 보완하며 결과 커밋은 Git 이력으로 확인합니다.
- 최초 조사 범위(2026-10-25): 공식 배포 메타데이터·문서·공개 소스 열람, 다운로드/실행 없음. **2026-10-26 S4-1 후속 구현·최소 runtime smoke 결과는 마지막 절에 별도로 기록**합니다. 조사와 실제 시험을 구분합니다.
- 상태: WorldGuard는 [ADR-0014](decisions/0014-worldguard-protection-engine.md)의 채택을 유지합니다. WorldEdit는 도구 및 의존성 검토 대상, Multiverse-Core는 미채택 후보입니다. 아래 버전은 조사 시점의 관측값이며 Hearth 설치 버전 확정이 아닙니다.
- 현재 S2 완료·S3 설계 완료·S4 진행 중. 최초 조사 당시에는 S4 실제 구현 미착수였으며 후속 제한 시험은 아래 S4-1 절을 따릅니다. [S3 감사 C1](s3-completion-audit.md)은 이번 사용자 최종 승인으로 해소했으며 플러그인 발견/선택과 독립된 게임 정책입니다.

## 1. 판단 원칙과 증거 구분

새 기능을 구현하기 전에 기성 플러그인을 조사하고 Paper 26.2·Java 25 호환성, 라이선스, 유지보수, API, 성능, 충돌, 진행별 격리 및 동적 월드 수명주기를 비교합니다. 적합하면 재사용하고 Hearth 고유 정책만 연동합니다. 부적합하거나 유지보수 비용이 크면 자체 구현과 비교하고 근거를 남깁니다. 발견·지원 버전 표시·API 존재는 채택 승인이나 실제 호환성 검증을 대신하지 않습니다.

검토 절차는 요구 기능/충족 범위와 부족분 → 공식 버전·API/외부 연동·의존성/충돌·CPU/RAM·유지보수·라이선스/재배포·run 격리 증거 수집 → 플러그인 사용/API 확장/자체 개발 비교 추천 → 필요한 채택 결정과 S4 시험으로 구분합니다. 비교 결과가 없다면 모든 기능을 자동으로 자체 개발하지 않습니다. 각 기능의 실제 구현 전에 검토 내용을 최신화합니다.

| 표시 | 의미 |
| --- | --- |
| 공식 확인 | 아래 출처에서 읽은 배포 정보·문서 계약·태그 소스 선언. 실행 성공을 뜻하지 않음 |
| 검토 추천·추정 | Hearth 요구와 비교한 판단·통합 후보. 새 아키텍처 채택이 아님 |
| 미검증 / S4 | 실제 아티팩트·조합·동작·부하를 시험해야 함. 최초 조사만으로는 실행 확인 없음. 후속 S4-1 부분 시험은 마지막 절 참조 |

공식 배포 페이지의 Minecraft/Paper 지원 범위와 Java 25에서의 실제 플러그인 실행은 별개의 증거입니다. 이번에 확인한 자료만으로 세 플러그인의 Java 25 조합 시험 성공을 증명할 수 없습니다. 이전 [S2 최소 빌드](stack-build-verification.md)는 Hearth 최소 플러그인과 Paper API 참조 검증이며 WG/WE/MV 검증이 아닙니다.

## 2. 버전·배포·라이선스 관측

| 후보 | 공식 배포 및 관측 버전 | 26.2 관련 근거·한계 | 라이선스·유지보수 관측 |
| --- | --- | --- | --- |
| WorldGuard | 공식 설치 안내에서 연결하는 [Bukkit 배포 목록](https://dev.bukkit.org/projects/worldguard/files/): 최신 7.0.20(2026-10-16)은 MC 26.3 표시. 26.2를 명시한 7.0.19(2026-09-18)는 MC 26.2–26.3 표시 | [7.0.19 plugin.yml](https://github.com/EngineHub/WorldGuard/blob/7.0.19/worldguard-bukkit/src/main/resources/plugin.yml)의 `api-version: 26.2`, `depend: [WorldEdit]`. 7.0.20을 26.2용 최신이라고 자동 선택하지 않음 | [7.0.19 LICENSE](https://github.com/EngineHub/WorldGuard/blob/7.0.19/LICENSE.txt): LGPL v3 및 동봉 코드 고지. 최근 배포 존재는 확인, 장기 지원 보장은 아님 |
| WorldEdit | EngineHub 공식 [Hangar 7.4.6](https://hangar.papermc.io/EngineHub/WorldEdit/versions/7.4.6), 2026-10-15, Paper 1.21.4–26.3 표시 | 지원 범위에 26.2 포함. [7.4.6 plugin.yml](https://github.com/EngineHub/WorldEdit/blob/7.4.6/worldedit-bukkit/src/main/resources/plugin.yml)은 Bukkit용·STARTUP 로드, Vault soft dependency. WG와의 조합 실행은 미검증 | [7.4.6 LICENSE](https://github.com/EngineHub/WorldEdit/blob/7.4.6/LICENSE.txt): 전체 GPL v3 및 포함 라이브러리 고지. 최근 정식 배포 확인 |
| Multiverse-Core | 공식 [Hangar 5.8.1](https://hangar.papermc.io/Multiverse/Multiverse-Core/versions/5.8.1) 및 [GitHub 릴리스](https://github.com/Multiverse/Multiverse-Core/releases/tag/5.8.1), 2026-08-28 | Paper 1.18.2–26.2 표시. [5.8.1 build.gradle](https://github.com/Multiverse/Multiverse-Core/blob/5.8.1/build.gradle)은 서버 API 1.21.11 참조: 빌드 대상과 배포 지원 표시는 별개. Java 25 런타임 보증으로 확대하지 않음 | [5.8.1 LICENSE](https://github.com/Multiverse/Multiverse-Core/blob/5.8.1/LICENSE.md): BSD-3-Clause. 릴리스는 월드 이름/키 불일치·스폰/틱 관련 수정 포함. 계속되는 변경에 따른 회귀 시험 필요 |

라이선스 이름만으로 Hearth JAR 통합·수정·재배포 조건을 모두 충족했다고 판단하지 않습니다. 사용 버전을 정하면 해당 LICENSE/제3자 고지, API 연동·코드 복사·동봉 여부와 배포 조건을 다시 확인합니다. 우선 독립 플러그인과 공개 API 연동을 비교하며 JAR 병합이나 소스 복사를 승인하지 않습니다. 메타데이터/문서의 `latest`는 변하므로 S4 시험 때 정확한 Paper 빌드·Java·플러그인 버전·체크섬·설정을 함께 기록해야 합니다.

### 정확한 버전 조합의 확인 범위

이번 공식 배포 페이지·태그 선언 재확인 결과도 위와 같습니다. 다음은 **실행 시험 후보 조합**이며 호환 조합 확정/설치 승인이 아닙니다. 실제 Java 25·고정 Paper 빌드·플러그인 3자 연동 성공 여부는 문서 조회만으로 결정할 수 없습니다.

| 후보 조합 | 확인한 사실 | 남은 확인 |
| --- | --- | --- |
| Paper 26.2 + WG 7.0.19 + WE 7.4.4 | WG hard dependency는 WorldEdit, 해당 태그 빌드 참조는 WE 7.4.4. [WE 7.4.4 배포](https://hangar.papermc.io/EngineHub/WorldEdit/versions/7.4.4)는 Paper 1.21.4–26.2 및 26.2 갱신 명시 | 후속 S4-1에서 이 고정 조합+Java 25의 로딩·A/B 구역 API/저장·재로드 smoke 통과. 실제 플레이어 이벤트·전체 수명주기·부하는 미시험 |
| Paper 26.2 + WG 7.0.19 + WE 7.4.6 | WE 최신 관측 7.4.6의 Paper 지원 범위에 26.2 포함 | WG가 빌드 참조한 WE와 다른 버전의 API/adapter·동작 회귀 미검증. 운영 버전 자동 상향 금지 |
| 위 WG/WE 기반 + MV 5.8.1 | MV 공식 Paper 지원 범위는 26.2 포함, soft dependency는 Vault/PlaceholderAPI | WG/WE와 동적 생성/저장/언로드·재접속 조합 및 Java 25 실행 미검증 |

정확한 최소 허용 WorldEdit 버전이나 모든 상위 버전과의 바이너리 호환성을 `depend: [WorldEdit]` 하나로 추론하지 않습니다. WG 7.0.20의 관측 지원 표시는 26.3이므로 이번 26.2 시험의 자동 대체 버전으로 추천하지 않습니다.

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

## 5. S4 최소 시험 계획 — 전체 수용 시험은 미완료

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

## 6. 선택 추천과 C1·단계 경계

| 영역 | 검토 추천 | 결정 경계 |
| --- | --- | --- |
| 일반 보호 | 기성 WorldGuard 사용 | ADR-0014 채택 유지. Hearth 자체 범용 보호 엔진 제외 |
| 제작/관리 및 보호 연동 | WorldEdit 도구/의존성 + WG 공개 API 확장 | WorldEdit Bukkit 필요는 공식 확인. 런타임 지형 편집 자동 채택·운영 버전 확정은 아님 |
| 물리 월드 관리 | MV API 활용과 Paper API+Hearth 관리자를 PL1~PL10으로 비교 | MV 조건부 후보. 복제/관리 재사용 이득과 충돌·비용 측정 후 결정 |
| run/개인 DB·진행 입장·저장 조정 | Hearth 고유 코드/API 연동 후보 | 조사한 Core/WG/WE로 정책 전체가 충족되지 않음. 후속 기능별 플러그인 조사도 먼저 수행 |

[S3 감사 C1](s3-completion-audit.md)은 온라인 시작 명단/난이도·온라인 사망자 진입 자동 부활·시작 당시 오프라인 길목 참가자는 다음 도전부터·이미 시작한 참가자의 재접속 권리 유지로 승인됐습니다. 기존 미승인 추천 이력은 `2df743f`에 보존하며 현재 정책은 ADR-0013 후속 승인을 따릅니다. S3 설계 완료와 별개로 S4 실제 구현·설치/실행 착수는 이번 범위가 아닙니다.

## S4-1 후속 확인 (2026-10-26)

[실제 구현/검증 기록](s4-1-verification.md)에 Paper API 비교용 어댑터·최소 JAR 빌드/파일 시험과 공식 배포물 확인을 기록했습니다. WG 7.0.19+2400-f395a16/WE 7.4.4+7546-f9e033f JAR 해시 및 WG hard dependency를 확인했습니다. Paper 26.2 build 134도 공식 SHA-256 일치입니다. 사용자 EULA 동의 후 이 고정 조합의 활성화/정상 종료, Paper API 기반 독립 A/B 블록/UUID 보존·저장/언로드/재로드, 별도 WG manager/YAML과 동일 probe ID의 A deny/B allow·디스크 재조회 smoke가 통과했습니다. BUILD 판정은 명시적인 합성 NON_MEMBER 주체를 사용하며 실제 플레이어 이벤트 보호 시험은 아닙니다.

PL1의 WG/WE 로딩/종료 부분은 실행했고 MV 조합은 미실행입니다. PL2는 합성 공간 복사·블록/식별자 분리·중복 할당 거부·원본 보존 일부, PL3은 구역 API/저장/재로드 일부만 확인했습니다. DB 매핑·실제 건축 권한·장애/경합과 PL4~PL10 전체 수용 기준은 미검증입니다. 짧은 CPU/RSS 관측은 부하/누수·WE 편집·MV 비교를 대체하지 않습니다. 초기 오류 3건과 수정·정상 종료/자원·아티팩트 증거는 S4-1 기록에 보존합니다. Multiverse-Core 미채택·후속 비교 상태는 유지하며 운영 버전 확정이나 전체 호환성 보장은 아닙니다.

## S4-2 후속 확인 (2026-10-26)

[두 JVM 검증](s4-2-verification.md)에서 같은 고정 WG/WE/Paper 조합의 재시작·월드별 구역/중첩 보존과 자연 발생 피스톤 A 거부/B 허용을 확인했습니다. PL3과 W4 일부의 근거이며 접속 플레이어 설치/파괴·권한/bypass·전체 W1~W13/PL1~PL10 통과가 아닙니다. 최소 Paper API 배치 매핑만 확장했고 MV 후보/비교 상태와 WG 채택을 변경하지 않았습니다.

## 격리 NPC·개인 방문 퀘스트 검토 — 2026-10-26

이번 범위는 중립적인 주민 한 명과 아이템 소모·보상 없는 방문/보고 퀘스트 한 개입니다. 외부 플러그인의 설치·채택 승인을 추정하지 않습니다.

| 후보 | 공식 자료에서 확인한 사실 | Hearth 적합성·미확인 | 이번 선택 |
| --- | --- | --- | --- |
| Citizens | 공식 저장소는 NPC 공개 API·개발 빌드 경로를 안내합니다. 확인한 POM은 `2.0.44-SNAPSHOT`, `v26_2_R1` 모듈을 포함합니다. OSL-3.0 라이선스입니다. | 26.2 대응 소스 근거는 있으나 고정 Paper build 134/Java 25 배포 JAR 실동작은 미검증. registry·위치 기반 NPC를 story_run_id·동적 월드/청크에 연결하고 비영속 인스턴스 정리를 별도 검증해야 합니다. 버전별 내부 서버 어댑터 유지보수 위험이 있습니다. | 향후 외형·경로·다수 NPC 요구 시 API 연동 후보. 이번에는 설치하지 않음 |
| BetonQuest | 공식 대화 문서에 콘텐츠 기반 대화·Citizens 연결이 있고, 공개 개발 API와 변경 이력·기여 경로가 있습니다. GPL-3.0 라이선스입니다. | 최신 배포의 정확한 26.2/Java 25 조합은 미확인. 이번 자료만으로 `story_run_id+player_uuid` 소유권·동적 복제 월드의 대화/NPC 참조 정리가 보장된다고 판단할 수 없습니다. 기존 PostgreSQL 정본과 외부 진행 정본의 중복 관리 위험이 있습니다. | 대화/퀘스트 플랫폼 채택은 별도 아키텍처 선택. 지금 채택하지 않음 |
| Paper 기본 주민·Adventure 채팅 + Hearth 저장 연동 | 기존 Paper API가 주민 생성·상호작용 이벤트·비영속 엔티티와 채팅 선택을 제공합니다. | 복잡한 NPC 플랫폼을 재현하지 않고 고정된 주민 하나와 세 번의 상태 전이만 연결합니다. 기존 bounded DB worker·CAS·세션·월드 저장 장벽을 재사용할 수 있습니다. 실제 클릭과 클라이언트 UI는 별도 시험입니다. | 이번 격리 프로토타입에 한정해 사용. 운영 NPC 플랫폼 확정이 아님 |

자료: [Citizens README](https://raw.githubusercontent.com/CitizensDev/Citizens2/master/README.md), [POM](https://raw.githubusercontent.com/CitizensDev/Citizens2/master/pom.xml), [라이선스](https://raw.githubusercontent.com/CitizensDev/Citizens2/master/LICENSE), [BetonQuest 대화](https://betonquest.org/2.2/Documentation/Features/Conversations/), [공개 API](https://betonquest.org/RELEASE/API/Overview/), [저장소·라이선스](https://github.com/BetonQuest/BetonQuest). Citizens 버전 위키 직접 열기는403으로 실패해 정확한 배포 호환 버전을 확정하지 않았습니다. 개발 브랜치·문서 존재를 안정성/성능 보장으로 취급하지 않으며 외부 코드·배포물을 복사하거나 재배포하지 않았습니다.

구현 API 근거: [Paper 상호작용 이벤트](https://jd.papermc.io/paper/26.2/org/bukkit/event/player/PlayerInteractEntityEvent.html)는 세부 위치 이벤트 사용을 권고하므로 `PlayerInteractAtEntityEvent`를 사용합니다. [엔티티 비영속 설정](https://jd.papermc.io/paper/26.2/org/bukkit/entity/Entity.html#setPersistent(boolean))에 따라 시험 NPC를 월드 파일에 중복 저장하지 않습니다. [WorldGuard flags](https://worldguard.enginehub.org/en/latest/regions/flags/)의 `INTERACT`를 NPC 지점에만 허용하며 건축 권한과 구분합니다. 실제 결과와 남은 시험은 [개인 퀘스트 기록](personal-quest-verification.md)을 따릅니다.
