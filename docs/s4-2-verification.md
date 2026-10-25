# S4-2 프로세스 재시작·WorldGuard 보호 경로 검증

- 확인일: **2026-10-26 (Asia/Seoul)**. 기준 `4691b11f6664242d25188fc17d5d451332313ae1`. 시작 시 main·작업 트리/스테이징은 깨끗했고 `git fetch origin` 후 원격과 일치했습니다.
- **두 JVM 사이의 월드/구역 영속성, 보호 API와 자연 발생 피스톤 이벤트는 통과했습니다. 접속 플레이어 설치·파괴 시험은 미실행입니다. S4 전체는 진행 중**입니다.
- 신규 `/tmp/hearth-s4-2.*`만 실행했습니다. 기존 [S4-1 증거](s4-1-verification.md)의 로그/결과 해시 불변을 확인했으며 기존 게임 데이터·비공개 스토리·Docker/Zabbix/Cloudflare/SSH·DB·전역 Java/시스템 패키지는 변경/접근하지 않았습니다. Multiverse-Core는 도입하지 않았습니다.

## 구현 범위·근거

[ADR-0001](decisions/0001-story-world-multiworld.md)의 단일 프로세스/진행 격리, [ADR-0012](decisions/0012-progress-storage-world-lifecycle-and-content.md)의 기존 월드 보존·저장 실패 금지, [ADR-0014](decisions/0014-worldguard-protection-engine.md)의 기성 보호 엔진을 유지합니다. 기성 플러그인 선택은 [검토 기록](plugin-suitability-review.md)의 WG/WE 재사용·Paper API 비교 어댑터를 연장한 시험이며 최종 월드 관리자 채택이나 PostgreSQL 구현이 아닙니다.

| 변경 | 범위·경계 |
| --- | --- |
| `HearthPlugin` | 기존 설정/JVM root/격리 표식/콘솔 게이트 유지. S4-2 경로·표식을 추가하고 `hearthverify restart prepare` / `resume`만 연결. 일반 서버에서 기본 비활성 |
| `WorldBackend`·`PaperWorldBackend` | 기존 생성/로드/저장/언로드 유지. 저장된 시험 배치를 재등록하는 `registerExisting` 추가. 키/예상 경로/실제 경로·준비 표식/UUID 메타데이터/필수 region 확인, 로드 후 UUID/key 대조. 누락 데이터를 신규 생성으로 우회하지 않음 |
| `RestartVerification` | 버전 고정 합성 템플릿 복제·A/B 기준 블록·구역/중첩 설정, 최소 Properties 매핑과 체크섬, 새 JVM 복원·API/피스톤·타이밍 관측. 프로덕션 진행/세션/유휴 관리자·DDL 없음 |
| [S4-2 실행 도구](../scripts/verify-s4-2.py) | 새 시험 서버만 생성. 첫 JVM에서 기존 S4-1 smoke 회귀→prepare→정상 stop/exit/포트 해제 확인→같은 데이터의 새 JVM resume. 두 JVM 합산 240초와 기존 메모리 한도 유지 |
| [요약 증거](../verification/s4-2-runtime-result.json) | 버전/JAR/각 JVM PID·시간·자원·실제 로그 요약/해시·초기 실패·미실행. 배포 JAR/전체 로그/월드는 Git 미포함 |

