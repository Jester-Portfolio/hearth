#!/bin/sh
# 후보 검사 외 데이터 생성·EULA 동의·HOLD 해제·DB 초기화를 하지 않습니다.
set -eu
umask 077
refuse() { echo 'HEARTH_NETWORK_LAUNCH_REFUSED' >&2; exit 2; }
[ "$(id -u)" != 0 ] || refuse
[ "${HEARTH_NETWORK_APPROVAL:-}" = reviewed-and-approved ] || refuse
case "${HEARTH_ROLE:-}" in proxy|lobby|economy) ;; *) refuse;; esac
case "${HEARTH_HEAP_MIB:-}:${HEARTH_CPUS:-}" in *[!0-9:]*|:|:*) refuse;; esac
[ "$HEARTH_HEAP_MIB" -gt 0 ] && [ "$HEARTH_CPUS" -gt 0 ] || refuse
[ -d /run/hearth ] && [ ! -e /run/hearth/boot-id ] || refuse
jar=$(java -Xmx128m -XX:ActiveProcessorCount=1 -Djava.io.tmpdir=/run/hearth /review/NetworkLaunchCheck.java /data "/review/${HEARTH_ROLE}.properties" "${HEARTH_LAUNCH_SHA256:-}" "$HEARTH_ROLE") || refuse
. /review/runtime-env.sh
hearth_load_env /data/runtime.env || refuse
[ -r /proc/sys/kernel/random/uuid ] || refuse
boot=$(cat /proc/sys/kernel/random/uuid)
printf '%s\n' "$boot" > /run/hearth/boot-id
if [ "$HEARTH_ROLE" = proxy ]; then
 exec java "-Xmx${HEARTH_HEAP_MIB}m" "-XX:ActiveProcessorCount=${HEARTH_CPUS}" -Djava.io.tmpdir=/run/hearth "-Dhearth.runtime.boot=$boot" -jar "$jar"
fi
[ "$(cat /data/eula.txt)" = eula=true ] || refuse
exec java "-Xmx${HEARTH_HEAP_MIB}m" "-XX:ActiveProcessorCount=${HEARTH_CPUS}" -Djava.io.tmpdir=/run/hearth "-Dhearth.runtime.boot=$boot" -jar "$jar" --nogui
