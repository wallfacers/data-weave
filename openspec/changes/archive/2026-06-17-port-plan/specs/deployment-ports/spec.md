## ADDED Requirements

### Requirement: Application ports follow functional hundred-segment allocation
The system SHALL assign application service ports by functional role using hundred-segment ranges: master/api services use 80xx (base 8000), worker services use 81xx (base 8100), secondary master instances use 82xx (base 8200), workhorse-agent uses 83xx (base 8300), and the frontend dev server uses 4000. No application service SHALL use a port from another segment.

#### Scenario: Default master-1 port falls in 80xx segment
- **WHEN** `dataweave-api` (master-1) starts with no port override
- **THEN** it listens on port 8000 (container-internal and host)

#### Scenario: Default worker port falls in 81xx segment
- **WHEN** `dataweave-worker` starts with no port override
- **THEN** it listens on port 8100 (container-internal)

#### Scenario: Secondary master host port falls in 82xx segment
- **WHEN** `dataweave-master-2` is deployed alongside `dataweave-master`
- **THEN** its host port is 8200, while its container-internal port remains 8000

#### Scenario: Workhorse-agent port falls in 83xx segment
- **WHEN** workhorse-agent starts in host network mode
- **THEN** it binds to 127.0.0.1:8300

#### Scenario: Frontend dev server uses port 4000
- **WHEN** developer runs `pnpm dev` in `frontend/`
- **THEN** Next.js serves on `http://localhost:4000`

### Requirement: Multi-instance deployment uses container-uniform + host-offset strategy
When running multiple master or worker instances on the same host, all instances of the same role SHALL use the same container-internal port. Host port offsetting SHALL distinguish instances. The first instance's host port equals its container-internal port; subsequent instances increment the host port by 1 per instance.

#### Scenario: Two workers on the same host
- **WHEN** `worker-1` and `worker-2` are both deployed on the same host
- **THEN** `worker-1` host port is 8100 (container 8100), `worker-2` host port is 8101 (container 8100)

#### Scenario: Two masters on the same host
- **WHEN** `dataweave-master` and `dataweave-master-2` are both deployed on the same host
- **THEN** `dataweave-master` host port is 8000 (container 8000), `dataweave-master-2` host port is 8200 (container 8000)

### Requirement: Third-party infrastructure ports follow industry standards
Third-party infrastructure services (PostgreSQL, Redis, MinIO) SHALL retain their industry-standard ports: PostgreSQL 5432, Redis 6379, MinIO S3 API 9000, MinIO Console 9001. The DataWeave port allocation scheme SHALL NOT overlap with these.

#### Scenario: PostgreSQL keeps standard port
- **WHEN** `docker compose up` starts the postgres service
- **THEN** it is accessible at host port 5432

#### Scenario: Redis keeps standard port
- **WHEN** `docker compose up` starts the redis service
- **THEN** it is accessible at host port 6379

### Requirement: Port configuration has a single source of truth
Each service's port SHALL be declared in its `application.yml` as `server.port`. Hardcoded `@Value("${...:default}")` fallbacks in Java code SHALL match the `application.yml` value. Docker-compose and external configs referencing the port SHALL derive from or agree with this source.

#### Scenario: Worker application.yml and @Value default agree
- **WHEN** `dataweave-worker` `application.yml` sets `server.port: 8100`
- **THEN** `HeartbeatReporter.@Value("${server.port:8100}")` fallback is also 8100

#### Scenario: Docker-compose master URL matches container port
- **WHEN** worker containers connect to master via `DATAWEAVE_MASTER_URL`
- **THEN** the URL is `http://dataweave-master:8000` (container-internal port, not host-offset port)

### Requirement: CORS allowlist matches frontend dev port
The backend `CorsConfig` SHALL allow exactly the origin `http://localhost:4000` for development. The frontend dev server SHALL bind to port 4000 so that AG-UI SSE and POST `/agui` requests pass CORS.

#### Scenario: Preflight from localhost:4000 is allowed
- **WHEN** browser sends `OPTIONS /agui` with `Origin: http://localhost:4000`
- **THEN** backend responds with `Access-Control-Allow-Origin: http://localhost:4000`

#### Scenario: Preflight from stale localhost:3000 is rejected
- **WHEN** browser sends `OPTIONS /agui` with `Origin: http://localhost:3000`
- **THEN** backend does NOT set `Access-Control-Allow-Origin` header (CORS blocks)

### Requirement: CLI and deployment configs reference new ports
The `dw` CLI default `DW_API` SHALL be `http://localhost:8000`. The `deploy/workhorse/mcp.json` MCP endpoint SHALL be `http://127.0.0.1:8000/mcp`. Help text and developer-facing documentation SHALL reflect the new ports.

#### Scenario: CLI uses 8000 by default
- **WHEN** user runs `dw task list` without setting `DW_API`
- **THEN** CLI sends request to `http://localhost:8000`

#### Scenario: workhorse-agent reaches MCP at 8000
- **WHEN** workhorse-agent loads `deploy/workhorse/mcp.json`
- **THEN** the MCP endpoint URL is `http://127.0.0.1:8000/mcp`

### Requirement: Documentation reflects new port plan
`CLAUDE.md`, `README.md`, `docs/architecture.md`, and active OpenSpec change docs SHALL use the new port numbers in examples. Archived change docs MAY be left unchanged.

#### Scenario: CLAUDE.md shows new ports
- **WHEN** developer reads `CLAUDE.md` Run & Build section
- **THEN** backend URL example is `http://localhost:8000` and frontend is `http://localhost:4000`
