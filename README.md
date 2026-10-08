# SupplyPilot

SupplyPilot is a portfolio-grade procurement and supply-chain decision platform for small businesses. Deterministic backend logic owns inventory, purchasing calculations, lifecycle transitions, authorization, and data integrity. AI is limited to query, explanation, document retrieval, and explicitly confirmed draft creation.

## Technology Baseline

| Area | Baseline |
|---|---|
| Java | 21 LTS |
| Spring Boot | 4.1.1 |
| Build | Maven Wrapper |
| Database | MySQL 8.4 LTS |
| Migration | Flyway |
| Backend architecture | Modular Monolith, Package by Feature |
| Frontend | React 19, TypeScript, Vite 8, Ant Design |
| Node.js | 24 LTS |
| AI integration | Spring AI 2.0, outside the core transaction path |

## Repository Layout

```text
supplypilot/
├── backend/          Spring Boot API
├── frontend/         React + Vite frontend shell
├── docs/             V1 specifications and executable backlog
│   ├── erd/          Entity relationship diagrams
│   └── uml/          Domain and sequence diagrams
├── compose.yaml      Local MySQL
└── README.md         Project entry point
```

## Local Prerequisites

- Java 21
- Node.js 24 LTS
- Docker Desktop with the Docker engine running
- Git

Maven does not need to be installed globally; use the committed wrapper.

## Local Setup

### Backend

1. Create a local environment file from `.env.example` and set non-empty development passwords.
   If port `3306` is unavailable, set `DB_PORT` and update `DB_URL` to the same host port.
2. Start MySQL from the repository root:

   ```powershell
   docker compose up -d mysql
   ```

3. Start the backend:

   ```powershell
   cd backend
   .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
   ```

4. Check `http://localhost:8080/actuator/health`.

### Frontend

1. Install frontend dependencies:

   ```powershell
   cd frontend
   npm install
   ```

2. Configure environment:

   ```powershell
   copy .env.example .env
   ```

3. Start development server:

   ```powershell
   npm run dev
   ```

4. Access `http://localhost:5173`.

## Tests

Integration tests use MySQL 8.4 through Testcontainers and require a running Docker engine.

```powershell
cd backend
.\mvnw.cmd test
```

## Configuration

The backend supports `local`, `test`, `demo`, and `prod` profiles. Secrets must be supplied through environment variables or a local untracked `.env` file. Production starts with `ddl-auto=validate`, Flyway enabled, and fail-fast validation for required settings. `FRONTEND_ORIGIN` is reserved as the configuration boundary for later CORS and security work; CORS is not implemented in SP-001.

## V1 Scope

The documentation index is maintained in `docs/README.md`, and the executable implementation backlog is maintained in `docs/SUPPLYPILOT_V1_ISSUES_ZH-TW.md`. V1 intentionally excludes microservices, Kafka, Redis, Elasticsearch, partial receiving, multi-company, multi-warehouse, and AI-controlled high-risk actions.

