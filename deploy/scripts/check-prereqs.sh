#!/usr/bin/env bash
# Reports whether this Linux machine has everything KRZ Attend needs. Installs nothing.
set -uo pipefail
# shellcheck source=lib/env.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/env.sh"

CUDA_TEST_IMAGE="nvidia/cuda:12.9.1-base-ubuntu22.04"
MIN_FREE_GB=10
RECOMMENDED_FREE_GB=25
WITH_GPU_DOCKER=""
DB_PORT_OPT=""

for arg in "$@"; do
  case "$arg" in
    --help|-h)
      cat <<'EOF'
check-prereqs - report the prerequisites for KRZ Attend (nothing is installed).
  --with-gpu-docker   also run the GPU-in-Docker test (pulls the pinned nvidia/cuda base image, ~80 MB)
  --db-port=N         PostgreSQL port to probe (default: DB_PORT from deploy/.env, else 5432)
  --help              show this help
The PostgreSQL server version comes from database/bootstrap/run_bootstrap.sh --check-only, which you run yourself.
EOF
      exit 0 ;;
    --with-gpu-docker) WITH_GPU_DOCKER=1 ;;
    --db-port=*) DB_PORT_OPT="${arg#*=}" ;;
    *) echo "Unknown option '$arg'. Use --help." >&2; exit 2 ;;
  esac
done

MISSING=0
FIXES=()
report() { # item status detail [fix]
  printf '%-28s %-8s %s\n' "$1" "$2" "$3"
  [[ "$2" == "MISSING" ]] && MISSING=1
  if [[ -n "${4:-}" && ( "$2" == "MISSING" || "$2" == "WARN" ) ]]; then FIXES+=("$1: $4"); fi
}
have() { command -v "$1" >/dev/null 2>&1; }

printf '%-28s %-8s %s\n' "Item" "Status" "Detail"
printf '%-28s %-8s %s\n' "----" "------" "------"

# --- OS ---
if [[ -r /etc/os-release ]]; then os_name="$(. /etc/os-release && printf '%s' "$PRETTY_NAME")"; else os_name="$(uname -s)"; fi
report "Operating system" OK "$os_name ($(uname -r))"

# --- Docker + Compose ---
if have docker; then
  server="$(docker version --format '{{.Server.Version}}' 2>/dev/null)"
  if [[ -n "$server" ]]; then report "Docker" OK "engine $server"
  else report "Docker" MISSING "engine not reachable" "sudo systemctl start docker; add the deploy user to the docker group."; fi
  compose="$(docker compose version --short 2>/dev/null)"
  if [[ -n "$compose" ]]; then report "Docker Compose" OK "v$compose"
  else report "Docker Compose" MISSING "plugin not found" "sudo apt install docker-compose-plugin"; fi
else
  report "Docker" MISSING "docker not found" "Install Docker Engine from docs.docker.com/engine/install/ubuntu (not the snap)."
fi

# --- Java >= 21 ---
if have java; then
  banner="$(java -version 2>&1 | head -n1)"
  major="$(printf '%s' "$banner" | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
  if [[ "${major:-0}" -ge 21 ]]; then report "Java (>= 21)" OK "$banner"
  else report "Java (>= 21)" MISSING "$banner" "sudo apt install openjdk-21-jdk-headless"; fi
  if [[ -n "${JAVA_HOME:-}" && ! -x "$JAVA_HOME/bin/java" ]]; then
    report "JAVA_HOME" WARN "$JAVA_HOME has no bin/java" "Point JAVA_HOME at the JDK root."
  fi
else
  report "Java (>= 21)" MISSING "java not found" "sudo apt install openjdk-21-jdk-headless"
fi

# --- Node LTS ---
if have node; then
  nv="$(node -v)"; nmajor="${nv#v}"; nmajor="${nmajor%%.*}"
  if [[ "$nmajor" -ge 22 && $((nmajor % 2)) -eq 0 ]]; then report "Node.js (active LTS)" OK "$nv, npm $(npm -v 2>/dev/null)"
  else report "Node.js (active LTS)" MISSING "$nv" "Install the active LTS with nvm or NodeSource."; fi
