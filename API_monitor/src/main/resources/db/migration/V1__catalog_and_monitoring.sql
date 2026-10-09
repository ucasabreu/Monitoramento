CREATE TABLE sites (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    location varchar(240),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX sites_name_unique ON sites (lower(name));

CREATE TABLE providers (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    contact varchar(240),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX providers_name_unique ON providers (lower(name));

CREATE TABLE circuits (
    id uuid PRIMARY KEY,
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    site_id uuid NOT NULL REFERENCES sites(id),
    provider_id uuid NOT NULL REFERENCES providers(id),
    role varchar(20) NOT NULL CHECK (role IN ('PRIMARY', 'SECONDARY', 'STANDALONE')),
    simulation_scenario varchar(40) NOT NULL CHECK (simulation_scenario IN
        ('STABLE', 'ALWAYS_DOWN', 'HIGH_LATENCY', 'INTERMITTENT', 'COLLECTOR_ERROR',
         'OUTAGE_CYCLE', 'INITIAL_OUTAGE', 'COLLECTOR_GAP_CYCLE')),
    archived_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX circuits_name_per_site_unique ON circuits (site_id, lower(name)) WHERE archived_at IS NULL;

CREATE TABLE monitor_states (
    circuit_id uuid PRIMARY KEY REFERENCES circuits(id),
    checkpoint jsonb NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE measurements (
    id uuid PRIMARY KEY,
    circuit_id uuid NOT NULL REFERENCES circuits(id),
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL CHECK (completed_at >= started_at),
    received_at timestamptz NOT NULL CHECK (received_at >= completed_at),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'ERROR')),
    latency_ms double precision,
    source varchar(60) NOT NULL CHECK (length(btrim(source)) > 0),
    detail varchar(1000) NOT NULL CHECK (length(btrim(detail)) > 0),
    processing_status varchar(20) NOT NULL CHECK (processing_status IN ('ACCEPTED', 'OUT_OF_ORDER')),
    result jsonb NOT NULL,
    CHECK ((outcome = 'SUCCESS' AND latency_ms IS NOT NULL AND latency_ms >= 0
            AND latency_ms < 'Infinity'::double precision)
        OR (outcome <> 'SUCCESS' AND latency_ms IS NULL))
);
CREATE INDEX measurements_circuit_time ON measurements (circuit_id, completed_at DESC, id);

CREATE TABLE incidents (
    id uuid PRIMARY KEY,
    circuit_id uuid NOT NULL REFERENCES circuits(id),
    first_failure_at timestamptz NOT NULL,
    down_confirmed_at timestamptz NOT NULL CHECK (down_confirmed_at >= first_failure_at),
    first_recovery_at timestamptz,
    recovery_confirmed_at timestamptz,
    began_without_confirmed_up boolean NOT NULL,
    has_observation_gap boolean NOT NULL,
    evidence jsonb NOT NULL,
    CHECK ((first_recovery_at IS NULL AND recovery_confirmed_at IS NULL)
        OR (first_recovery_at >= first_failure_at AND
            (recovery_confirmed_at IS NULL OR recovery_confirmed_at >= first_recovery_at)))
);
CREATE UNIQUE INDEX incidents_one_open_per_circuit ON incidents (circuit_id) WHERE recovery_confirmed_at IS NULL;
CREATE INDEX incidents_first_failure ON incidents (first_failure_at DESC, id);

CREATE TABLE monitoring_settings (
    key varchar(80) PRIMARY KEY,
    value text NOT NULL
);
