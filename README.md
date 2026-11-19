# ITube

A video-sharing platform with a **React** frontend and **Spring Boot microservices**, kept in one repository and
built to scale the way large video platforms do: sharded databases, caching, a CDN, and services that survive each
other's failures.

```
ITube/
├── backend/            Spring Boot 4 (Java 21), one Maven build with several services:
│   ├── common/             shared library: session user, resilient service clients, caches, Kafka retries
│   ├── storage/            shared library: S3-compatible object storage
│   ├── identity-service/   accounts, sign-in                       http://localhost:8081
│   ├── catalog-service/    channels, videos, uploads, playback     http://localhost:8082  (sharded Postgres)
│   ├── engagement-service/ history, likes, comments                http://localhost:8083  (ScyllaDB)
│   ├── payments-service/   Stripe, memberships                     http://localhost:8084
│   ├── transcode-worker/   FFmpeg renditions (Kafka)
│   └── e2e-tests/          end-to-end Cucumber journeys
├── gateway/            nginx API gateway: one entry point for /api/**   http://localhost:8080
├── frontend/           React 19 + Vite web app                          http://localhost:3000
├── loadtest/           k6 load, spike, soak and chaos tests
├── deploy/             Kubernetes (EKS), Terraform (AWS), Cloudflare (DNS + CDN)
└── docs/               architecture, sharding, CDN, resilience and scale, testing
```

**Start here:** [docs/MICROSERVICES.md](docs/MICROSERVICES.md) explains what each service does and why.
Then [docs/SHARDING.md](docs/SHARDING.md), [docs/CDN-AND-DNS.md](docs/CDN-AND-DNS.md),
[docs/RESILIENCE-AND-SCALE.md](docs/RESILIENCE-AND-SCALE.md) and [docs/TESTING.md](docs/TESTING.md).

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
2. Starts Docker Desktop if it isn't running, then PostgreSQL (two catalog shards), ScyllaDB, SeaweedFS, Redis, Kafka, Grafana, the API gateway, the transcode worker and the Stripe webhook forwarder from `docker-compose.yml`.
3. Saves the Stripe webhook secret into `backend/.env`.
4. Installs frontend packages if they're missing or out of date.
5. Builds the services and opens each one (identity, catalog, engagement, payments) in its own window, waits until they're healthy, then opens the frontend. The app opens in your browser.

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
| API gateway (what the frontend calls) | http://localhost:8080 |
| Swagger (API docs), one per service | http://localhost:8081/swagger-ui.html (identity), :8082 (catalog), :8083 (engagement), :8084 (payments) |
| Grafana (logs, traces, metrics) | http://localhost:3080 |

**Payments are optional.** Without a Stripe key everything else works, and payment endpoints return `503`. To enable them, put a Stripe **test** key (`sk_test_…` or, better, a restricted `rk_test_…`) in `STRIPE_SECRET_KEY` in `backend/.env`, then run `.\dev.cmd stop` and `.\dev.cmd` again. The script refuses to start with a live key.

Good to know:
- **The first run is slow.** It downloads the Docker images and the backend's dependencies, which can take a few minutes. Later starts take under a minute.
- **"Port … is already allocated"** means another copy of the services is running, usually the Terraform containers from `tf_docker`. Stop those with `make down` (or in Docker Desktop) first. Use one setup or the other, not both.
- **Something not working?** Look at the "ITube <service>" and "ITube frontend" windows; errors show up there.
- **Memory:** four services plus the data stores need about 6 GB of RAM in total.
- **macOS or Linux:** the script is Windows-only. Follow the manual steps below, using `docker compose --env-file backend/.env up -d` for the services.

### Running things by hand

Start the services first, with either `docker compose --env-file backend/.env up -d` (from the repo root) or `make up` in `tf_docker`.

