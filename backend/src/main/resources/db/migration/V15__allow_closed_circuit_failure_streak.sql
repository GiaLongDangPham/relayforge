ALTER TABLE public.endpoint_circuit_breakers
    DROP CONSTRAINT ck_endpoint_circuit_breakers_state_shape;

ALTER TABLE public.endpoint_circuit_breakers
    ADD CONSTRAINT ck_endpoint_circuit_breakers_state_shape CHECK (
        (state = 'CLOSED'
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
    );