else
  report "Node.js (active LTS)" MISSING "node not found" "Install the active LTS with nvm or NodeSource."
fi

# --- Python 3.11 ---
if have python3.11; then report "Python 3.11" OK "$(python3.11 --version 2>&1)"
else report "Python 3.11" MISSING "python3.11 not found" "sudo apt install python3.11 python3.11-venv (deadsnakes PPA on older Ubuntu)."; fi

# --- PostgreSQL client + service (never authenticates) ---
ENV_FILE="$(krz_env_path)"
DB_PORT="${DB_PORT_OPT:-$(krz_env_get "$ENV_FILE" DB_PORT 5432)}"
if have psql; then
  report "psql client" OK "$(psql --version)"
  if have pg_lsclusters; then report "PostgreSQL clusters" INFO "$(pg_lsclusters --no-header 2>/dev/null | tr '\n' ';')"; fi
  if systemctl is-active --quiet postgresql 2>/dev/null; then report "PostgreSQL service" OK "active"
  else report "PostgreSQL service" MISSING "not active" "sudo systemctl start postgresql"; fi
  if ready="$(pg_isready -h localhost -p "$DB_PORT" 2>&1)"; then report "pg_isready (port $DB_PORT)" OK "$ready"
  else report "pg_isready (port $DB_PORT)" MISSING "$ready" "Check DB_PORT in deploy/.env and the service."; fi
  report "PostgreSQL server version" INFO "reported by run_bootstrap.sh --check-only (you run it)"
else
  report "psql client" MISSING "psql not found" "sudo apt install postgresql-client"
fi

# --- GPU ---
if have nvidia-smi; then
  report "NVIDIA GPU (host)" OK "$(nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv,noheader | head -n1)"
  if [[ -n "$WITH_GPU_DOCKER" ]]; then
    if out="$(docker run --rm --gpus all "$CUDA_TEST_IMAGE" nvidia-smi --query-gpu=name --format=csv,noheader 2>&1)"; then
      report "GPU in Docker" OK "$out"
    else
      report "GPU in Docker" WARN "$(printf '%s' "$out" | tail -n1)" "Install the NVIDIA Container Toolkit and run: sudo nvidia-ctk runtime configure --runtime=docker && sudo systemctl restart docker"
    fi
  else
    report "GPU in Docker" SKIPPED "run with --with-gpu-docker (pulls $CUDA_TEST_IMAGE)"
  fi
else
  report "NVIDIA GPU (host)" WARN "nvidia-smi not found" "Install the NVIDIA driver (ubuntu-drivers install), or run the worker on CPU (docker-compose.cpu.yml)."
fi

# --- Disk space ---
root="$(krz_project_root)"
free_gb="$(df -BG --output=avail "$root" | tail -n1 | tr -dc '0-9')"
if [[ "$free_gb" -lt "$MIN_FREE_GB" ]]; then report "Free disk" MISSING "${free_gb} GB free" "Free at least ${RECOMMENDED_FREE_GB} GB."
elif [[ "$free_gb" -lt "$RECOMMENDED_FREE_GB" ]]; then report "Free disk" WARN "${free_gb} GB free" "${RECOMMENDED_FREE_GB} GB or more is recommended."
else report "Free disk" OK "${free_gb} GB free"; fi

# --- Time sync (security §13) ---
if have timedatectl; then
  if timedatectl show -p NTPSynchronized --value 2>/dev/null | grep -q yes; then report "NTP time sync" OK "synchronized"
  else report "NTP time sync" WARN "not synchronized" "sudo timedatectl set-ntp true"; fi
fi

if [[ ${#FIXES[@]} -gt 0 ]]; then
  echo; echo "What to do:"
  for f in "${FIXES[@]}"; do echo "  - $f"; done
fi
exit "$MISSING"
