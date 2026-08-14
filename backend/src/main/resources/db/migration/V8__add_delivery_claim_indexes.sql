CREATE INDEX ix_deliveries_pending_endpoint_due_at_id
    ON public.deliveries (endpoint_id, due_at, id)
    WHERE state = 'PENDING';

CREATE INDEX ix_deliveries_claimed_lease_expires_at_id
    ON public.deliveries (lease_expires_at, id)
    WHERE state = 'CLAIMED';
