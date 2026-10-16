#!/usr/bin/env bash
# Daily database backup: pg_dump -Fc as krz_owner into BACKUP_DIR, optional gpg encryption, retention.
# Writes BACKUP_DIR/last-backup.json (read by Settings > System). Prints no secrets.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../deploy/scripts/lib/env.sh
. "$HERE/../../deploy/scripts/lib/env.sh"

for arg in "$@"; do
  case "$arg" in
    --help|-h)
      cat <<'EOF'
backup - pg_dump -Fc of krz_attend as krz_owner into BACKUP_DIR (deploy/.env), named db-<yyyyMMdd-HHmm>.dump.
If BACKUP_PASSPHRASE is set, the dump is encrypted with gpg (AES256) and only the .dump.gpg is kept.
Dumps older than BACKUP_KEEP_DAYS are deleted. The result is written to BACKUP_DIR/last-backup.json.
EOF
      exit 0 ;;
    *) echo "Unknown option '$arg'. Use --help." >&2; exit 2 ;;
  esac
done

umask 027
ENV_FILE="$(krz_env_path)"
DEPLOY_DIR="$(dirname "$ENV_FILE")"
PORT="$(krz_env_get "$ENV_FILE" DB_PORT 5432)"
DB_NAME="$(krz_env_get "$ENV_FILE" DB_NAME krz_attend)"
OWNER="$(krz_env_get "$ENV_FILE" DB_OWNER_USER krz_owner)"
KEEP_DAYS="$(krz_env_get "$ENV_FILE" BACKUP_KEEP_DAYS 14)"
BACKUP_DIR_SETTING="$(krz_env_get "$ENV_FILE" BACKUP_DIR ../data/backups)"
case "$BACKUP_DIR_SETTING" in /*) BACKUP_DIR="$BACKUP_DIR_SETTING" ;; *) BACKUP_DIR="$DEPLOY_DIR/$BACKUP_DIR_SETTING" ;; esac
mkdir -p "$BACKUP_DIR"
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"

STAMP="$(date +%Y%m%d-%H%M)"
DUMP="$BACKUP_DIR/db-$STAMP.dump"
STARTED="$(date --iso-8601=seconds)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

status=FAILED; final=""; encrypted=false; error=""
run() {
  local owner_pwd
  owner_pwd="$(krz_env_get "$ENV_FILE" DB_OWNER_PASSWORD)"
  [[ -n "$owner_pwd" ]] || { error="DB_OWNER_PASSWORD is empty in deploy/.env (run database/bootstrap/run_bootstrap.sh first)."; return 1; }
  owner_pwd="${owner_pwd//\\/\\\\}"; owner_pwd="${owner_pwd//:/\\:}"
  ( umask 077; printf '*:*:*:%s:%s\n' "$OWNER" "$owner_pwd" > "$TMP_DIR/pgpass" )
  unset owner_pwd
  if ! out="$(PGPASSFILE="$TMP_DIR/pgpass" PGCONNECT_TIMEOUT=10 pg_dump -Fc -h localhost -p "$PORT" -U "$OWNER" -d "$DB_NAME" -f "$DUMP" 2>&1)"; then
    error="pg_dump failed: $out"; return 1
  fi
  final="$DUMP"
  local passphrase
  passphrase="$(krz_env_get "$ENV_FILE" BACKUP_PASSPHRASE)"
  if [[ -n "$passphrase" ]]; then
    command -v gpg >/dev/null 2>&1 || { error="BACKUP_PASSPHRASE is set but gpg was not found."; return 1; }
    # The passphrase goes through file descriptor 3, never through the command line.
    if ! out="$(gpg --batch --yes --pinentry-mode loopback --passphrase-fd 3 --symmetric --cipher-algo AES256 \
                  -o "$DUMP.gpg" "$DUMP" 2>&1 3<<<"$passphrase")"; then
      error="gpg failed: $out"; return 1
    fi
    rm -f "$DUMP"; final="$DUMP.gpg"; encrypted=true
  fi
  unset passphrase
  find "$BACKUP_DIR" -maxdepth 1 -type f -regextype posix-extended -regex '.*/db-[0-9]{8}-[0-9]{4}\.dump(\.gpg)?' \
       -mtime +"$KEEP_DAYS" -delete
  status=SUCCESS
}

run
size=0; name=null
if [[ "$status" == SUCCESS ]]; then
  size="$(stat -c %s "$final")"; name="\"$(basename "$final")\""
  echo "Backup written: $(basename "$final") ($((size / 1024)) KB)"
else
  echo "Backup failed: $error"
fi
error_json="null"
[[ -n "$error" ]] && error_json="\"$(printf '%s' "$error" | tr -d '\r\n"\\' | cut -c1-500)\""
cat > "$BACKUP_DIR/last-backup.json" <<EOF
{
  "status": "$status",
  "file": $name,
  "sizeBytes": $size,
  "encrypted": $encrypted,
  "startedAt": "$STARTED",
  "finishedAt": "$(date --iso-8601=seconds)",
  "error": $error_json
}
EOF
[[ "$status" == SUCCESS ]]
