CREATE TABLE public.webhook_endpoints
(
    id                        uuid          NOT NULL,
    project_id                uuid          NOT NULL,
    name                      varchar(120)  NOT NULL,
    destination_url           varchar(2048) NOT NULL,
    enabled                   boolean       NOT NULL,
    signing_secret_ciphertext bytea         NOT NULL,
    encryption_key_reference  varchar(128)  NOT NULL,
    version                   bigint        NOT NULL DEFAULT 0,
    created_at                timestamptz   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                timestamptz   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_webhook_endpoints PRIMARY KEY (id),
    CONSTRAINT fk_webhook_endpoints_project
        FOREIGN KEY (project_id)
        REFERENCES public.projects (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_webhook_endpoints_name_trimmed_nonblank
        CHECK (name <> '' AND (name COLLATE "C") !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT ck_webhook_endpoints_destination_url_nonblank
        CHECK (destination_url <> '' AND (destination_url COLLATE "C") !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT ck_webhook_endpoints_secret_ciphertext_nonblank
        CHECK (octet_length(signing_secret_ciphertext) > 0),
    CONSTRAINT ck_webhook_endpoints_key_reference_nonblank
        CHECK (encryption_key_reference <> ''),
    CONSTRAINT ck_webhook_endpoints_version_nonnegative CHECK (version >= 0)
);

CREATE TABLE public.endpoint_subscriptions
(
    endpoint_id uuid         NOT NULL,
    event_type  varchar(200) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_endpoint_subscriptions PRIMARY KEY (endpoint_id, event_type),
    CONSTRAINT fk_endpoint_subscriptions_endpoint
        FOREIGN KEY (endpoint_id)
        REFERENCES public.webhook_endpoints (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_endpoint_subscriptions_event_type_trimmed_nonblank
        CHECK (event_type <> '' AND (event_type COLLATE "C") !~ '^[[:space:]]|[[:space:]]$')
);

CREATE INDEX ix_webhook_endpoints_project_created_at_id
    ON public.webhook_endpoints (project_id, created_at DESC, id DESC);

CREATE INDEX ix_endpoint_subscriptions_event_type_endpoint_id
    ON public.endpoint_subscriptions (event_type, endpoint_id);
