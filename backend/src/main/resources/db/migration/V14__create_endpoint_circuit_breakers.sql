CREATE TABLE public.endpoint_circuit_breakers
(
    endpoint_id                      uuid        NOT NULL,
    state                            varchar(32) NOT NULL DEFAULT 'CLOSED',
    consecutive_qualifying_failures  integer     NOT NULL DEFAULT 0,
    open_until                       timestamptz NULL,
    probe_delivery_id                uuid        NULL,
    probe_claim_token                uuid        NULL,
    updated_at                       timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_endpoint_circuit_breakers PRIMARY KEY (endpoint_id),
    CONSTRAINT fk_endpoint_circuit_breakers_endpoint
        FOREIGN KEY (endpoint_id)
        REFERENCES public.webhook_endpoints (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_endpoint_circuit_breakers_probe_delivery
        FOREIGN KEY (probe_delivery_id)
        REFERENCES public.deliveries (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_endpoint_circuit_breakers_state
        CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    CONSTRAINT ck_endpoint_circuit_breakers_failure_count_nonnegative
        CHECK (consecutive_qualifying_failures >= 0),
    CONSTRAINT ck_endpoint_circuit_breakers_state_shape CHECK (
        (state = 'CLOSED'
            AND consecutive_qualifying_failures = 0
            AND open_until IS NULL
            AND probe_delivery_id IS NULL
            AND probe_claim_token IS NULL)
        OR (state = 'OPEN'
            AND consecutive_qualifying_failures > 0
            AND open_until IS NOT NULL
            AND probe_delivery_id IS NULL
            AND probe_claim_token IS NULL)
        OR (state = 'HALF_OPEN'
            AND consecutive_qualifying_failures > 0
            AND open_until IS NULL
            AND probe_delivery_id IS NOT NULL
            AND probe_claim_token IS NOT NULL)
    )
);
