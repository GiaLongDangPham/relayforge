CREATE TABLE public.attempt_late_diagnostics
(
    id              uuid        NOT NULL,
    attempt_id      uuid        NOT NULL,
    claim_token     uuid        NOT NULL,
    observed_status varchar(32) NOT NULL,
    http_status     smallint    NULL,
    failure_code    varchar(64) NULL,
    latency_ms      integer     NULL,
    observed_at     timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_attempt_late_diagnostics PRIMARY KEY (id),
    CONSTRAINT uq_attempt_late_diagnostics_attempt UNIQUE (attempt_id),
    CONSTRAINT fk_attempt_late_diagnostics_attempt
        FOREIGN KEY (attempt_id)
        REFERENCES public.delivery_attempts (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_attempt_late_diagnostics_observed_status
        CHECK (observed_status IN ('SUCCEEDED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),
    CONSTRAINT ck_attempt_late_diagnostics_http_status
        CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT ck_attempt_late_diagnostics_latency_nonnegative
        CHECK (latency_ms IS NULL OR latency_ms >= 0)
);
