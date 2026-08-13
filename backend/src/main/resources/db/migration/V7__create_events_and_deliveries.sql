CREATE TABLE public.events
(
    id                  uuid         NOT NULL,
    project_id          uuid         NOT NULL,
    event_type          varchar(200) NOT NULL,
    payload             jsonb        NOT NULL,
    idempotency_key     varchar(200) NOT NULL,
    fingerprint_version smallint     NOT NULL,
    command_fingerprint bytea        NOT NULL,
    accepted_at         timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_events PRIMARY KEY (id),
    CONSTRAINT uq_events_project_idempotency_key UNIQUE (project_id, idempotency_key),
    CONSTRAINT uq_events_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_events_project
        FOREIGN KEY (project_id)
        REFERENCES public.projects (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_events_event_type_trimmed_nonblank
        CHECK (event_type <> '' AND (event_type COLLATE "C") !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT ck_events_idempotency_key_trimmed_nonblank
        CHECK (idempotency_key <> '' AND (idempotency_key COLLATE "C") !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT ck_events_fingerprint_version_positive CHECK (fingerprint_version > 0),
    CONSTRAINT ck_events_command_fingerprint_sha256 CHECK (octet_length(command_fingerprint) = 32)
);

ALTER TABLE public.webhook_endpoints
    ADD CONSTRAINT uq_webhook_endpoints_project_id UNIQUE (project_id, id);

CREATE TABLE public.deliveries
(
    id                   uuid         NOT NULL,
    project_id           uuid         NOT NULL,
    event_id             uuid         NOT NULL,
    endpoint_id          uuid         NOT NULL,
    state                varchar(32)  NOT NULL,
    due_at               timestamptz  NULL,
    attempt_count        smallint     NOT NULL,
    claim_token          uuid         NULL,
    lease_expires_at     timestamptz  NULL,
    created_at           timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    terminal_at          timestamptz  NULL,

    CONSTRAINT pk_deliveries PRIMARY KEY (id),
    CONSTRAINT uq_deliveries_event_endpoint UNIQUE (event_id, endpoint_id),
    CONSTRAINT uq_deliveries_claim_token UNIQUE (claim_token),
    CONSTRAINT fk_deliveries_project
        FOREIGN KEY (project_id)
        REFERENCES public.projects (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_deliveries_event_project
        FOREIGN KEY (project_id, event_id)
        REFERENCES public.events (project_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_deliveries_endpoint_project
        FOREIGN KEY (project_id, endpoint_id)
        REFERENCES public.webhook_endpoints (project_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_deliveries_state
        CHECK (state IN ('PENDING', 'CLAIMED', 'SUCCEEDED', 'FAILED_PERMANENT', 'EXHAUSTED')),
    CONSTRAINT ck_deliveries_attempt_count CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT ck_deliveries_claim_pair
        CHECK ((claim_token IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT ck_deliveries_state_columns CHECK (
        (state = 'PENDING' AND due_at IS NOT NULL AND claim_token IS NULL AND terminal_at IS NULL
            AND attempt_count BETWEEN 0 AND 4)
        OR (state = 'CLAIMED' AND due_at IS NULL AND claim_token IS NOT NULL AND terminal_at IS NULL)
        OR (state = 'SUCCEEDED' AND due_at IS NULL AND claim_token IS NULL AND terminal_at IS NOT NULL
            AND attempt_count BETWEEN 1 AND 5)
        OR (state = 'FAILED_PERMANENT' AND due_at IS NULL AND claim_token IS NULL AND terminal_at IS NOT NULL
            AND attempt_count BETWEEN 1 AND 5)
        OR (state = 'EXHAUSTED' AND due_at IS NULL AND claim_token IS NULL AND terminal_at IS NOT NULL
            AND attempt_count = 5)
    )
);

CREATE INDEX ix_events_project_accepted_at_id
    ON public.events (project_id, accepted_at DESC, id DESC);

CREATE INDEX ix_deliveries_pending_due_at_id
    ON public.deliveries (due_at, id)
    WHERE state = 'PENDING';
