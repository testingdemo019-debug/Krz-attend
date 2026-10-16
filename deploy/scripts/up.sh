#!/usr/bin/env bash
# Starts KRZ Attend's containers: renders deploy/redis/users.acl from deploy/.env, creates the data folders (owner
# 10001, group DEPLOY_GID, mode 2770), then runs docker compose up and waits until the services are healthy.
set -euo pipefail
# shellcheck source=lib/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/env.sh"

DEV=""; CPU=""; SERVICES=()
for arg in "$@"; do
  case "$arg" in
    --help|-h)
      cat <<'EOF'
up - start KRZ Attend's containers (idempotent).
  --dev               add docker-compose.dev.yml (ports on 127.0.0.1 only, for host development)
  --cpu               add docker-compose.cpu.yml (worker without GPU)
  --services=a,b      start only these services (e.g. --services=redis for host development)
Renders deploy/redis/users.acl from deploy/.env (never committed) and creates the data/ folders first.
Creating the folders with owner 10001 needs sudo once.
EOF
      exit 0 ;;
    --dev) DEV=1 ;;
    --cpu) CPU=1 ;;
    --services=*) IFS=',' read -r -a SERVICES <<< "${arg#*=}" ;;
    *) echo "Unknown option '$arg'. Use --help." >&2; exit 2 ;;
  esac
done

DEPLOY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$(krz_env_path)"
[[ -f "$ENV_FILE" ]] || { echo "deploy/.env is missing. Run deploy/scripts/init-env.sh first." >&2; exit 1; }
GID="$(krz_env_get "$ENV_FILE" DEPLOY_GID 1000)"

# The containers run as 10001:<DEPLOY_GID>; group read lets Redis read the ACL file (DECISIONS).
krz_render_template "$DEPLOY/redis/users.acl.template" "$DEPLOY/redis/users.acl" "$ENV_FILE" 640
chgrp "$GID" "$DEPLOY/redis/users.acl" 2>/dev/null || true
echo "Rendered deploy/redis/users.acl."

DATA_SETTING="$(krz_env_get "$ENV_FILE" DATA_DIR ../data)"
case "$DATA_SETTING" in /*) DATA_DIR="$DATA_SETTING" ;; *) DATA_DIR="$DEPLOY/$DATA_SETTING" ;; esac
for sub in crops uploads spool redis trt_cache models backups logs/backend logs/worker logs/nginx; do
  mkdir -p "$DATA_DIR/$sub"
done
DATA_DIR="$(cd "$DATA_DIR" && pwd)"
if [[ "$(stat -c %u "$DATA_DIR/crops")" != "10001" ]]; then
  echo "Setting owner 10001:$GID and mode 2770 on $DATA_DIR (sudo)."
  sudo chown -R "10001:$GID" "$DATA_DIR"
  sudo find "$DATA_DIR" -type d -exec chmod 2770 {} +
fi
echo "Data folders ready under $DATA_DIR."

FILES=(-f "$DEPLOY/docker-compose.yml")
[[ -n "$DEV" ]] && FILES+=(-f "$DEPLOY/docker-compose.dev.yml")
[[ -n "$CPU" ]] && FILES+=(-f "$DEPLOY/docker-compose.cpu.yml")
docker compose --project-directory "$DEPLOY" --env-file "$ENV_FILE" "${FILES[@]}" up -d --wait "${SERVICES[@]}"
echo "Containers are up and healthy."
