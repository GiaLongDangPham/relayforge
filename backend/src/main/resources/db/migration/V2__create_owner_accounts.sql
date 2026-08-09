CREATE TABLE public.owner_accounts
(
    id            uuid         NOT NULL,
    login_name    varchar(100) NOT NULL,
    password_hash varchar(255) NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_owner_accounts PRIMARY KEY (id),
    CONSTRAINT uk_owner_accounts_login_name UNIQUE (login_name),
    CONSTRAINT ck_owner_accounts_login_name_format
        CHECK ((login_name COLLATE "C") ~ '^[a-z0-9][a-z0-9._-]*$'),
    CONSTRAINT ck_owner_accounts_password_hash_nonblank
        CHECK (password_hash <> '' AND (password_hash COLLATE "C") !~ '[[:space:]]'),
    CONSTRAINT ck_owner_accounts_version_nonnegative CHECK (version >= 0)
);
