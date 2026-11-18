# Where the attendance system lives

The Android app is only half the system. Employee records and attendance have to be
stored centrally so every phone, and every manager, sees the same thing. That central
part — the API and its PostgreSQL database — has to run somewhere that is online when
staff arrive at work.

**It cannot live on GitHub.** GitHub stores the source code and builds the APK, and it
does both well, but it is version control, not a database: the app cannot read or write
records there, history is kept forever (so an erasure request can never be honoured),
and access is all-or-nothing. Employee attendance — which shows when each person is at
the office — is exactly the kind of data that must not sit in a repository.

## The three options

| | Cost | Effort | Where the data sits |
|---|---|---|---|
| **A. Render free tier** | R0 | ~10 minutes, clicks only | Germany (Render's closest region) |
| **B. South African server** | ~R150–R400/month | ~1 hour, some admin | South Africa |
| **C. Machine at the Meridian office** | Hardware you own | ~1 hour, plus IT involvement | The Umhlanga office |

**Option A is for the pilot.** It proves the whole system works end to end — phones,
detection, confirmation, admin screens — without spending anything or waiting on
procurement. Its limits matter: the free web service sleeps after about 15 minutes of
inactivity and takes a few seconds to wake (the app queues events and retries, so no
attendance is lost, but the first request of the morning is slow), the free database is
time-limited, and background jobs such as expiring unconfirmed detections only run while
the service is awake.

**Options B and C are for real use.** POPIA does not flatly require personal information
to stay on South African servers, but section 72 regulates transfers of personal
information to a third party in a foreign country, and those transfers must meet one of a
specific set of conditions. Hosting in South Africa keeps the data in local jurisdiction
and avoids that question entirely, which is the simpler position for a system that tracks
employees' presence. Meridian's information officer should confirm the approach before
real employee data is loaded. This is a practical note, not legal advice.

Moving between options is straightforward — it is the same database and the same
container either way — so starting with A costs nothing later.

---

## Option A — Render free tier (pilot)

The repository contains `render.yaml`, which describes the whole deployment, so Render
sets up the database, the API and the secrets itself.

1. **Sign up** at render.com and choose "Sign in with GitHub". Authorise Render to read
   the `meridian-attendance` repository. No credit card is required for free services.
2. **New → Blueprint**, pick `meridian-attendance`, and click **Apply**. Render reads
   `render.yaml`, creates `meridian-db` and `meridian-api`, and generates `SECRET_KEY`
   and `FIRST_ADMIN_SETUP_CODE` for you.
3. **Wait for the first deploy** (about 5–10 minutes; it builds the Docker image and runs
   the database migrations automatically). The service is healthy when its status is
   Live and `https://<your-service>.onrender.com/readyz` returns `ready`.
4. **Copy two values** from the Render dashboard:
   - the service address, e.g. `https://meridian-api-xxxx.onrender.com`
   - `FIRST_ADMIN_SETUP_CODE` (meridian-api → Environment). You need it once.
5. **Set the full address.** In Environment, change `PUBLIC_BASE_URL` to the full
   `https://…onrender.com` address and save. (The blueprint seeds it with the bare host,
   which works, but password-reset links need the complete URL.) Render redeploys.
6. **On the phone**: install the APK, open it, tap **Server** on the login screen, enter
   the service address, and tap **Test connection**. Then use **Set up Meridian
   Attendance**, enter the setup code from step 4, and create the first administrator
   account. That account is a superadmin and can create everyone else.
7. **Set the workplace**: in the app, Manage → Workplaces, stand inside the Umhlanga
   office and tap **Use my current location**, set the radius, and tick
   *coordinates confirmed*. Until this is done, no geofence is registered and no
   attendance is detected. Do not skip it, and do not accept the placeholder.

The admin dashboard is also available in a browser at `https://<your-service>/admin/`
with the same login.

**Before the pilot ends**, back the data up (`deploy/scripts/backup.sh`) and move to
option B or C. The free database is deleted when it expires.

---

## Option B — a South African server (recommended for real use)

Any Linux host with Docker works: a VPS from a local provider such as Afrihost, Xneelo
or RSAWEB, or an Azure/AWS instance in the Johannesburg or Cape Town region. 2 GB of RAM
is comfortable for Meridian's size. `deploy/README.md` is the step-by-step guide; in
outline:

1. Point a domain or subdomain (e.g. `attendance.meridiangroup.co.za`) at the server.
2. Copy `deploy/env/production.env.example` to `production.env` and fill in the values it
   lists — each one is annotated, and `docs/CONFIGURATION-REQUIRED.md` is the master
   checklist.
3. `docker compose -f deploy/docker-compose.yml --env-file production.env up -d`.
   Caddy obtains the HTTPS certificate automatically; migrations run on start.
4. Create the first administrator with `deploy/scripts/create-first-admin.sh`, or set
   `FIRST_ADMIN_SETUP_CODE` and do it from the app as in option A.
5. Schedule `deploy/scripts/backup.sh` daily (cron) and keep copies off the server.

This also removes the free tier's limits: the service never sleeps, the scheduled jobs
run continuously, and the database is yours.

---

## Option C — a machine at the Meridian office

Same as option B, but the server is a PC or small server in the office. The data never
leaves the building, which is the strongest privacy position.

Two practical points for IT:

- **The machine must stay on.** A desktop that sleeps overnight will miss the first
  arrivals of the day. Disable sleep; a UPS is sensible.
- **Phones must be able to reach it.** On the office Wi-Fi they reach it by its local
  address, which is enough if staff only clock in and out at the office. For HTTPS and
  access from mobile data, IT must publish it through the company firewall with a
  hostname and certificate. Employees confirming attendance from the car park are on
  mobile data, so this is worth sorting out.

---

## Changing the address later

The app stores the server address as a setting, so moving hosts does not require a new
APK: change it on the login screen (Server → Test connection), or rebuild with
`MERIDIAN_API_BASE_URL` set to bake in the new default for mass distribution. Moving the
data itself is a `pg_dump` from the old host and a `pg_restore` into the new one;
`deploy/scripts/backup.sh` and `restore.sh` do both.
