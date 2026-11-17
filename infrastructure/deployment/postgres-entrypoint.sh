#!/bin/sh
# 기존 정지/복원 PGDATA만 사용합니다. initdb/chown/비밀번호 변경은 실행하지 않습니다.
set -eu
umask 077
refuse() { echo 'HEARTH_PG_LAUNCH_REFUSED' >&2; exit 2; }
[ "$(id -u)" != 0 ] || refuse
[ "${HEARTH_OPERATING_APPROVAL:-}" = reviewed-and-approved ] || refuse
[ "${PGDATA:-}" = /data ] || refuse
case "${HEARTH_PG_PORT:-}" in ''|*[!0-9]*) refuse;; esac
[ "$HEARTH_PG_PORT" -ge 1024 ] && [ "$HEARTH_PG_PORT" -le 65535 ] || refuse
[ -f /data/PG_VERSION ] && [ ! -L /data/PG_VERSION ] && [ ! -e /data/postmaster.pid ] && [ ! -L /data/postmaster.pid ] || refuse
major=$(cat /data/PG_VERSION)
case "$major" in ''|*[!0-9]*) refuse;; esac
binary_major=$(postgres --version | sed -n 's/^postgres (PostgreSQL) \([0-9][0-9]*\).*/\1/p')
[ "$major" = "$binary_major" ] || refuse
exec postgres -D /data -c listen_addresses=127.0.0.1 -c "port=$HEARTH_PG_PORT" -c unix_socket_directories=/run/hearth
