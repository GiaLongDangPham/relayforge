CREATE TABLE public.project_api_keys
(
    id            uuid         NOT NULL,
    project_id    uuid         NOT NULL,
    display_name  varchar(120) NOT NULL,
    key_hint      varchar(24)  NOT NULL,
    secret_digest bytea        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at    timestamptz,

    CONSTRAINT pk_project_api_keys PRIMARY KEY (id),
    CONSTRAINT fk_project_api_keys_project
        FOREIGN KEY (project_id)
        REFERENCES public.projects (id)
        ON DELETE RESTRICT,
    CONSTRAINT uk_project_api_keys_key_hint UNIQUE (key_hint),
    CONSTRAINT uk_project_api_keys_secret_digest UNIQUE (secret_digest),
    CONSTRAINT ck_project_api_keys_display_name_trimmed_nonblank
        CHECK (display_name <> '' AND (display_name COLLATE "C") !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT ck_project_api_keys_key_hint_nonblank CHECK (key_hint <> ''),
    CONSTRAINT ck_project_api_keys_secret_digest_length CHECK (octet_length(secret_digest) = 32)
);

CREATE INDEX ix_project_api_keys_project_created_at_id
    ON public.project_api_keys (project_id, created_at DESC, id DESC);
