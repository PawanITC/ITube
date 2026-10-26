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

You don't need to install Maven. The backend includes the Maven wrapper (`mvnw`), which downloads Maven automatically.

## Getting started

```bash
git clone https://github.com/Ibraheem177/ITube.git
cd ITube
```

### 1. Run the backend
```bash
cd backend
./mvnw spring-boot:run        # Windows CMD/PowerShell: mvnw.cmd spring-boot:run
```
The API starts on http://localhost:8080. For video quality processing, start the local infrastructure with `make up` from `tf_docker` first and install FFmpeg on the backend machine. The worker looks for `ffmpeg` on `PATH` by default; set `FFMPEG_PATH` to its executable if needed. Kafka defaults to `localhost:9092` and can be overridden with `KAFKA_BOOTSTRAP_SERVERS`.

Uploaded videos are kept in PostgreSQL for this first implementation. Kafka schedules background FFmpeg jobs that create 360p and 720p MP4 renditions. The API reports `PROCESSING`, `READY`, or `FAILED`; while processing, the original remains playable. Quality files are also stored in PostgreSQL, so this is suitable for local development/small demos rather than production-scale media storage.

### 2. Run the frontend (in a second terminal)
```bash
cd frontend
npm install                   # first time, and after anyone changes package.json
npm run dev
```
The app opens at http://localhost:3000. Changes you save reload in the browser automatically.

The frontend currently uses mock data (`frontend/src/data/videos.js`), so it works without the backend running.

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

Open **http://localhost:3000/api-docs** while the frontend is running. It shows every API endpoint, its parameters and its response format. **Try it out** sends a real request, which works once the backend is running and has implemented that endpoint.

The page is generated from **`frontend/public/openapi.yaml`**, the agreed API contract between frontend and backend. It currently holds one placeholder endpoint, `GET /api/videos`, to use as a template.

**When you add or change an endpoint, update `openapi.yaml` in the same pull request:**
1. Add the path under `paths:`, and any new data shapes under `components.schemas:`. Copy the existing `/videos` entry as a starting point.
2. Save. `/api-docs` refreshes with the change.
3. To check for mistakes, paste the file into https://editor.swagger.io/.

Example:
```yaml
  /videos/{id}:
    get:
      tags: [Videos]
      summary: Get one video
      parameters:
        - name: id
          in: path
          required: true
          schema: { type: integer }
      responses:
        '200':
          description: The video
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Video' }
        '404':
          description: Video not found
```


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

*Planned:* once the backend has real endpoints, we can add [springdoc-openapi](https://springdoc.org/) so Spring generates the spec from the Java code. To point Swagger at it, create `frontend/.env.local` containing `VITE_OPENAPI_URL=/v3/api-docs` and add `/v3` to the proxy in `vite.config.js`.

## Things to know

- **Don't remove `overrides` from `frontend/package.json`.** They patch a security vulnerability in one of Swagger UI's dependencies (`sprintf-js`) and silence React 19 peer warnings. `npm audit` should report 0 vulnerabilities. If it doesn't, flag it.
- **Files containing JSX must use the `.jsx` extension.** Vite requires it.
- **Don't commit** `node_modules/`, `frontend/build/` or `backend/target/`. They're already in `.gitignore`.
- **Port already in use?** Stop the other process, or run `npm run dev -- --port 3001`.
