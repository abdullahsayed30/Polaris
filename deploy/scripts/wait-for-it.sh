#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: wait-for-it host:port [--timeout=SECONDS] [--strict] [--quiet] [-- command...]
USAGE
}

if [[ $# -lt 1 ]]; then
  usage
  exit 2
fi

target="$1"
shift
timeout=60
strict=false
quiet=false
command=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--timeout)
      timeout="$2"
      shift 2
      ;;
    --timeout=*)
      timeout="${1#*=}"
      shift
      ;;
    --strict)
      strict=true
      shift
      ;;
    -q|--quiet)
      quiet=true
      shift
      ;;
    --)
      shift
      command=("$@")
      break
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

if [[ "$target" != *:* ]]; then
  usage
  exit 2
fi

host="${target%:*}"
port="${target##*:}"
deadline=$((SECONDS + timeout))

log() {
  if [[ "$quiet" == false ]]; then
    printf '%s\n' "$*" >&2
  fi
}

is_available() {
  (exec 3<>"/dev/tcp/${host}/${port}") >/dev/null 2>&1
}

log "waiting for ${host}:${port}"

while ! is_available; do
  if (( SECONDS >= deadline )); then
    log "timed out waiting for ${host}:${port}"
    if [[ ${#command[@]} -gt 0 && "$strict" == false ]]; then
      exec "${command[@]}"
    fi
    exit 124
  fi
  sleep 1
done

log "${host}:${port} is available"

if [[ ${#command[@]} -gt 0 ]]; then
  exec "${command[@]}"
fi
