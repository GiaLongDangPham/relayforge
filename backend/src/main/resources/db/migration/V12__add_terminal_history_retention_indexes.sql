CREATE INDEX ix_events_retention_accepted_at_id
    ON public.events (accepted_at, id);

CREATE INDEX ix_deliveries_retention_event_state_terminal_at
    ON public.deliveries (event_id, state, terminal_at);
