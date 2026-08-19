ALTER TABLE public.deliveries
    DROP CONSTRAINT uq_deliveries_event_endpoint;

ALTER TABLE public.deliveries
    ADD COLUMN replay_of_delivery_id uuid NULL,
    ADD CONSTRAINT uq_deliveries_project_event_endpoint_id
        UNIQUE (project_id, event_id, endpoint_id, id),
    ADD CONSTRAINT uq_deliveries_replay_identity
        UNIQUE (project_id, replay_of_delivery_id, id),
    ADD CONSTRAINT fk_deliveries_replay_source_identity
        FOREIGN KEY (project_id, event_id, endpoint_id, replay_of_delivery_id)
        REFERENCES public.deliveries (project_id, event_id, endpoint_id, id)
        ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_deliveries_original_event_endpoint
    ON public.deliveries (event_id, endpoint_id)
    WHERE replay_of_delivery_id IS NULL;

CREATE TABLE public.replay_requests
(
    id                 uuid         NOT NULL,
    project_id         uuid         NOT NULL,
    idempotency_key    varchar(200) NOT NULL,
    source_delivery_id uuid         NOT NULL,
    replay_delivery_id uuid         NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_replay_requests PRIMARY KEY (id),
    CONSTRAINT uq_replay_requests_project_idempotency_key UNIQUE (project_id, idempotency_key),
    CONSTRAINT uq_replay_requests_replay_delivery UNIQUE (replay_delivery_id),
    CONSTRAINT ck_replay_requests_idempotency_key_trimmed_nonblank
        CHECK (idempotency_key <> '' AND (idempotency_key COLLATE "C") !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT ck_replay_requests_distinct_deliveries
        CHECK (source_delivery_id <> replay_delivery_id),
    CONSTRAINT fk_replay_requests_replay_lineage
        FOREIGN KEY (project_id, source_delivery_id, replay_delivery_id)
        REFERENCES public.deliveries (project_id, replay_of_delivery_id, id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ix_deliveries_project_created_at_id
    ON public.deliveries (project_id, created_at DESC, id DESC);

CREATE INDEX ix_deliveries_project_event_created_at_id
    ON public.deliveries (project_id, event_id, created_at ASC, id ASC);
