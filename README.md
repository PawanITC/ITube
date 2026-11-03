# ITube

A video-sharing platform with a **React** frontend and a **Spring Boot** backend, kept in one repository.

```
ITube/
├── backend/     Spring Boot 4 (Java 21, Maven) - REST API on http://localhost:8080
└── frontend/    React 19 + Vite - web app on http://localhost:3000
```

## Prerequisites

| Tool    | Version                    | Check with       |
|---------|----------------------------|------------------|
| Java    | 21                         | `java -version`  |
| Node.js | 20.19+ or 22.12+ (we use 24) | `node -v`      |
| Git     | any recent                 | `git --version`  |
| Docker Desktop | any recent          | `docker --version` |

You don't need to install Maven. The backend includes the Maven wrapper (`mvnw`), which downloads Maven automatically.

## Getting started

```bash
git clone https://github.com/PawanITC/ITube.git
cd ITube
```

### Quick start (Windows): one command

From the repo root, double-click **`dev.cmd`**, or run:

```powershell
.\dev.cmd
```

The script does the following:
1. Creates `backend/.env` from `backend/.env.example` the first time, with a random playback signing key. The file is git-ignored and stays on your PC.
2. Starts Docker Desktop if it isn't running, then PostgreSQL, Redis, Kafka, Grafana and the Stripe webhook forwarder from `docker-compose.yml`.
3. Saves the Stripe webhook secret into `backend/.env`.
4. Installs frontend packages if they're missing or out of date.
5. Opens the backend in its own window and waits until it's healthy, then opens the frontend. The app opens in your browser.

| Command | What it does |
|---|---|
| `.\dev.cmd` | Start everything |
| `.\dev.cmd stop` | Stop everything. Your local database is kept |
| `.\dev.cmd status` | Show what's running |

When it's running:

| What | Where |
|---|---|
| App | http://localhost:3000 |
| Feature showcase (development only) | http://localhost:3000/demo |
| Swagger (API docs) | http://localhost:8080/swagger-ui.html |
| Grafana (logs, traces, metrics) | http://localhost:3080 |

**Payments are optional.** Without a Stripe key everything else works, and payment endpoints return `503`. To enable them, put a Stripe **test** key (`sk_test_…` or, better, a restricted `rk_test_…`) in `STRIPE_SECRET_KEY` in `backend/.env`, then run `.\dev.cmd stop` and `.\dev.cmd` again. The script refuses to start with a live key.

Good to know:
- **The first run is slow.** It downloads the Docker images and the backend's dependencies, which can take a few minutes. Later starts take under a minute.
- **"Port … is already allocated"** means another copy of the services is running, usually the Terraform containers from `tf_docker`. Stop those with `make down` (or in Docker Desktop) first. Use one setup or the other, not both.
- **Something not working?** Look at the "ITube backend" and "ITube frontend" windows; errors show up there.
- **macOS or Linux:** the script is Windows-only. Follow the manual steps below, using `docker compose --env-file backend/.env up -d` for the services.

### Running things by hand

Start the services first, with either `docker compose --env-file backend/.env up -d` (from the repo root) or `make up` in `tf_docker`.

### 1. Run the backend
```bash
cd backend
./mvnw spring-boot:run        # Windows CMD/PowerShell: mvnw.cmd spring-boot:run
```
The API starts on http://localhost:8080. For video quality processing, start the local infrastructure with `make up` from `tf_docker` first and install FFmpeg on the backend machine. The worker looks for `ffmpeg` on `PATH` by default; set `FFMPEG_PATH` to its executable if needed. Kafka defaults to `localhost:9092` and can be overridden with `KAFKA_BOOTSTRAP_SERVERS`.

For History endpoints, the optional H2 profile, and the backend package layout, see [the History guide](backend/HISTORY.md).

Uploaded videos are kept in PostgreSQL for this first implementation. Kafka schedules background FFmpeg jobs that create 360p and 720p MP4 renditions. The API reports `PROCESSING`, `READY`, or `FAILED`; while processing, the original remains playable. Quality files are also stored in PostgreSQL, so this is suitable for local development/small demos rather than production-scale media storage.

