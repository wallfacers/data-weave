# scheduling-parameters Specification

## Purpose
TBD - created by archiving change scheduling-parameters. Update Purpose after archive.
## Requirements
### Requirement: Placeholder substitution before execution

The system SHALL resolve scheduling-parameter placeholders in a task's `content` on the master side, **before** the `content` is dispatched to any execution gateway (in-process or distributed) or worker. After resolution, the placeholder-free `content` SHALL flow unchanged through `DispatchCommand` → `ExecutionContext` → executor.

#### Scenario: Shell task with a business-date placeholder

- **WHEN** a `TaskDef` has `content="echo dt=${yyyymmdd}"`, the resolved instance `biz_date` is `2025-03-14`, and the task instance is dispatched
- **THEN** the executor receives `content="echo dt=20250314"` and no `${...}` placeholder reaches the shell.

#### Scenario: Resolution is invisible to worker

- **WHEN** a task runs in distributed mode
- **THEN** the worker receives already-resolved `content` and performs no placeholder parsing of its own.

### Requirement: Business-date formatting

The system SHALL support `${<format>}` placeholders where `<format>` is a combination of `yyyy`, `mm`, `dd` (day-precision only; `hh`/`mi`/`ss` SHALL be rejected). The value SHALL be derived from the instance `biz_date` (business date, T-1).

#### Scenario: Common business-date formats

- **WHEN** `biz_date=2025-03-14`
- **THEN** `${yyyymmdd}`→`20250314`, `${yyyy-mm-dd}`→`2025-03-14`, `${yyyymm}`→`202503`, `${yyyy}`→`2025`.

#### Scenario: Time-precision token rejected

- **WHEN** `content` contains `${yyyy-mm-dd hh24:mi:ss}` (day-precision syntax with time tokens)
- **THEN** resolution fails and the instance is marked `FAILED` with an error naming the invalid token.

### Requirement: Business-date integer offset

The system SHALL support integer offsets `${<format>±N}` on `${...}` placeholders, where `N` is expressed in the smallest unit of `<format>` (days for `yyyymmdd`, months for `yyyymm`, years for `yyyy`). Weeks SHALL be expressible as `${yyyymmdd±7*N}`.

#### Scenario: Day, month, and year offsets

- **WHEN** `biz_date=2025-03-14`
- **THEN** `${yyyymmdd-1}`→`20250313` (previous day), `${yyyymm-1}`→`202502` (previous month), `${yyyy-1}`→`2024` (previous year), `${yyyymmdd-7*1}`→`20250307` (previous week).

#### Scenario: Month-end rollover

- **WHEN** `biz_date=2025-03-31` and `content` contains `${yyyymm-1}`
- **THEN** the result is `202502` (month offset does not clamp to a nonexistent Feb 31; month-precision output ignores the day).

### Requirement: System built-in scheduling parameters

The system SHALL recognize the following built-in parameter tokens: `$bizdate` (≡`${yyyymmdd}`), `$bizmonth`, `$gmtdate`, `$jobid` (workflow id), `$nodeid` (workflow node id), `$taskid` (task instance id). `$bizmonth` SHALL follow DataWorks semantics: when the business-date month equals the current calendar month, it SHALL yield the previous month; otherwise the business-date month, formatted `yyyymm`.

#### Scenario: Built-in parameters substituted

- **WHEN** `biz_date=2025-03-14`, current month is `2025-03`, workflow id is `7`
- **THEN** `$bizdate`→`20250314`, `$bizmonth`→`202502` (same month → previous), `$gmtdate`→ today's date `yyyymmdd`, `$jobid`→`7`.

#### Scenario: bizmonth cross-month rule

- **WHEN** `biz_date=2025-02-20`, current month is `2025-03`
- **THEN** `$bizmonth`→`202502` (business-date month, different from current month).

### Requirement: Recursive custom parameter expansion

The system SHALL resolve custom parameters: a `${<name>}` placeholder in `content` SHALL be expanded to the value declared for `<name>` in `TaskDef.paramsJson` (a `{"name":"expr"}` Map). **If that value still contains `${...}` placeholders, they SHALL be expanded recursively** to arbitrary depth, with a visited-stack cycle guard. Literal nesting `${${...}}` (using an expansion result as a parameter name) SHALL NOT be supported.

#### Scenario: Two-level expansion

- **WHEN** `content="WHERE dt='${dt}'"`, `paramsJson={"dt":"${yyyymmdd-1}"}`, and `biz_date=2025-03-14`
- **THEN** the resolved `content` is `WHERE dt='20250313'`.

#### Scenario: Recursive expansion through a value that contains placeholders

- **WHEN** `paramsJson={"biz_dt":"${yyyymmdd-1}","biz_pt":"dt=${biz_dt}"}`, `content="INSERT ... WHERE ${biz_pt}"`, and `biz_date=2025-03-14`
- **THEN** the resolved `content` is `INSERT ... WHERE dt=20250313` (`${biz_pt}` → `dt=${biz_dt}` → `dt=${yyyymmdd-1}` → `dt=20250313`).

#### Scenario: Unknown custom parameter fails

- **WHEN** `content="SELECT ${undefined_param}"` and `undefined_param` is neither built-in nor declared in `paramsJson`
- **THEN** resolution fails and the instance is marked `FAILED` with an error naming `undefined_param`.

#### Scenario: Circular reference detected

- **WHEN** `paramsJson={"a":"${b}","b":"${a}"}` and `content` references `${a}`
- **THEN** resolution fails with a circular-reference error rather than looping.

#### Scenario: Literal nested placeholder not supported

- **WHEN** `content="SELECT ${${biz_dt}}"` (a placeholder whose name is itself a placeholder)
- **THEN** resolution fails with an error; the inner `${...}` is NOT re-interpreted as a parameter-name lookup.

### Requirement: Frozen substitution values

Resolved values SHALL be a pure function of the instance's frozen substitution inputs — `biz_date` (set at instance creation) and the `content`/`paramsJson` of the `TaskDef` version referenced by `taskVersionNo` — and SHALL NOT depend on the wall-clock time at which the instance actually starts running (including queueing or retry delays).

#### Scenario: Delayed start does not change substitution

- **WHEN** an instance is created at 00:00 with `biz_date=2025-03-14`, but does not actually run until 06:00 the same day
- **THEN** `${yyyymmdd}` still resolves to `20250314`, not to the date at 06:00.

### Requirement: Unresolved placeholder handling

The system SHALL NOT silently pass an unresolved or malformed placeholder into the executor. Any placeholder that fails to parse or resolve SHALL cause the task instance to enter `FAILED` state with `errorMessage` identifying the offending placeholder.

#### Scenario: Malformed placeholder fails

- **WHEN** `content="SELECT ${yyyymmdd}"` and resolver encounters a syntactically invalid token `${yyyymmdd-}` (trailing operator)
- **THEN** the instance is marked `FAILED` and `errorMessage` names the malformed token; the executor is never invoked with the broken content.

### Requirement: No-op when content has no placeholders

The system SHALL return `content` unchanged when it contains no recognized placeholder token, so existing tasks without parameters are unaffected.

#### Scenario: Plain content passes through

- **WHEN** `content="SELECT 1"` and `paramsJson` is empty
- **THEN** the resolved `content` is byte-identical to `SELECT 1` and the resolver introduces no side effects.