구현은 보호 엔진을 재작성하지 않습니다. 구역 API와 중첩 우선순위는 WorldGuard에 위임합니다. [고정 WG 이벤트 소스](https://github.com/EngineHub/WorldGuard/blob/7.0.19/worldguard-bukkit/src/main/java/com/sk89q/worldguard/bukkit/listener/EventAbstractionListener.java)의 피스톤→블록 파괴/배치 위임 경로와 [보호 판정 소스](https://github.com/EngineHub/WorldGuard/blob/7.0.19/worldguard-bukkit/src/main/java/com/sk89q/worldguard/bukkit/listener/RegionProtectionListener.java)를 확인했습니다. 실제 설치/파괴 이벤트는 플레이어 경로가 별도로 필요합니다.

## 버전·빌드·실행 조건

| 항목 | 사용 값·결과 |
| --- | --- |
| 도구 | 사용자 전용 Temurin 25.0.5+8-LTS, Gradle 9.1.0. 명령별 JAVA_HOME·기존 /tmp 격리 Gradle cache, 전역 변경/추가 다운로드 설치 없음 |
| 서버/API | Paper 26.2 build 134 / `26.2.build.134-stable` |
| 보호 플러그인 | WG 7.0.19+2400-f395a16, WE 7.4.4+7546-f9e033f. 기존 [공식 해시 목록](../verification/s4-1-artifacts.json)과 복사한 JAR 재대조 통과 |
| Hearth 빌드 | `compileJava`, `jar`, `test`, `check` 성공·종료 0. 기존 JUnit 파일 안전성 시험 1개·실패/오류/skip 0. class major 69·plugin.yml 포함·Paper/WG/WE API 미동봉 |
| EULA/노출 | 기존 사용자 동의 범위에서 새 Hearth 시험 서버에만 eula=true. 127.0.0.1:25585·online-mode/whitelist·RCON/query 비활성. 공개 포트/포트포워딩/인증 우회 없음 |
| 자원 제한 | Xms256MiB/Xmx1536MiB·ActiveProcessorCount=2·nice+10. 두 JVM 합산 작업 240초, stop 30초 유예 후 해당 자식만 종료. RSS 2.5GiB 감시, 호스트 가용 6GiB·디스크 4GiB 사전 기준. CPU 값은 JVM 힌트이며 하드 quota 아님 |

Paper bootstrap은 격리 경로에 Mojang/라이브러리를 다운로드했습니다. 이 전이 파일 전체의 별도 공식 해시 목록은 만들지 않았습니다. 빌드는 기존 캐시를 사용했습니다. 정확한 Hearth JAR SHA-256은 요약 증거에 기록합니다.

```sh
env JAVA_HOME=/home/server/.local/share/hearth-verification/jdk-25.0.5+8 GRADLE_USER_HOME=/tmp/hearth-s4-1.RSHqyT/gradle-home /tmp/hearth-gradle-9.1.0/gradle-9.1.0/bin/gradle --no-daemon --offline --console=plain check jar
python3 scripts/verify-s4-2.py --workspace /tmp/hearth-s4-2.vnd2kki4 --java /home/server/.local/share/hearth-verification/jdk-25.0.5+8/bin/java --plugin build/libs/Hearth-0.1.0-SNAPSHOT.jar --accept-eula
```

## 두 프로세스 사이의 실제 결과

최종 prepare JVM **PID 976171**, resume JVM **PID 976339**, 서로 다른 boot ID를 확인했습니다. 첫 JVM은 매핑·WG 구역 저장과 월드 저장/언로드 완료 후 stop/exit 0, 그 종료와 포트 해제를 확인하고 두 번째 JVM을 시작했습니다. 두 번째도 stop/exit 0이며 harness exit 0, 합산 43.510초입니다. `resume`에는 backend 신규 할당/템플릿 복사 호출이 없습니다. Paper의 기존 월드 로드 API인 createWorld 호출은 파일/식별 사전 검사 뒤에만 수행합니다.

| 월드 | story_run_id | 실제 key / 경로(서버 기준) | 물리 UUID (전후 동일) | (0,64,0) 전후 |
| --- | --- | --- | --- | --- |
| A | `00000000-0000-002a-0000-00000000000a` | `hearth_verify:restart_a` / `world/dimensions/hearth_verify/restart_a` | `d0338083-1d20-442c-95e2-27aa4f2a7e53` | GOLD_BLOCK 유지 |
| B | `00000000-0000-002a-0000-00000000000b` | `hearth_verify:restart_b` / `world/dimensions/hearth_verify/restart_b` | `57a69884-65d9-41ff-840f-7b031ad2712f` | DIAMOND_BLOCK 유지 |

- 매핑의 templateVersion은 `synthetic-restart-v1`. 최초 A/B는 같은 STONE 템플릿 공간 파일을 복사했고 각자 다른 블록을 기록했습니다. 템플릿 공간 SHA-256 `c3eb92ca32c953eca59d7fd8cbdbda1bb2f6f37b7f5fc88ec85ebb91c5ba83b1`은 두 단계 전후 동일합니다.
- 새 JVM의 A/B 로드 **이전** 공간 파일과 3개 월드 UUID 메타데이터 해시가 저장 시점과 일치했습니다. 로드 후 Paper UUID/key 및 블록/API/구역을 대조했습니다. 두 단계 모두 피스톤 시험 전후 기준 블록은 유지됐습니다.
- 재로드 후 다시 틱/물리/저장을 수행한 A/B 전체 공간 해시는 달라졌습니다. 전체 region에는 청크 갱신 정보 등이 포함되므로 전체 바이트 불변을 주장하지 않습니다. 이후 해시는 결과 파일에 보존하며 전체 NBT 차이 원인 분석은 하지 않았습니다. 원본 템플릿 불변과 기준 블록/구역·식별자 보존을 구분합니다.
- 존재하지 않는 `restart_missing` 복원 요청은 거부했고 디렉터리/로드된 월드가 새로 생기지 않았습니다. 손상/UUID 변경 주입 시험은 이번에 수행하지 않았으며 일반 장애 복구 보장을 뜻하지 않습니다.

## 보호 결과 — API·서버 이벤트·접속 플레이어 구분

| 시험 | 방법·관측 | 결과·한계 |
| --- | --- | --- |
| A 보호 / B 허용 | 동일 `protected` ID, x/z 0..15·y 60..70, A BUILD deny/B allow. (2,64,2)의 명시적 NON_MEMBER API 판정 전후 비교 | API 통과. 접속 플레이어 설치/파괴 성공 증거 아님 |
| A 지정 건축 구역 | `build`, x/z 6..10·priority 10·BUILD allow, (6,64,6) | API 허용·재시작 후 유지. 실제 플레이어 행동 미실행 |
| A 중첩 우선순위 | `overlap`, x/z 8..9·priority 20·BUILD deny, (8,64,8) | API 거부·두 priority/flags 보존. 실제 플레이어 행동 미실행 |
| 자연 발생 피스톤 A | 외부 (-2,64,8) EAST piston, (-1,64,8) STONE을 보호 경계 (0,64,8)로 밀기. redstone으로 실제 물리 동작 유발 | 양 JVM 모두 MONITOR에서 BlockPistonExtendEvent cancelled=true·affected=1. source STONE 유지·목적지 AIR로 실제 이동 거부 |
| 자연 발생 피스톤 B | 같은 배치·좌표, 독립 B의 allow 구역 | 양 JVM 모두 cancelled=false·affected=1, 목적지 STONE으로 실제 이동 확인. A 설정이 B 동작을 취소하지 않음 |
| 접속 플레이어 설치/파괴·건축 허용·중첩 | 인증된 Minecraft 클라이언트 미준비, 사용자/기존 계정 데이터 사용 없음 | **미실행**. online-mode를 끄거나 가짜 Player/패킷·합성 이벤트로 성공을 대체하지 않음 |
| 기타 보호/부하 | 폭발/유체/화재/엔티티·시설·NPC/퀘스트·다른 run 입장 등 | **미실행**. W1~W13/PL1~PL10 전체 완료 아님 |

피스톤 이벤트는 수동 `PluginManager.callEvent`나 가짜 이벤트로 만들지 않았습니다. Hearth 리스너는 MONITOR에서 취소/영향 블록 수만 관측하며 취소 상태를 변경하지 않습니다. 고정 설치 플러그인은 Hearth·WG·WE이고 보호는 WG가 수행했습니다. 블록 배치로 시험 장치를 만드는 내부 코드 자체는 일반 플레이어 건축 보호 검증이 아닙니다. 피스톤은 플러그인 청크 티켓을 사용해 구동하고 완료 후 티켓을 제거했습니다. 전 구역/방향/종류의 간접 보호를 보장하지 않습니다.

## 시간·자원 관측

| 관측 | prepare (S4-1 회귀 포함) | resume |
| --- | --- | --- |
| 프로세스 실행→Done 수신 | 14.222초 (Paper 보고 9.968초) | 8.928초 (Paper 보고 8.281초) |
| 실행→정상 종료 | 27.329초 | 16.180초 |
| 최대 표본 RSS | 1,281.93MiB | 940.66MiB |
| 표본 누적 CPU | 55.57 CPU초 | 34.43 CPU초 |
| 최소 호스트 가용 RAM | 11,796.00MiB | 12,154.73MiB |
| A/B create API 반환 | 25.775 / 23.998ms | 신규 할당 없음 |
| A/B load API 반환 | 22.429 / 22.125ms | 85.002 / 94.510ms |
| A/B save+unload | 297.110 / 275.140ms | 468.779 / 287.828ms |

템플릿 create 29.223ms, save+unload 250.835ms입니다. 반환 시간은 필요한 모든 청크가 준비된 시간이나 전체 입장 준비 지연이 아닙니다. save+unload는 두 호출 합산이며 단독 unload/디스크 I/O 분리 시간은 미측정입니다. CPU는 스레드 합계여서 벽시계보다 큽니다. RSS는 표본 관측이며 heap/GC 분석이나 누수 시험이 아닙니다. 피스톤 청크 준비·S4-1 회귀 포함 여부가 달라 두 JVM RSS 차이를 누수/절감량으로 판단하지 않습니다.

사전 RAM 15,442MiB·가용 13,032MiB·swap 사용 0, load 0.33/0.10/0.07, /tmp 약 62GiB 가용. 종료 후 가용 13,019MiB·swap 0, 시험 JVM 없음·25585 리스닝 없음 확인. 기존 서비스 성능 영향·실제 동시 플레이·TPS/MSPT·장기 메모리 누수는 **미측정**입니다.

## 오류·수정·증거 보존

초기 `/tmp/hearth-s4-2.0qcb7zku`는 prepare 실패·harness exit 1, JVM 정상 exit 0이었습니다. 고정 30틱 대기가 비동기 청크 준비보다 일찍 끝나 이벤트 확인 전에 실패했고, 직후 로그에서 A 취소/B 허용 이벤트가 관측됐습니다. 실패 후 두 번째 JVM은 시작하지 않았습니다. 대기를 실제 양쪽 이벤트·B 이동 완료까지 최대 150회/2틱 간격으로 관측하도록 수정한 후 **새 경로**에서 전 과정을 통과했습니다. 전체 240초/메모리 한도는 늘리지 않았고 과거 실패 결과는 성공으로 소급하지 않습니다.

최종 로그는 비대화형 터미널·JOML Unsafe deprecation·고정 Paper 빌드의 업데이트 안내·수동 save 성능 경고를 포함합니다. ERROR는 없으며 경고를 숨기거나 버전을 자동 상향하지 않았습니다. 수동 save 비용/자동 저장 조정은 후속 관측 대상입니다.

전체 로그/표본/월드/매핑은 최종 격리 경로에 보존하고 요약 증거에 해시를 남깁니다. `/tmp` 장기 보존은 보장하지 않습니다. 기존 S4-1 로그와 결과 파일의 해시 일치를 다시 확인했습니다. 기존 S4-1 코드 경로도 새 JAR·첫 JVM에서 smoke PASS를 확인했습니다.

## 남은 검증·판정

이번 자동 시험은 **재시작 영속성 + 보호 API/대표 간접 이벤트 통과**, 사용자 요청의 **접속 플레이어 건축 행동은 미실행**입니다. 해당 미실행을 실패/성공으로 바꾸지 않으며 S4 전체 완료로 판정하지 않습니다. 다음 시험은 인증된 클라이언트를 이용한 W1/W2/W3 설치·파괴/권한/bypass, 기타 간접 변경, 장애/손상·동시 요청·IDLE/반복 로드/부하입니다. PostgreSQL·NPC·퀘스트·AI·경제·실운영 구성은 이번 범위에서 구현하지 않았습니다.

## 문서·보안 검사

2026-10-26: Markdown 37개·상대 링크 368개(앵커 2개 포함)·표 92개 검사 오류 0. Python 구문/도움말·EULA 인자 누락 시 exit 2/서버 미생성, JAR/공식 아티팩트/JUnit/요약·로그 해시 대조·S4-1 증거 보존 검사 통과. `git diff --check`·staged diff/파일 범위 검사 통과, Gitleaks staged 유출 없음. 원본 로그/월드/JAR/SQL은 커밋 대상에서 제외했으며 Git 보안 Hook을 유지합니다. 결과 커밋과 push는 Git 이력·작업 보고에서 확인합니다.
