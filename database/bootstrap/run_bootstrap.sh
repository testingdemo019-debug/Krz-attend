#!/usr/bin/env bash
# Database bootstrap for KRZ Attend - YOU run this in your own terminal; it asks for the postgres password.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../deploy/scripts/lib/env.sh
. "$HERE/../../deploy/scripts/lib/env.sh"

CHECK_ONLY=""; RESET=""; DB_HOST_OPT="localhost"; PORT_OPT=""; SUPERUSER="postgres"
for arg in "$@"; do
  case "$arg" in
    --help|-h)
      cat <<'EOF'
run_bootstrap - create the KRZ Attend roles, database, schema and extensions (idempotent; never drops anything).
  --check-only              only write the non-secret report database/bootstrap/check-report.txt
  --reset-role-passwords    generate new passwords for krz_* roles that exist but have no password in deploy/.env
  --host=HOST               PostgreSQL host (default localhost)
  --port=PORT               PostgreSQL port (default DB_PORT from deploy/.env, else 5432)
  --superuser=NAME          superuser to connect as (default postgres)
  --help                    show this help
The superuser password is typed at a hidden prompt, used through a temporary password file (mode 600) that is
deleted on exit, and never stored. Role passwords are reused from deploy/.env; blank ones are generated into it.
EOF
      exit 0 ;;
    --check-only) CHECK_ONLY=1 ;;
    --reset-role-passwords) RESET=1 ;;
    --host=*) DB_HOST_OPT="${arg#*=}" ;;
    --port=*) PORT_OPT="${arg#*=}" ;;
    --superuser=*) SUPERUSER="${arg#*=}" ;;
    *) echo "Unknown option '$arg'. Use --help." >&2; exit 2 ;;
  esac
done

