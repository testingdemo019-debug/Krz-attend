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
`BOOTSTRAP_ADMIN_EMAILS`, `MAIL_TEST_RECIPIENT` (the one address that receives every e-mail in this setup, see step 9),
`CAMERA_01_USERNAME`, `CAMERA_01_PASSWORD`, `DB_PORT` (if PostgreSQL is not on 5432) and `WORKER_MAX_FPS=25` (the
camera's frame rate; the worker reads it only when it starts).

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
.\.venv\Scripts\python -m pip install -e ".[gpu]" --extra-index-url https://pypi.nvidia.com
.\.venv\Scripts\python scripts\download_models.py --models-dir ..\data\models
cd ..\frontend
npm ci
cd ..
```
The model download builds two models in a temporary environment (a few GB, deleted afterwards); copying another
machine's `data\models` folder instead is quicker.

The back-view Re-ID model is NVIDIA ReIdentificationNet, downloaded from NVIDIA NGC, and it runs on NVIDIA TensorRT
(about 2 GB, from NVIDIA's package index). Installing them accepts NVIDIA's licences for your organisation: the
[Deep Learning Models License Agreement](https://developer.download.nvidia.com/licenses/tao_toolkit_21-08_models_eula.pdf)
allows use inside this application on machines with an NVIDIA GPU, and not handing the model out on its own. On a
machine without TensorRT the worker still runs, with back-view Re-ID off: an OUT without a face goes to review
(`deploy\scripts\status.ps1` shows which).

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
Stop-ScheduledTask -TaskName "KRZ Attend"
powershell -ExecutionPolicy Bypass -File deploy\scripts\stop-all.ps1
git pull
powershell -ExecutionPolicy Bypass -File deploy\scripts\start-all.ps1
```
`start-all` does the rest: when the worker's or the web app's packages changed it installs them, it downloads any new
model and removes one no longer used, and the backend updates the database as it starts. Watch it in
`data\logs\start-all.log`, `worker-setup.log` and `web-setup.log`; a large download can take a while, and the first
worker start after a new model takes about a minute more. If the internet is down the worker and the web app start with
what they have, and it is tried again at the next start.

The first line is only for a machine with the task of step 7; there, end with `Start-ScheduledTask -TaskName "KRZ Attend"`
instead of the last line, so it keeps watching. Check the result with `deploy\scripts\status.ps1`.

## 9. E-mail
- **Who receives it:** each notification rule's recipients in Settings, Notifications (roles, and single people who
  have a role). The address is their work e-mail from Microsoft. Each e-mail names the rule and links to the page in
  KRZ Attend and to the notification settings; it never contains a face picture.
- **This setup** (`APP_ENV=dev`) sends **every** e-mail only to `MAIL_TEST_RECIPIENT`. Its subject starts with
  `[DEV → address]`, the person it was meant for. Without that address nothing is sent. Links use `APP_BASE_URL`
  (`http://localhost:5173`), so they open on this machine only.
- **Real recipients** need production (step 10): `APP_ENV=prod`, `MAIL_TEST_RECIPIENT` empty (the backend log warns
  when one is left over), `APP_BASE_URL` set to the https address people open, and `MAIL_SENDER` a real mailbox.
- **Check:** Settings, Notifications, **Send test e-mail** sends one to your own work address (in this setup: to
  `MAIL_TEST_RECIPIENT`).

## 10. Production on this Windows PC
PostgreSQL and the worker (on the GPU) run on Windows; Redis, the video relay, the backend and nginx (https) run in Docker
Desktop. Only ports 80 and 443 are open to the network: port 80 only redirects to https.

**Needed first:**
- A fixed IP for this PC (a reservation in the router) and a DNS record pointing your name to it.
- In Microsoft Entra, under App registrations, KRZ Attend, Authentication, add
  `https://<your name>/login/oauth2/code/azure`.
- A certificate for your name, as two files in `deploy\nginx\certs`: `fullchain.pem` and `privkey.pem`. They stay out of
  Git; never share the private key.

**Then:**
1. In `deploy\.env` set `APP_ENV=prod`, `APP_BASE_URL=https://<your name>` and leave `MAIL_TEST_RECIPIENT` empty. From now
   on e-mail goes to the real recipients.
2. Stop the running setup and start production. The first start builds the images, which can take a long time; if a
   download fails, run `start-all` again and it continues from where it stopped.
```powershell
powershell -ExecutionPolicy Bypass -File deploy\scripts\stop-all.ps1
powershell -ExecutionPolicy Bypass -File deploy\scripts\start-all.ps1
powershell -ExecutionPolicy Bypass -File deploy\scripts\status.ps1
```
3. From another PC open `https://<your name>` and sign in. If it does not answer, allow ports 80 and 443 in Windows
   Firewall on this PC.

Step 7's task and step 8's update work the same in production; the images are built again when the code changed. To go
back to the development setup, set `APP_ENV=dev` and `APP_BASE_URL=http://localhost:5173`.

## Notes
- Only one machine should run the worker for a camera at a time, or both record every crossing in their own database.
- In the development setup the web app answers on this machine only (`localhost`). Other PCs use production (step 10).
- In the app: Settings, Cameras to add the camera, draw the zone and the tripwire; Enrolment to enrol the employees.
