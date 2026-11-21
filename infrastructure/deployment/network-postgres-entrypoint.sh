#!/bin/sh
set -eu
umask 077
refuse() { echo 'HEARTH_NETWORK_PG_LAUNCH_REFUSED' >&2; exit 2; }
[ "${HEARTH_NETWORK_APPROVAL:-}" = reviewed-and-approved ] || refuse
. /review/runtime-env.sh
hearth_pg_manifest /data /review/postgres.properties "${HEARTH_LAUNCH_SHA256:-}" || refuse
exec /bin/sh /review/postgres-entrypoint.sh
