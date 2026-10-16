#!/usr/bin/env bash
# Restores a backup into a NEW database (default krz_attend_restore_<timestamp>); never overwrites anything.
# YOU run this in your own terminal: creating a database needs the postgres password (hidden prompt, never stored).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../deploy/scripts/lib/env.sh
. "$HERE/../../deploy/scripts/lib/env.sh"

FILE=""; TARGET=""; SWITCH=""; PORT_OPT=""; SUPERUSER=postgres
for arg in "$@"; do
  case "$arg" in
    --help|-h)
      cat <<'EOF'
restore - restore a pg_dump backup into a NEW database, then explain (or, with --switch, perform) the switch.
  --file=PATH      the .dump or .dump.gpg to restore (default: the newest db-*.dump* in BACKUP_DIR)
  --target=NAME    database to create (default krz_attend_restore_<yyyyMMdd_HHmm>); must not exist
  --switch         after the restore, rename krz_attend -> krz_attend_before_<ts> and the target -> krz_attend
                   (asks you to type SWITCH; stop the backend and worker first). Nothing is dropped.
  --port=PORT      PostgreSQL port (default DB_PORT from deploy/.env)
  --superuser=NAME superuser for CREATE DATABASE (default postgres)
EOF
      exit 0 ;;
    --file=*) FILE="${arg#*=}" ;;
    --target=*) TARGET="${arg#*=}" ;;
    --switch) SWITCH=1 ;;
    --port=*) PORT_OPT="${arg#*=}" ;;
    --superuser=*) SUPERUSER="${arg#*=}" ;;
    *) echo "Unknown option '$arg'. Use --help." >&2; exit 2 ;;
  esac
done

umask 077
ENV_FILE="$(krz_env_path)"
DEPLOY_DIR="$(dirname "$ENV_FILE")"
PORT="${PORT_OPT:-$(krz_env_get "$ENV_FILE" DB_PORT 5432)}"
LIVE_DB="$(krz_env_get "$ENV_FILE" DB_NAME krz_attend)"
STAMP="$(date +%Y%m%d_%H%M)"
TARGET="${TARGET:-krz_attend_restore_$STAMP}"
[[ "$TARGET" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || { echo "Invalid database name '$TARGET'." >&2; exit 1; }
BACKUP_DIR_SETTING="$(krz_env_get "$ENV_FILE" BACKUP_DIR ../data/backups)"
case "$BACKUP_DIR_SETTING" in /*) BACKUP_DIR="$BACKUP_DIR_SETTING" ;; *) BACKUP_DIR="$DEPLOY_DIR/$BACKUP_DIR_SETTING" ;; esac
if [[ -z "$FILE" ]]; then
  FILE="$(find "$BACKUP_DIR" -maxdepth 1 -type f -regextype posix-extended -regex '.*/db-[0-9]{8}-[0-9]{4}\.dump(\.gpg)?' | sort -r | head -n1)"
  [[ -n "$FILE" ]] || { echo "No db-*.dump found in $BACKUP_DIR." >&2; exit 1; }
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM
echo "Restoring $(basename "$FILE") into the new database '$TARGET' on localhost:$PORT."
read -rsp "Password for PostgreSQL user '$SUPERUSER' (hidden): " SU_PWD; echo
escaped="${SU_PWD//\\/\\\\}"; escaped="${escaped//:/\\:}"
printf '*:*:*:%s:%s\n' "$SUPERUSER" "$escaped" > "$TMP_DIR/pgpass"
unset SU_PWD escaped
export PGPASSFILE="$TMP_DIR/pgpass" PGCONNECT_TIMEOUT=10 PGCLIENTENCODING=UTF8
psql_su() { psql -X -q -v ON_ERROR_STOP=1 -h localhost -p "$PORT" -U "$SUPERUSER" "$@"; }

DUMP="$FILE"
if [[ "$FILE" == *.gpg ]]; then
  passphrase="$(krz_env_get "$ENV_FILE" BACKUP_PASSPHRASE)"
  [[ -n "$passphrase" ]] || { echo "The backup is encrypted, but BACKUP_PASSPHRASE is empty in deploy/.env." >&2; exit 1; }
  DUMP="$TMP_DIR/restore.dump"
  gpg --batch --yes --pinentry-mode loopback --passphrase-fd 3 -o "$DUMP" --decrypt "$FILE" 3<<<"$passphrase"
  unset passphrase
fi

if [[ "$(psql_su -A -t -d postgres -c "SELECT count(*) FROM pg_database WHERE datname = '$TARGET'")" != "0" ]]; then
  echo "Database '$TARGET' already exists; nothing was changed. Choose another --target." >&2; exit 1
fi
psql_su -d postgres \
  -c "CREATE DATABASE $TARGET OWNER krz_owner ENCODING 'UTF8' TEMPLATE template0" \
  -c "REVOKE ALL ON DATABASE $TARGET FROM PUBLIC" \
  -c "GRANT CONNECT ON DATABASE $TARGET TO krz_app, krz_worker"
psql_su -d "$TARGET" -c "REVOKE CREATE ON SCHEMA public FROM PUBLIC"
if ! pg_restore --exit-on-error --single-transaction -h localhost -p "$PORT" -U "$SUPERUSER" -d "$TARGET" "$DUMP"; then
  echo "Restore failed. The incomplete database '$TARGET' was left in place; remove it with: DROP DATABASE $TARGET;" >&2
  exit 1
fi
echo "Restored into '$TARGET'. Check it with: psql -h localhost -p $PORT -U krz_app -d $TARGET -f database/scripts/verify.sql"

if [[ -n "$SWITCH" ]]; then
  BEFORE="${LIVE_DB}_before_$STAMP"
  echo "SWITCH: '$LIVE_DB' will be renamed to '$BEFORE' and '$TARGET' to '$LIVE_DB'. Stop the backend and the worker first."
  read -rp "Type SWITCH to continue: " answer
  [[ "$answer" == SWITCH ]] || { echo "Switch cancelled; the restored database stays as it is."; exit 1; }
  sessions="$(psql_su -A -t -d postgres -c "SELECT count(*) FROM pg_stat_activity WHERE datname IN ('$LIVE_DB', '$TARGET')")"
  [[ "$sessions" == "0" ]] || { echo "There are open connections to '$LIVE_DB' or '$TARGET'. Stop the backend and the worker first." >&2; exit 1; }
  psql_su -d postgres -c "ALTER DATABASE $LIVE_DB RENAME TO $BEFORE" -c "ALTER DATABASE $TARGET RENAME TO $LIVE_DB"
  echo "Switched. The previous database is kept as '$BEFORE' (drop it yourself once you are satisfied)."
else
  echo "To switch to it (backend and worker stopped), as postgres:"
  echo "  ALTER DATABASE $LIVE_DB RENAME TO ${LIVE_DB}_before_$STAMP;"
  echo "  ALTER DATABASE $TARGET RENAME TO $LIVE_DB;"
fi
