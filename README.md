# KRZ Attend

Face-recognition attendance for KRZ Technologies: a camera at the door records who comes in and goes out, and the
web app shows attendance, the review queue and the settings.

Parts: PostgreSQL (on the machine), Redis and the video relay (Docker), the backend (Java), the recognition worker
(Python, NVIDIA GPU) and the web app (React). Secrets (`deploy\.env`) and data (`data\`) are never in Git.

## 1. Install once (Windows, as administrator)
- Git for Windows, Docker Desktop (WSL 2) and PostgreSQL 17
- `winget install EclipseAdoptium.Temurin.21.JDK`
- `winget install OpenJS.NodeJS.LTS`
- `winget install Python.Python.3.11`
- The NVIDIA driver 580 or newer (CUDA 13), e.g. for the RTX 2000 Ada

Reopen the terminal afterwards.

## 2. Get the code and check the machine
```powershell
git clone https://github.com/testingdemo019-debug/Krz-attend.git C:\krz-attend
cd C:\krz-attend
powershell -ExecutionPolicy Bypass -File deploy\scripts\check-prereqs.ps1 --with-gpu-docker
```

## 3. Secrets
Either a **fresh system**: run `powershell -ExecutionPolicy Bypass -File deploy\scripts\init-env.ps1`, then open
`deploy\.env` in a text editor and fill in `ENTRA_CLIENT_SECRET`, `ENTRA_CLIENT_SECRET_EXPIRES`,
`BOOTSTRAP_ADMIN_EMAILS`, `MAIL_TEST_RECIPIENT`, `CAMERA_01_USERNAME`, `CAMERA_01_PASSWORD`, `DB_PORT` (if PostgreSQL
is not on 5432) and `WORKER_MAX_FPS=20`.

Or **the same data as another machine**: copy that machine's `deploy\.env` by hand (USB stick; never Git, e-mail or
chat), restore its database backup (`database\scripts\restore.ps1`) and copy its `data\crops` and `data\uploads`. Its
`ENCRYPTION_KEY_*` values are what unlock the face templates, the crops and the camera password.

## 4. Database
```powershell
powershell -ExecutionPolicy Bypass -File database\bootstrap\run_bootstrap.ps1 --check-only
powershell -ExecutionPolicy Bypass -File database\bootstrap\run_bootstrap.ps1
```
It asks for the `postgres` password at a hidden prompt and writes the application's database passwords into
`deploy\.env`. The tables are created when the backend starts.

## 5. Worker and web app (once, and again after an update changes their dependencies)
```powershell
cd worker
py -3.11 -m venv .venv
.\.venv\Scripts\python -m pip install -e ".[gpu]"
.\.venv\Scripts\python scripts\download_models.py --models-dir ..\data\models
cd ..\frontend
npm ci
cd ..
```
The model download builds two models in a temporary environment (a few GB, deleted afterwards); copying another
machine's `data\models` folder instead is quicker.

## 6. Run everything
```powershell
powershell -ExecutionPolicy Bypass -File deploy\scripts\start-all.ps1
```
It starts Docker Desktop if needed, then Redis and the video relay, the backend, the worker and the web app, hidden in
the background, with their logs in `data\logs`. The first backend start takes a few minutes (it builds). Then open
**http://localhost:5173** on this machine and sign in with Microsoft.

- Status: `powershell -ExecutionPolicy Bypass -File deploy\scripts\status.ps1`
- Stop: `powershell -ExecutionPolicy Bypass -File deploy\scripts\stop-all.ps1`

Recognition does not need the web page: the worker records every crossing while the services run.

## 7. Start automatically after a restart
```powershell
powershell -ExecutionPolicy Bypass -File deploy\scripts\install-startup-task.ps1
```
This registers the scheduled task "KRZ Attend": one minute after you sign in to Windows it runs
`start-all.ps1 --watch`, which starts everything and then checks every minute, starting again any part that stopped.
Docker Desktop needs a signed-in user, so for a restart with nobody at the PC turn on automatic sign-in for this
account (`netplwiz`; your security decision). Keep the PC from sleeping (Settings, System, Power: "Never" when plugged
in). Remove the task with `install-startup-task.ps1 --remove`; stop it for now with
`Stop-ScheduledTask -TaskName "KRZ Attend"` before `stop-all.ps1`.

## 8. Update to the newest code
```powershell
powershell -ExecutionPolicy Bypass -File deploy\scripts\stop-all.ps1
git pull
powershell -ExecutionPolicy Bypass -File deploy\scripts\start-all.ps1
```
After an update that changes `worker\pyproject.toml` or `frontend\package.json`, run the matching install line of step
5 again first.

## Notes
- Only one machine should run the worker for a camera at a time, or both record every crossing in their own database.
- The web app answers on this machine only (`localhost`). Opening it from other PCs needs HTTPS and the machine's
  address added to the Microsoft sign-in app (the production setup).
- In the app: Settings, Cameras to add the camera, draw the zone and the tripwire; Enrolment to enrol the employees.