### 1. Run the services
```bash
docker compose --env-file backend/.env up -d     # data stores, gateway (:8080), transcode worker
cd backend
./mvnw -DskipTests install                       # build everything once
# then one terminal per service, all started from backend/ (they read backend/.env):
java -jar identity-service/target/identity-service-0.0.1-SNAPSHOT.jar
java -jar catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar
java -jar engagement-service/target/engagement-service-0.0.1-SNAPSHOT.jar
java -jar payments-service/target/payments-service-0.0.1-SNAPSHOT.jar
```
Or run a service from your IDE: its main class is `ITubeBackend.<Name>ServiceApplication` in its module. Everything
goes through the gateway on http://localhost:8080, exactly like the old single backend. The transcode worker runs in
Docker so you don't need FFmpeg; to run it on your machine instead, install FFmpeg and start
`transcode-worker/target/transcode-worker-0.0.1-SNAPSHOT.jar` (set `FFMPEG_PATH` if `ffmpeg` isn't on `PATH`).

For History endpoints, the optional H2 profile, and the backend package layout, see [the History guide](backend/HISTORY.md).

For the Liked videos API, UI, and a beginner walkthrough of its implementation, see [the Liked videos guide](backend/LIKED-VIDEOS.md).

Uploaded videos, renditions and thumbnails are stored in S3-compatible object storage (SeaweedFS locally, S3 API at http://localhost:8333); PostgreSQL keeps only their keys. Kafka sends new videos to the transcode worker, which creates 360p and 720p MP4 renditions with FFmpeg. The API reports `PROCESSING`, `READY`, or `FAILED`; while processing, the original remains playable. Playback redirects the browser to a short-lived pre-signed URL, so video bytes don't pass through the backend.

Data is split by service: PostgreSQL schemas `identity` and `payments`, the `catalog` sharded over two PostgreSQL databases (ports 5434 and 5435 locally), and ScyllaDB for watch history, likes and comments. See [docs/DATA-ARCHITECTURE.md](docs/DATA-ARCHITECTURE.md) and [docs/SHARDING.md](docs/SHARDING.md). Existing databases are migrated automatically on the next start.

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
| `./mvnw test`                                  | Unit tests and BDD (Cucumber) scenarios of every service |
| `./mvnw -pl catalog-service test`              | One service's tests |
| `./mvnw -DskipTests install`                   | Build every service's jar (`backend/<service>/target/`) |
| `./mvnw -pl e2e-tests test -Pe2e`              | End-to-end journeys against the running stack ([docs/TESTING.md](docs/TESTING.md)) |
| `k6 run loadtest/smoke.js`                     | Load test smoke run, from the repo root ([loadtest/README.md](loadtest/README.md)) |

## How the frontend and backend connect

In development, the frontend's dev server forwards any request starting with `/api/` to the API gateway on port 8080 (configured in `frontend/vite.config.js`), which sends it to the right service by its path ([gateway/templates/default.conf.template](gateway/templates/default.conf.template)). Frontend code therefore calls relative URLs such as `fetch('/api/videos')`, and no CORS setup is needed.

**Backend convention:** put every public REST endpoint under `/api/...` (e.g. `@RequestMapping("/api/videos")`). A new path prefix also needs a route in the gateway. Service-to-service endpoints go under `/internal/...`, which the gateway never exposes.

## API documentation (Swagger)

Each service serves Swagger for its own endpoints: **http://localhost:8081/swagger-ui.html** (identity), **:8082** (catalog), **:8083** (engagement), **:8084** (payments).

The page is generated by [springdoc-openapi](https://springdoc.org/) from the Spring controllers each time the backend starts, so it only lists endpoints that actually exist. There's no spec file to maintain. The raw OpenAPI JSON is at `http://localhost:<port>/v3/api-docs`, which you can import into Postman or use to generate a client.

**Trying endpoints:** use **Try it out**. To act as a user, first run `POST /api/auth/register` or `POST /api/auth/login` in identity's Swagger. The session cookie works for every service on localhost, so the other services' calls then run as that user.

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

Then run the cmd `make up` to start the servers and database. Note: `tf_docker` starts the data stores of the
original single-backend setup; the second catalog shard and the API gateway are only in `docker-compose.yml`.
Use `make down` to take down servers
Use `make down` to delete the database

## Things to know

- **`npm audit` should report 0 vulnerabilities** in `frontend/`. If it doesn't, flag it.
- **Files containing JSX must use the `.jsx` extension.** Vite requires it.
- **Don't commit** `node_modules/`, `frontend/build/` or `backend/target/`. They're already in `.gitignore`.
- **Port already in use?** Stop the other process, or run `npm run dev -- --port 3001`.
