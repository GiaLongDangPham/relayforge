ALTER TABLE public.webhook_endpoints
    ADD COLUMN minimum_retry_delay_seconds integer NULL,
    ADD CONSTRAINT ck_webhook_endpoints_minimum_retry_delay_seconds
        CHECK (minimum_retry_delay_seconds IS NULL OR minimum_retry_delay_seconds BETWEEN 5 AND 300);

ALTER TABLE public.delivery_attempts
    DROP CONSTRAINT ck_delivery_attempts_retry_schedule_source,
    ADD CONSTRAINT ck_delivery_attempts_retry_schedule_source
        CHECK (retry_schedule_source IS NULL OR retry_schedule_source IN ('BACKOFF', 'RETRY_AFTER', 'ENDPOINT_POLICY'));