### 2. Run the frontend (in a second terminal)
```bash
cd frontend
npm install                   # first time, and after anyone changes package.json
npm run dev
```
The app opens at http://localhost:3000. Changes you save reload in the browser automatically.

### IntelliJ IDEA
Open the `ITube` folder. If IntelliJ doesn't detect the backend, right-click `backend/pom.xml` and choose **Add as Maven Project**.

## Useful commands

**Frontend** (run inside `frontend/`)

| Command           | What it does                                      |
|-------------------|---------------------------------------------------|
| `npm run dev`     | Start the dev server with hot reload              |
| `npm run build`   | Build for production into `frontend/build/`       |
| `npm run preview` | Serve the production build locally                |
| `npm test`        | Run tests (Vitest, watch mode)                    |

**Backend** (run inside `backend/`; on Windows use `mvnw.cmd`)

| Command                     | What it does                          |
|-----------------------------|---------------------------------------|
| `./mvnw spring-boot:run`    | Start the API                         |
| `./mvnw test`               | Run tests                             |
| `./mvnw package`            | Build the jar into `backend/target/`  |

## How the frontend and backend connect

In development, the frontend's dev server forwards any request starting with `/api/` to the backend on port 8080 (configured in `frontend/vite.config.js`). Frontend code therefore calls relative URLs such as `fetch('/api/videos')`, and no CORS setup is needed.

**Backend convention:** put every REST endpoint under `/api/...` (e.g. `@RequestMapping("/api/videos")`) so the proxy picks it up.

## API documentation (Swagger)

Swagger is served by the **backend**. Start it, then open **http://localhost:8080/swagger-ui.html**.

The page is generated by [springdoc-openapi](https://springdoc.org/) from the Spring controllers each time the backend starts, so it only lists endpoints that actually exist. There's no spec file to maintain. The raw OpenAPI JSON is at `http://localhost:8080/v3/api-docs`, which you can import into Postman or use to generate a client.

**Trying endpoints:** use **Try it out**. To act as a user, first run `POST /api/session/dev` (or `/dev/random`). The browser keeps the session cookie, so the next calls run as that user.

**When you add an endpoint:** put it under `/api/...` and it appears automatically. To make the docs clearer, add a `@Tag` on the controller (every controller has one) and, where useful, `@Operation(summary = "...")` on the method and `@Schema(description = "...")` on DTO fields.

**Production:** set `API_DOCS_ENABLED=false` to switch off both `/swagger-ui.html` and `/v3/api-docs`.


## Payments (Stripe)

Paid channel memberships use Stripe Checkout, Billing and webhooks. **Free Subscribe is a separate feature and never involves payment.**

- **How it works:** [docs/payments/ARCHITECTURE.md](docs/payments/ARCHITECTURE.md) covers the architecture diagram, money flows, database, Connect plan, PCI/GDPR and phases.
- **Run and test locally:** [docs/payments/TESTING.md](docs/payments/TESTING.md) covers keys, the Stripe CLI, test cards, a manual test script, and the Dashboard settings you need.

Quick start:
1. `cp backend/.env.example backend/.env` and add a Stripe **sandbox** key. `.env` is git-ignored: never commit keys.
2. Run `.\dev.cmd`. It starts the Stripe webhook forwarder and saves the `whsec_…` secret for you. (By hand: the `stripe-cli` service in `docker-compose.yml`, or `stripe listen` with the `--events` list in TESTING.md.)
3. Open http://localhost:3000/demo for a guided tour of the membership features.

Terraform:

Install Terraform --- Follow this video `https://www.youtube.com/watch?v=ntf5mvIvE2k`
Install Make --- `choco install make`
Direct into terraform and initialise it ---

```bash
cd tf_docker
terraform init
```

Then run the cmd `make up` to start the servers and database
Use `make down` to take down servers
Use `make down` to delete the database

## Things to know

- **`npm audit` should report 0 vulnerabilities** in `frontend/`. If it doesn't, flag it.
- **Files containing JSX must use the `.jsx` extension.** Vite requires it.
- **Don't commit** `node_modules/`, `frontend/build/` or `backend/target/`. They're already in `.gitignore`.
- **Port already in use?** Stop the other process, or run `npm run dev -- --port 3001`.