command -v psql >/dev/null 2>&1 || { echo "psql not found: sudo apt install postgresql-client" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is needed to compute SCRAM verifiers." >&2; exit 1; }

umask 077
ENV_FILE="$(krz_env_path)"
PORT="${PORT_OPT:-$(krz_env_get "$ENV_FILE" DB_PORT 5432)}"
ROLES=(krz_owner krz_app krz_worker)
declare -A ENV_KEY=([krz_owner]=DB_OWNER_PASSWORD [krz_app]=DB_APP_PASSWORD [krz_worker]=DB_WORKER_PASSWORD)
declare -A PSQL_VAR=([krz_owner]=owner_pwd [krz_app]=app_pwd [krz_worker]=worker_pwd)

TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT INT TERM

psql_su() { psql -X -v ON_ERROR_STOP=1 -h "$DB_HOST_OPT" -p "$PORT" -U "$SUPERUSER" "$@"; }

# SCRAM-SHA-256 verifier (the format PostgreSQL stores), computed client-side so the server and its log never see
# the password. stdin carries the password (line 1) and, for "check", the stored verifier (line 2), so neither
# appears on a command line.
scram() { # printf 'pwd\n' | scram new  |  printf 'pwd\nverifier\n' | scram check
  python3 -I -c '
import base64, hashlib, hmac, os, re, sys
lines = sys.stdin.read().split("\n")
password = lines[0].encode()
def keys(salt, it):
    sp = hashlib.pbkdf2_hmac("sha256", password, salt, it)
    ck = hmac.new(sp, b"Client Key", hashlib.sha256).digest()
    sk = hmac.new(sp, b"Server Key", hashlib.sha256).digest()
    return base64.b64encode(hashlib.sha256(ck).digest()).decode(), base64.b64encode(sk).decode()
if sys.argv[1] == "new":
    salt = os.urandom(16); stored, server = keys(salt, 4096)
    sys.stdout.write("SCRAM-SHA-256$4096:%s$%s:%s" % (base64.b64encode(salt).decode(), stored, server))
else:
    m = re.fullmatch(r"SCRAM-SHA-256\$(\d+):([^$]+)\$([^:]+):(.+)", lines[1] if len(lines) > 1 else "")
    ok = bool(m) and keys(base64.b64decode(m.group(2)), int(m.group(1))) == (m.group(3), m.group(4))
    sys.exit(0 if ok else 1)
' "$1"
}

read -rsp "Password for PostgreSQL user '$SUPERUSER' on $DB_HOST_OPT:$PORT (hidden): " SU_PWD; echo
escaped="${SU_PWD//\\/\\\\}"; escaped="${escaped//:/\\:}"
printf '*:*:*:%s:%s\n' "$SUPERUSER" "$escaped" > "$TMP_DIR/pgpass"
chmod 600 "$TMP_DIR/pgpass"
unset SU_PWD escaped
export PGPASSFILE="$TMP_DIR/pgpass" PGCLIENTENCODING=UTF8 PGCONNECT_TIMEOUT=10

if [[ -n "$CHECK_ONLY" ]]; then
  report="$HERE/check-report.txt"
  {
    echo "# KRZ Attend - PostgreSQL check report (contains no secrets)"
    echo "# Generated $(date --iso-8601=seconds) by run_bootstrap --check-only; host $DB_HOST_OPT, port $PORT; $(psql --version)"
    psql_su -q -d postgres -f "$HERE/00_check_prerequisites.sql"
    echo "== deploy/.env role passwords (values never shown)"
    for role in "${ROLES[@]}"; do
      if [[ -n "$(krz_env_get "$ENV_FILE" "${ENV_KEY[$role]}")" ]]; then state=set; else state=blank; fi
      printf '%-22s %s\n' "${ENV_KEY[$role]}" "$state"
    done
  } > "$report.tmp"
  chmod 644 "$report.tmp"; mv -f "$report.tmp" "$report"
  echo "Check report written to database/bootstrap/check-report.txt"
  exit 0
fi

[[ -f "$ENV_FILE" ]] || cp "$(dirname "$ENV_FILE")/.env.example" "$ENV_FILE"
chmod 600 "$ENV_FILE"
for role in "${ROLES[@]}"; do
  user_key="${ENV_KEY[$role]/_PASSWORD/_USER}"
  configured="$(krz_env_get "$ENV_FILE" "$user_key" "$role")"
  [[ "$configured" == "$role" ]] || { echo "$user_key is '$configured' in deploy/.env, but the bootstrap creates '$role'." >&2; exit 1; }
done

# Existing verifiers decide whether a password must be (re)applied; they never leave this process.
declare -A EXISTING=()
while IFS='|' read -r name verifier; do
  [[ -n "$name" ]] && EXISTING[$name]="${verifier%$'\r'}"  # psql under Git Bash ends lines with CR LF
done < <(psql_su -d postgres -A -t -c "SELECT rolname || '|' || coalesce(rolpassword, '') FROM pg_authid WHERE rolname IN ('krz_owner','krz_app','krz_worker')")

missing=()
for role in "${ROLES[@]}"; do
  if [[ -v "EXISTING[$role]" && -z "$(krz_env_get "$ENV_FILE" "${ENV_KEY[$role]}")" ]]; then missing+=("$role"); fi
done
if [[ ${#missing[@]} -gt 0 && -z "$RESET" ]]; then
  echo "These roles already exist, but deploy/.env has no password for them: ${missing[*]}"
  echo "Nothing was changed. To give them new passwords (written into deploy/.env), run again with --reset-role-passwords."
  exit 2
fi

: > "$TMP_DIR/vars.sql"
for role in "${ROLES[@]}"; do
  key="${ENV_KEY[$role]}"
  password="$(krz_env_get "$ENV_FILE" "$key")"
  if [[ -z "$password" ]]; then
    password="$(krz_secret 32 urlsafe)"
    krz_env_set "$ENV_FILE" "$key" "$password"
  fi
  [[ "$password" =~ ^[!-~]{16,}$ ]] || { echo "$key in deploy/.env must be at least 16 printable ASCII characters without spaces." >&2; exit 1; }
  if [[ -v "EXISTING[$role]" ]] && printf '%s\n%s\n' "$password" "${EXISTING[$role]}" | scram check; then
    verifier="${EXISTING[$role]}"
  else
    verifier="$(printf '%s\n' "$password" | scram new)"
  fi
  printf "\\\\set %s '%s'\n" "${PSQL_VAR[$role]}" "$verifier" >> "$TMP_DIR/vars.sql"
done
unset password verifier
chmod 600 "$ENV_FILE"

# Paths as psql sees them (cygpath only exists under Git Bash/Cygwin, where psql is a Windows program).
sql_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cat > "$TMP_DIR/run.sql" <<EOF
\\i '$(sql_path "$TMP_DIR/vars.sql")'
\\i '$(sql_path "$HERE/00_check_prerequisites.sql")'
\\i '$(sql_path "$HERE/01_create_roles.sql")'
\\i '$(sql_path "$HERE/02_create_database.sql")'
\\c krz_attend
\\i '$(sql_path "$HERE/03_extensions_and_schema.sql")'
EOF
# Re-runs print "already exists, skipping" notices; keep only warnings and errors.
PGOPTIONS="-c client_min_messages=warning" psql_su -q -d postgres -f "$TMP_DIR/run.sql" > /dev/null
echo "Roles ready; passwords in deploy/.env"
