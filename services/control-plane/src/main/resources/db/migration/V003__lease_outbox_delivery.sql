ALTER TABLE outbox_events
    ADD COLUMN claim_token uuid,
    ADD COLUMN claimed_at timestamptz,
    ADD CONSTRAINT ck_outbox_events_claim CHECK (
        (claim_token IS NULL AND claimed_at IS NULL)
        OR (claim_token IS NOT NULL AND claimed_at IS NOT NULL)
    );

DROP INDEX ix_outbox_events_pending;
CREATE INDEX ix_outbox_events_pending
    ON outbox_events (next_attempt_at, claimed_at, created_at, id)
    WHERE published_at IS NULL;

COMMENT ON COLUMN outbox_events.claim_token IS
    'Short-transaction relay lease token; delivery network I/O runs after the claim commits.';
COMMENT ON COLUMN outbox_events.claimed_at IS
    'Lease acquisition time; an expired lease may be reclaimed for at-least-once delivery.';
