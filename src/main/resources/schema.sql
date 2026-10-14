-- Runs on every startup, so every statement must be idempotent.
-- Timestamps are Unix nanoseconds (as sent in OTLP); durations are milliseconds.

-- One row per request or background job: the list view.
CREATE TABLE IF NOT EXISTS traces (
    trace_id        TEXT    PRIMARY KEY,               -- hex
    service         TEXT    NOT NULL,                  -- resource service.name
    instance_id     TEXT,                              -- resource service.instance.id, changes on restart
    kind            TEXT    NOT NULL DEFAULT 'unknown'
                    CHECK (kind IN ('http', 'job', 'message', 'unknown')),
    name            TEXT,                              -- root span name, e.g. NotificationWorker.run
    method          TEXT,                              -- http.request.method
    route           TEXT,                              -- http.route, groups requests
    path            TEXT,                              -- url.path, has the real IDs
    query           TEXT,                              -- url.query
    status_code     INTEGER,                           -- http.response.status_code
    start_ns        INTEGER NOT NULL,
    duration_ms     REAL,
    span_count      INTEGER NOT NULL DEFAULT 0,
    db_count        INTEGER NOT NULL DEFAULT 0,
    db_time_ms      REAL    NOT NULL DEFAULT 0,
    http_out_count  INTEGER NOT NULL DEFAULT 0,
    error_count     INTEGER NOT NULL DEFAULT 0,
    issue_count     INTEGER NOT NULL DEFAULT 0,
    top_issue       TEXT,                              -- e.g. 'N+1 on order_items (50x)'
    has_root        INTEGER NOT NULL DEFAULT 0,        -- 0 = root span never arrived
    is_warmup       INTEGER NOT NULL DEFAULT 0,        -- first calls of a route after a restart
    -- Spans arrive in batches and the root arrives last, so a trace stays
    -- 'open' until it is finalized (root + grace period, or timeout).
    state           TEXT    NOT NULL DEFAULT 'open'
                    CHECK (state IN ('open', 'complete')),
    last_received_at INTEGER NOT NULL                  -- epoch ms of the last batch for this trace
);
CREATE INDEX IF NOT EXISTS idx_traces_start ON traces (start_ns DESC);
CREATE INDEX IF NOT EXISTS idx_traces_route ON traces (route, start_ns DESC);
CREATE INDEX IF NOT EXISTS idx_traces_open  ON traces (state, last_received_at);

-- The steps inside a trace: the detail view and waterfall.
CREATE TABLE IF NOT EXISTS spans (
    trace_id        TEXT    NOT NULL REFERENCES traces (trace_id) ON DELETE CASCADE,
    span_id         TEXT    NOT NULL,
    parent_span_id  TEXT,                              -- NULL for the root
    name            TEXT    NOT NULL,
    category        TEXT    NOT NULL DEFAULT 'other'
                    CHECK (category IN ('http_server', 'db', 'http_client', 'messaging', 'code', 'other')),
    scope_name      TEXT,                              -- instrumentation, e.g. io.opentelemetry.hibernate-6.0; used to merge Hibernate + JDBC
    start_ns        INTEGER NOT NULL,                  -- offset in the waterfall = start_ns - traces.start_ns
    duration_ms     REAL    NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'unset'
                    CHECK (status IN ('unset', 'ok', 'error')),
    error_type      TEXT,                              -- exception.type
    error_message   TEXT,                              -- exception.message or status message
    stacktrace      TEXT,                              -- exception.stacktrace, first 30 lines
    code_function   TEXT,                              -- e.g. UserRepository.findByEmail
    thread_name     TEXT,                              -- kept for async and job spans
    -- Database
    db_system       TEXT,                              -- db.system.name / db.system
    db_operation    TEXT,                              -- db.operation.name / db.operation
    db_table        TEXT,                              -- db.collection.name / db.sql.table
    sql             TEXT,                              -- db.query.text / db.statement
    sql_shape       TEXT,                              -- hash of the normalized SQL, see query_shapes
    -- Outgoing HTTP
    http_method     TEXT,
    http_url        TEXT,                              -- url.full
    http_host       TEXT,                              -- server.address
    http_status     INTEGER,
    -- Messaging
    msg_system      TEXT,                              -- messaging.system
    msg_destination TEXT,                              -- messaging.destination.name
    raw_attrs       TEXT,                              -- every other attribute as JSON, for the "raw" toggle
    PRIMARY KEY (trace_id, span_id)
);
CREATE INDEX IF NOT EXISTS idx_spans_shape ON spans (sql_shape);

-- Each distinct query, aggregated across all requests: the insights page.
CREATE TABLE IF NOT EXISTS query_shapes (
    shape_hash      TEXT    PRIMARY KEY,
    normalized_sql  TEXT    NOT NULL,                  -- lowercased, literals -> ?, IN (?, ?) -> IN (?)
    db_system       TEXT,
    db_table        TEXT,
    db_operation    TEXT,
    total_count     INTEGER NOT NULL DEFAULT 0,
    total_time_ms   REAL    NOT NULL DEFAULT 0,
    max_ms          REAL    NOT NULL DEFAULT 0,
    trace_count     INTEGER NOT NULL DEFAULT 0,
    routes          TEXT    NOT NULL DEFAULT '[]',     -- JSON list of routes that ran it
    first_seen_ns   INTEGER NOT NULL,
    last_seen_ns    INTEGER NOT NULL
);

-- Detected problems: what find_issues and the issues panel read.
CREATE TABLE IF NOT EXISTS issues (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id        TEXT    NOT NULL REFERENCES traces (trace_id) ON DELETE CASCADE,
    type            TEXT    NOT NULL
                    CHECK (type IN ('n_plus_one', 'slow_query', 'repeated_query', 'error',
                                    'slow_http', 'missing_root', 'hot_query')),
    severity        TEXT    NOT NULL CHECK (severity IN ('high', 'medium', 'info')),
    title           TEXT    NOT NULL,                  -- e.g. 'N+1 on order_items (50x)'
    shape_hash      TEXT,                              -- the query involved, if any
    span_ids        TEXT    NOT NULL DEFAULT '[]',     -- JSON list of the evidence spans
    count           INTEGER,
    time_ms         REAL,
    share_pct       REAL,                              -- share of the trace's duration
    code_function   TEXT,                              -- where it was triggered
    suggestion      TEXT,
    created_at      INTEGER NOT NULL                   -- epoch ms
);
CREATE INDEX IF NOT EXISTS idx_issues_trace ON issues (trace_id);
CREATE INDEX IF NOT EXISTS idx_issues_type  ON issues (type, severity);
