ALTER TABLE rollout_allocations
    ADD COLUMN allocation_order integer;

WITH ordered AS (
    SELECT rule_id,
           variant_key,
           row_number() OVER (PARTITION BY rule_id ORDER BY variant_key) - 1 AS allocation_order
    FROM rollout_allocations
)
UPDATE rollout_allocations target
SET allocation_order = ordered.allocation_order
FROM ordered
WHERE target.rule_id = ordered.rule_id
  AND target.variant_key = ordered.variant_key;

ALTER TABLE rollout_allocations
    ALTER COLUMN allocation_order SET NOT NULL,
    ADD CONSTRAINT ck_rollout_allocations_order CHECK (allocation_order >= 0),
    ADD CONSTRAINT uk_rollout_allocations_rule_order UNIQUE (rule_id, allocation_order);
