CREATE TABLE public.projects
(
    id         uuid         NOT NULL,
    owner_id   uuid         NOT NULL,
    name       varchar(120) NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_projects PRIMARY KEY (id),
    CONSTRAINT fk_projects_owner
        FOREIGN KEY (owner_id)
        REFERENCES public.owner_accounts (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_projects_name_trimmed_nonblank
        CHECK (name <> '' AND (name COLLATE "C") !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT ck_projects_version_nonnegative CHECK (version >= 0)
);

CREATE INDEX ix_projects_owner_created_at_id
    ON public.projects (owner_id, created_at DESC, id DESC);
