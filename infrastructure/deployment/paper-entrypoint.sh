#!/bin/sh
# 구성 생성/덮어쓰기, EULA 동의, DB 초기화, HOLD 해제를 하지 않습니다.
set -eu
umask 077
refuse() { echo 'HEARTH_PAPER_LAUNCH_REFUSED' >&2; exit 2; }
[ "$(id -u)" != 0 ] || refuse
[ "${HEARTH_OPERATING_APPROVAL:-}" = reviewed-and-approved ] || refuse
case "${HEARTH_MODE:-}" in story|economy) ;; *) refuse;; esac
case "${HEARTH_HEAP_MIB:-}" in ''|*[!0-9]*) refuse;; esac
case "${HEARTH_JAVA_CPUS:-}" in ''|*[!0-9]*) refuse;; esac
[ "$HEARTH_HEAP_MIB" -gt 0 ] && [ "$HEARTH_JAVA_CPUS" -gt 0 ] || refuse
java -version 2>&1 | head -n 1 | grep -Eq 'version "25([.\"]|$)' || refuse
[ -d /run/hearth ] && [ ! -e /run/hearth/boot-id ] || refuse
mkdir /run/hearth/tmp
launch=$(java -Xmx128m -XX:ActiveProcessorCount=1 -Djava.io.tmpdir=/run/hearth/tmp -cp /opt/hearth/classes io.github.jesterportfolio.hearth.administration.OperatorLaunchCheck /data /review/deployment.properties "${HEARTH_MANIFEST_SHA256:-}" "$HEARTH_MODE") || refuse
server_jar=$(printf '%s\n' "$launch" | sed -n '1p')
password_environment=$(printf '%s\n' "$launch" | sed -n '2p')
[ -f /run/secrets/hearth_db_password ] && [ ! -L /run/secrets/hearth_db_password ] || refuse
secret_bytes=$(wc -c < /run/secrets/hearth_db_password)
[ "$secret_bytes" -gt 0 ] && [ "$secret_bytes" -le 4096 ] || refuse
invalid_bytes=$(LC_ALL=C tr -d '\040-\176\n' < /run/secrets/hearth_db_password | wc -c)
linebreaks=$(tr -cd '\n' < /run/secrets/hearth_db_password | wc -c)
[ "$invalid_bytes" -eq 0 ] && [ "$linebreaks" -le 1 ] || refuse
password=$(cat /run/secrets/hearth_db_password)
# 검토한 한 줄 ASCII 비밀번호. 명령 치환으로 제거된 마지막 개행 1개는 허용합니다.
case "$password" in ''|*'
'*) refuse;; esac
export "$password_environment=$password"
unset password
[ -r /proc/sys/kernel/random/uuid ] || refuse
boot=$(cat /proc/sys/kernel/random/uuid)
printf '%s\n' "$boot" > /run/hearth/boot-id
exec java -Xms128m "-Xmx${HEARTH_HEAP_MIB}m" "-XX:ActiveProcessorCount=${HEARTH_JAVA_CPUS}" -Djava.io.tmpdir=/run/hearth/tmp "-Dhearth.runtime.boot=$boot" -jar "$server_jar" --nogui
