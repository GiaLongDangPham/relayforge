CREATE TABLE public.delivery_attempts
(
    id                              uuid        NOT NULL,
    delivery_id                     uuid        NOT NULL,
    attempt_number                  smallint    NOT NULL,
    claim_token                     uuid        NOT NULL,
    status                          varchar(32) NOT NULL,
    destination_fingerprint_version smallint    NOT NULL,
    destination_fingerprint         bytea       NOT NULL,
    started_at                      timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at                     timestamptz NULL,
    http_status                     smallint    NULL,
    failure_code                    varchar(64) NULL,
    latency_ms                      integer     NULL,
    response_preview                bytea       NULL,
    response_truncated              boolean     NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_delivery_attempts PRIMARY KEY (id),
    CONSTRAINT uq_delivery_attempts_delivery_attempt_number UNIQUE (delivery_id, attempt_number),
    CONSTRAINT fk_delivery_attempts_delivery
        FOREIGN KEY (delivery_id)
        REFERENCES public.deliveries (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_delivery_attempts_attempt_number CHECK (attempt_number BETWEEN 1 AND 5),
    CONSTRAINT ck_delivery_attempts_status
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'UNKNOWN')),
    CONSTRAINT ck_delivery_attempts_destination_fingerprint_version_positive
        CHECK (destination_fingerprint_version > 0),
    CONSTRAINT ck_delivery_attempts_destination_fingerprint_sha256
        CHECK (octet_length(destination_fingerprint) = 32),
    CONSTRAINT ck_delivery_attempts_latency_nonnegative
        CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ck_delivery_attempts_started_shape CHECK (
        (status = 'STARTED' AND finished_at IS NULL AND http_status IS NULL AND failure_code IS NULL
            AND latency_ms IS NULL AND response_preview IS NULL AND response_truncated = FALSE)
        OR (status <> 'STARTED' AND finished_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_delivery_attempts_one_started_per_delivery
    ON public.delivery_attempts (delivery_id)
    WHERE status = 'STARTED';
