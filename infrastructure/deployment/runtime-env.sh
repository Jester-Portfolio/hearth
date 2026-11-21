# Source-only helpers. No eval or Compose dotenv expansion; reviewed bytes are exported literally.
hearth_load_env() {
  file=$1
  [ -f "$file" ] && [ ! -L "$file" ] || return 2
  seen_forward=0; seen_relay=0; seen_db=0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|\#*) continue;; esac
    case "$line" in *=*) ;; *) return 2;; esac
    key=${line%%=*}; value=${line#*=}
    [ -n "$value" ] || return 2
    case "$key" in
      HEARTH_VELOCITY_SECRET) [ "$seen_forward" = 0 ] || return 2; seen_forward=1;;
      HEARTH_NETWORK_SECRET) [ "$seen_relay" = 0 ] || return 2; seen_relay=1;;
      HEARTH_DB_PASSWORD) [ "$seen_db" = 0 ] || return 2; seen_db=1;;
      *) return 2;;
    esac
    export "$key=$value" || return 2
  done < "$file"
  [ "$seen_forward:$seen_relay:$seen_db" = 1:1:1 ]
}

hearth_pg_manifest() {
  root=$1; manifest=$2; expected=$3
  command -v sha256sum >/dev/null 2>&1 || return 2
  [ -f "$manifest" ] && [ ! -L "$manifest" ] || return 2
  [ "$(sha256sum "$manifest" | cut -d ' ' -f 1)" = "$expected" ] || return 2
  format=; role=; accepted=; count=0
  while IFS='=' read -r key value || [ -n "$key" ]; do
    case "$key" in
      format) [ -z "$format" ] || return 2; format=$value;;
      role) [ -z "$role" ] || return 2; role=$value;;
      acceptance-complete) [ -z "$accepted" ] || return 2; accepted=$value;;
      jar) [ -z "$value" ] || return 2;;
      file.*)
        name=${key#file.}
        case "$name" in ''|/*|*..*|*//*|*[!A-Za-z0-9_./-]*) return 2;; esac
        case "$value" in *[!a-f0-9]*) return 2;; esac
        [ "${#value}" = 64 ] || return 2
        path=$root/$name; parent=$path
        while [ "$parent" != "$root" ]; do
          [ ! -L "$parent" ] || return 2; parent=$(dirname "$parent")
        done
        [ -f "$path" ] && [ "$(sha256sum "$path" | cut -d ' ' -f 1)" = "$value" ] || return 2
        count=$((count+1)); [ "$count" -le 128 ] || return 2;;
      *) return 2;;
    esac
  done < "$manifest"
  [ "$format:$role:$accepted" = 2:postgres:true ] && [ "$count" -gt 0 ]
}
