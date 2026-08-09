-- Portfolio v1 uses PostgreSQL 17 as its minimum tested database baseline.
-- Business tables intentionally begin in later focused migrations.
DO $relayforge$
BEGIN
    IF current_setting('server_version_num')::integer < 170000 THEN
        RAISE EXCEPTION 'RelayForge requires PostgreSQL 17 or newer';
    END IF;
END
$relayforge$;
