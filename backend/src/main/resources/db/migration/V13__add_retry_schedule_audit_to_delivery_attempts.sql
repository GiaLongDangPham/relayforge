ALTER TABLE public.delivery_attempts
    ADD COLUMN retry_delay_ms integer NULL,
    ADD COLUMN retry_schedule_source varchar(32) NULL,
    ADD CONSTRAINT ck_delivery_attempts_retry_delay_positive
        CHECK (retry_delay_ms IS NULL OR retry_delay_ms > 0),
    ADD CONSTRAINT ck_delivery_attempts_retry_schedule_source
        CHECK (retry_schedule_source IS NULL OR retry_schedule_source IN ('BACKOFF', 'RETRY_AFTER')),
    ADD CONSTRAINT ck_delivery_attempts_retry_schedule_pair
        CHECK ((retry_delay_ms IS NULL) = (retry_schedule_source IS NULL)),
    ADD CONSTRAINT ck_delivery_attempts_retry_schedule_status
        CHECK (retry_schedule_source IS NULL OR status IN ('RETRYABLE_FAILURE', 'UNKNOWN'));
