TRUNCATE TABLE tenants CASCADE;

BEGIN;

INSERT INTO tenants (id, tenant_key, name, created_at, updated_at)
VALUES ('018f1000-0000-7000-8000-000000000001', 'acme', 'Acme', now(), now());

INSERT INTO tenant_members (tenant_id, principal_id, role, created_at, updated_at)
VALUES (
  '018f1000-0000-7000-8000-000000000001',
  'phase8-operator', 'TENANT_OWNER', now(), now()
);

INSERT INTO projects (id, tenant_id, project_key, name, created_at, updated_at)
VALUES ('018f1000-0000-7000-8000-000000000002', '018f1000-0000-7000-8000-000000000001', 'checkout', 'Checkout', now(), now());

INSERT INTO environments (
  id, tenant_id, project_id, environment_key, name, environment_type,
  current_snapshot_version, created_at, updated_at
) VALUES (
  '018f1000-0000-7000-8000-000000000003',
  '018f1000-0000-7000-8000-000000000001',
  '018f1000-0000-7000-8000-000000000002',
  'production', 'Production', 'PRODUCTION', 0, now(), now()
);

INSERT INTO feature_flags (
  id, tenant_id, project_id, flag_key, value_type, lifecycle_status, created_at, updated_at
) VALUES (
  '018f1000-0000-7000-8000-000000000006',
  '018f1000-0000-7000-8000-000000000001',
  '018f1000-0000-7000-8000-000000000002',
  'checkout-v2', 'BOOLEAN', 'ACTIVE', now(), now()
);

INSERT INTO flag_revisions (
  id, tenant_id, project_id, feature_flag_id, revision_number, value_type,
  lifecycle_state, default_variant_key, rollout_seed, created_at, updated_at
) VALUES (
  '018f1000-0000-7000-8000-000000000007',
  '018f1000-0000-7000-8000-000000000001',
  '018f1000-0000-7000-8000-000000000002',
  '018f1000-0000-7000-8000-000000000006',
  1, 'BOOLEAN', 'DRAFT', 'off', 'checkout-seed', now(), now()
);

INSERT INTO flag_variants (revision_id, variant_key, value_type, value) VALUES
  ('018f1000-0000-7000-8000-000000000007', 'off', 'BOOLEAN', 'false'::jsonb),
  ('018f1000-0000-7000-8000-000000000007', 'on', 'BOOLEAN', 'true'::jsonb);

INSERT INTO targeting_rules (id, revision_id, priority, result_type, result_variant_key)
VALUES (
  '018f1000-0000-7000-8000-000000000008',
  '018f1000-0000-7000-8000-000000000007',
  10, 'VARIANT', 'on'
);

INSERT INTO rule_conditions (id, rule_id, condition_order, attribute, operator, operand)
VALUES (
  '018f1000-0000-7000-8000-000000000009',
  '018f1000-0000-7000-8000-000000000008',
  0, 'plan', 'EQUALS', '"premium"'::jsonb
);

INSERT INTO client_applications (
  id, tenant_id, project_id, environment_id, client_application_key,
  lifecycle_status, created_at, updated_at
) VALUES (
  '018f1000-0000-7000-8000-000000000004',
  '018f1000-0000-7000-8000-000000000001',
  '018f1000-0000-7000-8000-000000000002',
  '018f1000-0000-7000-8000-000000000003',
  'orders', 'ACTIVE', now(), now()
);

INSERT INTO service_credentials (
  id, tenant_id, project_id, client_application_id, secret_hash,
  secret_prefix, status, created_at
) VALUES (
  '018f1000-0000-7000-8000-000000000005',
  '018f1000-0000-7000-8000-000000000001',
  '018f1000-0000-7000-8000-000000000002',
  '018f1000-0000-7000-8000-000000000004',
  '$2y$04$ct9z32ZDuCdKJhmoRY.DP.QwhTsxZjAZB32hRAVA29XSJwvoupe8G',
  'phase8', 'ACTIVE', now()
);

COMMIT;
