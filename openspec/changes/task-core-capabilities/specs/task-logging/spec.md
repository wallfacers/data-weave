## ADDED Requirements

### Requirement: Log field type upgrade
The `task_instance.log` column SHALL be changed from `VARCHAR(4000)` to `TEXT` to support unlimited-length log storage. The migration SHALL be backward-compatible (existing log data is preserved).

#### Scenario: Store long log output
- **WHEN** a task instance produces 50KB of log output
- **THEN** the full 50KB is stored in `task_instance.log` without truncation.

#### Scenario: Existing logs preserved after migration
- **WHEN** the schema migration runs on a database with existing task instances
- **THEN** existing log values (up to 4000 chars) are preserved unchanged.

### Requirement: Chunked log retrieval API
The system SHALL provide `GET /api/ops/instances/{id}/log` with query parameters `offset` (default 0) and `limit` (default 65536, max 1MB). The response SHALL include `content` (the log chunk), `totalSize` (total log length), `offset`, `hasMore` (boolean).

#### Scenario: Fetch full log in one request
- **WHEN** a user sends `GET /api/ops/instances/101/log` and the log is 30KB
- **THEN** the system returns `{ "content": "<30KB of text>", "totalSize": 30720, "offset": 0, "hasMore": false }`.

#### Scenario: Fetch log in chunks
- **WHEN** a user sends `GET /api/ops/instances/101/log?offset=0&limit=1024` and the log is 5KB
- **THEN** the system returns the first 1024 bytes with `hasMore=true`. The user can then request `offset=1024` to get the next chunk.

#### Scenario: Fetch log for non-existent instance
- **WHEN** a user sends `GET /api/ops/instances/9999/log`
- **THEN** the system returns HTTP 404.

#### Scenario: Fetch log for instance with no log
- **WHEN** a user sends `GET /api/ops/instances/101/log` and `log` is NULL
- **THEN** the system returns `{ "content": "", "totalSize": 0, "offset": 0, "hasMore": false }`.

### Requirement: Frontend log viewer component
The frontend SHALL provide a `LogViewer` component that displays task instance logs in a monospace font with: (1) auto-scroll to bottom on new content; (2) a scrollbar for navigating long logs; (3) chunked loading (loads first 64KB, then loads more on scroll-to-bottom); (4) instance metadata header (instance ID, task name, state, started_at, finished_at); (5) a "Download" button to save the full log as a text file.

#### Scenario: View log for a completed task
- **WHEN** a user clicks "View Log" on a SUCCESS task instance
- **THEN** the log viewer opens, displays the full log content, and shows the instance metadata header.

#### Scenario: Log viewer chunked loading
- **WHEN** a task instance has 200KB of log and the user scrolls to the bottom
- **THEN** the log viewer loads the next chunk automatically and appends it to the displayed content.

#### Scenario: Download log file
- **WHEN** a user clicks "Download" in the log viewer
- **THEN** the browser downloads a text file named `instance-{id}-log.txt` with the full log content.

### Requirement: Log writing during execution
During task execution, the system SHALL capture stdout/stderr output and write it to `task_instance.log`. If the task executor produces output incrementally, the log SHALL be appended to progressively (not written all at once at the end).

#### Scenario: SQL task log capture
- **WHEN** a SQL task executes and produces query results and timing information
- **THEN** the log field contains the full execution output including SQL text, result summary, and elapsed time.

#### Scenario: Failed task error in log
- **WHEN** a task fails during execution
- **THEN** the log contains the error message and stack trace (if available), and `error_message` field contains the key error summary.
