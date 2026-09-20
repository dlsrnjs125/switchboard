CREATE TABLE tenants (
    id uuid NOT NULL,
    tenant_key varchar(128) NOT NULL,
    name varchar(200) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_tenants PRIMARY KEY (id),
    CONSTRAINT uk_tenants_tenant_key UNIQUE (tenant_key),
    CONSTRAINT uk_tenants_id_tenant_key UNIQUE (id, tenant_key),
    CONSTRAINT ck_tenants_key CHECK (tenant_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    CONSTRAINT ck_tenants_name CHECK (btrim(name) <> '')
);

CREATE TABLE tenant_members (
    tenant_id uuid NOT NULL,
    principal_id varchar(255) NOT NULL,
    role varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_tenant_members PRIMARY KEY (tenant_id, principal_id),
    CONSTRAINT fk_tenant_members_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tenant_members_principal CHECK (btrim(principal_id) <> ''),
    CONSTRAINT ck_tenant_members_role CHECK (role IN ('TENANT_OWNER', 'PROJECT_MAINTAINER', 'DEVELOPER', 'VIEWER', 'AUDITOR'))
);

CREATE TABLE projects (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    project_key varchar(128) NOT NULL,
    name varchar(200) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    CONSTRAINT pk_projects PRIMARY KEY (id),
    CONSTRAINT fk_projects_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT uk_projects_tenant_key UNIQUE (tenant_id, project_key),
    CONSTRAINT uk_projects_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_projects_key CHECK (project_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    CONSTRAINT ck_projects_name CHECK (btrim(name) <> '')
);

CREATE TABLE environments (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    environment_key varchar(128) NOT NULL,
    name varchar(200) NOT NULL,
    environment_type varchar(32) NOT NULL,
    current_snapshot_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    CONSTRAINT pk_environments PRIMARY KEY (id),
    CONSTRAINT fk_environments_project FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uk_environments_tenant_project_key UNIQUE (tenant_id, project_id, environment_key),
    CONSTRAINT uk_environments_tenant_project_id UNIQUE (tenant_id, project_id, id),
    CONSTRAINT ck_environments_key CHECK (environment_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    CONSTRAINT ck_environments_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_environments_type CHECK (environment_type IN ('DEVELOPMENT', 'STAGING', 'PRODUCTION')),
    CONSTRAINT ck_environments_snapshot_version CHECK (current_snapshot_version >= 0)
);

CREATE TABLE feature_flags (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    flag_key varchar(128) NOT NULL,
    value_type varchar(16) NOT NULL,
    lifecycle_status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    CONSTRAINT pk_feature_flags PRIMARY KEY (id),
    CONSTRAINT fk_feature_flags_project FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uk_feature_flags_tenant_project_key UNIQUE (tenant_id, project_id, flag_key),
    CONSTRAINT uk_feature_flags_tenant_project_id UNIQUE (tenant_id, project_id, id),
    CONSTRAINT uk_feature_flags_id_type UNIQUE (id, value_type),
    CONSTRAINT ck_feature_flags_key CHECK (flag_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    CONSTRAINT ck_feature_flags_value_type CHECK (value_type IN ('BOOLEAN', 'STRING', 'NUMBER', 'OBJECT')),
    CONSTRAINT ck_feature_flags_lifecycle CHECK (lifecycle_status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_feature_flags_archive_time CHECK (
        (lifecycle_status = 'ACTIVE' AND archived_at IS NULL)
        OR (lifecycle_status = 'ARCHIVED' AND archived_at IS NOT NULL)
    )
);

CREATE TABLE flag_revisions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    feature_flag_id uuid NOT NULL,
    revision_number bigint NOT NULL,
    value_type varchar(16) NOT NULL,
    lifecycle_state varchar(16) NOT NULL DEFAULT 'DRAFT',
    default_variant_key varchar(128) NOT NULL,
    rollout_seed text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT pk_flag_revisions PRIMARY KEY (id),
    CONSTRAINT fk_flag_revisions_flag FOREIGN KEY (tenant_id, project_id, feature_flag_id) REFERENCES feature_flags (tenant_id, project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_flag_revisions_flag_type FOREIGN KEY (feature_flag_id, value_type) REFERENCES feature_flags (id, value_type) ON DELETE RESTRICT,
    CONSTRAINT uk_flag_revisions_number UNIQUE (feature_flag_id, revision_number),
    CONSTRAINT uk_flag_revisions_scope_id UNIQUE (tenant_id, project_id, feature_flag_id, id),
    CONSTRAINT uk_flag_revisions_id_type UNIQUE (id, value_type),
    CONSTRAINT ck_flag_revisions_number CHECK (revision_number >= 1),
    CONSTRAINT ck_flag_revisions_lifecycle CHECK (lifecycle_state IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_flag_revisions_seed CHECK (btrim(rollout_seed) <> ''),
    CONSTRAINT ck_flag_revisions_publish_time CHECK (
        (lifecycle_state = 'DRAFT' AND published_at IS NULL)
        OR (lifecycle_state = 'PUBLISHED' AND published_at IS NOT NULL)
    )
);

CREATE TABLE flag_variants (
    revision_id uuid NOT NULL,
    variant_key varchar(128) NOT NULL,
    value_type varchar(16) NOT NULL,
    value jsonb NOT NULL,
    CONSTRAINT pk_flag_variants PRIMARY KEY (revision_id, variant_key),
    CONSTRAINT fk_flag_variants_revision_type FOREIGN KEY (revision_id, value_type) REFERENCES flag_revisions (id, value_type) ON DELETE CASCADE,
    CONSTRAINT ck_flag_variants_key CHECK (variant_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    CONSTRAINT ck_flag_variants_value CHECK (
        (value_type = 'BOOLEAN' AND jsonb_typeof(value) = 'boolean')
        OR (value_type = 'STRING' AND jsonb_typeof(value) = 'string')
        OR (value_type = 'NUMBER' AND jsonb_typeof(value) = 'number')
        OR (value_type = 'OBJECT' AND jsonb_typeof(value) = 'object')
    )
);

ALTER TABLE flag_revisions
    ADD CONSTRAINT fk_flag_revisions_default_variant
    FOREIGN KEY (id, default_variant_key)
    REFERENCES flag_variants (revision_id, variant_key)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE targeting_rules (
    id uuid NOT NULL,
    revision_id uuid NOT NULL,
    priority integer NOT NULL,
    result_type varchar(16) NOT NULL,
    result_variant_key varchar(128),
    CONSTRAINT pk_targeting_rules PRIMARY KEY (id),
    CONSTRAINT fk_targeting_rules_revision FOREIGN KEY (revision_id) REFERENCES flag_revisions (id) ON DELETE CASCADE,
    CONSTRAINT uk_targeting_rules_revision_priority UNIQUE (revision_id, priority),
    CONSTRAINT uk_targeting_rules_revision_id UNIQUE (revision_id, id),
    CONSTRAINT fk_targeting_rules_result_variant FOREIGN KEY (revision_id, result_variant_key) REFERENCES flag_variants (revision_id, variant_key) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_targeting_rules_priority CHECK (priority >= 0),
    CONSTRAINT ck_targeting_rules_result CHECK (
        (result_type = 'VARIANT' AND result_variant_key IS NOT NULL)
        OR (result_type = 'ROLLOUT' AND result_variant_key IS NULL)
    )
);

CREATE TABLE rule_conditions (
    id uuid NOT NULL,
    rule_id uuid NOT NULL,
    condition_order integer NOT NULL,
    attribute varchar(255) NOT NULL,
    operator varchar(32) NOT NULL,
    operand jsonb,
    CONSTRAINT pk_rule_conditions PRIMARY KEY (id),
    CONSTRAINT fk_rule_conditions_rule FOREIGN KEY (rule_id) REFERENCES targeting_rules (id) ON DELETE CASCADE,
    CONSTRAINT uk_rule_conditions_order UNIQUE (rule_id, condition_order),
    CONSTRAINT ck_rule_conditions_order CHECK (condition_order >= 0),
    CONSTRAINT ck_rule_conditions_attribute CHECK (btrim(attribute) <> ''),
    CONSTRAINT ck_rule_conditions_operator CHECK (operator IN (
        'EQUALS', 'NOT_EQUALS', 'IN', 'NOT_IN', 'GREATER_THAN', 'GREATER_THAN_OR_EQUALS',
        'LESS_THAN', 'LESS_THAN_OR_EQUALS', 'EXISTS', 'NOT_EXISTS', 'STARTS_WITH', 'ENDS_WITH', 'CONTAINS'
    )),
    CONSTRAINT ck_rule_conditions_operand CHECK (
        (operator IN ('EXISTS', 'NOT_EXISTS') AND operand IS NULL)
        OR (operator NOT IN ('EXISTS', 'NOT_EXISTS') AND operand IS NOT NULL)
    ),
    CONSTRAINT ck_rule_conditions_collection_operand CHECK (
        operator NOT IN ('IN', 'NOT_IN')
        OR (jsonb_typeof(operand) = 'array' AND jsonb_array_length(operand) > 0)
    )
);

CREATE TABLE rollout_allocations (
    rule_id uuid NOT NULL,
    variant_key varchar(128) NOT NULL,
    revision_id uuid NOT NULL,
    basis_points integer NOT NULL,
    CONSTRAINT pk_rollout_allocations PRIMARY KEY (rule_id, variant_key),
    CONSTRAINT fk_rollout_allocations_rule FOREIGN KEY (revision_id, rule_id) REFERENCES targeting_rules (revision_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_rollout_allocations_variant FOREIGN KEY (revision_id, variant_key) REFERENCES flag_variants (revision_id, variant_key) ON DELETE CASCADE,
    CONSTRAINT ck_rollout_allocations_basis_points CHECK (basis_points BETWEEN 1 AND 10000)
);

CREATE TABLE environment_flag_states (
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    feature_flag_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    enabled boolean NOT NULL,
    environment_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_environment_flag_states PRIMARY KEY (environment_id, feature_flag_id),
    CONSTRAINT fk_environment_flag_states_environment FOREIGN KEY (tenant_id, project_id, environment_id) REFERENCES environments (tenant_id, project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_environment_flag_states_flag FOREIGN KEY (tenant_id, project_id, feature_flag_id) REFERENCES feature_flags (tenant_id, project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_environment_flag_states_revision FOREIGN KEY (tenant_id, project_id, feature_flag_id, revision_id) REFERENCES flag_revisions (tenant_id, project_id, feature_flag_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_environment_flag_states_version CHECK (environment_version >= 1)
);

CREATE TABLE configuration_snapshots (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    snapshot_version bigint NOT NULL,
    schema_version integer NOT NULL,
    payload jsonb NOT NULL,
    checksum char(64) NOT NULL,
    generated_at timestamptz NOT NULL,
    CONSTRAINT pk_configuration_snapshots PRIMARY KEY (id),
    CONSTRAINT fk_configuration_snapshots_environment FOREIGN KEY (tenant_id, project_id, environment_id) REFERENCES environments (tenant_id, project_id, id) ON DELETE RESTRICT,
    CONSTRAINT uk_configuration_snapshots_version UNIQUE (environment_id, snapshot_version),
    CONSTRAINT uk_configuration_snapshots_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_configuration_snapshots_scope_id UNIQUE (tenant_id, project_id, environment_id, id),
    CONSTRAINT ck_configuration_snapshots_version CHECK (snapshot_version >= 1),
    CONSTRAINT ck_configuration_snapshots_schema CHECK (schema_version >= 1),
    CONSTRAINT ck_configuration_snapshots_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_configuration_snapshots_checksum CHECK (checksum ~ '^[a-f0-9]{64}$')
);

CREATE TABLE snapshot_entries (
    snapshot_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    feature_flag_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    enabled boolean NOT NULL,
    CONSTRAINT pk_snapshot_entries PRIMARY KEY (snapshot_id, feature_flag_id),
    CONSTRAINT fk_snapshot_entries_snapshot FOREIGN KEY (tenant_id, project_id, environment_id, snapshot_id) REFERENCES configuration_snapshots (tenant_id, project_id, environment_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_snapshot_entries_revision FOREIGN KEY (tenant_id, project_id, feature_flag_id, revision_id) REFERENCES flag_revisions (tenant_id, project_id, feature_flag_id, id) ON DELETE RESTRICT
);

CREATE TABLE client_applications (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    client_application_key varchar(128) NOT NULL,
    lifecycle_status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    CONSTRAINT pk_client_applications PRIMARY KEY (id),
    CONSTRAINT fk_client_applications_environment FOREIGN KEY (tenant_id, project_id, environment_id) REFERENCES environments (tenant_id, project_id, id) ON DELETE RESTRICT,
    CONSTRAINT uk_client_applications_tenant_project_key UNIQUE (tenant_id, project_id, client_application_key),
    CONSTRAINT uk_client_applications_tenant_project_id UNIQUE (tenant_id, project_id, id),
    CONSTRAINT ck_client_applications_key CHECK (client_application_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    CONSTRAINT ck_client_applications_lifecycle CHECK (lifecycle_status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_client_applications_archive_time CHECK (
        (lifecycle_status = 'ACTIVE' AND archived_at IS NULL)
        OR (lifecycle_status = 'ARCHIVED' AND archived_at IS NOT NULL)
    )
);

CREATE TABLE service_credentials (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    client_application_id uuid NOT NULL,
    secret_hash text NOT NULL,
    secret_prefix varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    expires_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT pk_service_credentials PRIMARY KEY (id),
    CONSTRAINT fk_service_credentials_application FOREIGN KEY (tenant_id, project_id, client_application_id) REFERENCES client_applications (tenant_id, project_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_service_credentials_hash CHECK (btrim(secret_hash) <> ''),
    CONSTRAINT ck_service_credentials_prefix CHECK (btrim(secret_prefix) <> ''),
    CONSTRAINT ck_service_credentials_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_service_credentials_revoke_time CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
    )
);

CREATE TABLE audit_events (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_type varchar(16) NOT NULL,
    actor_id varchar(255) NOT NULL,
    action varchar(100) NOT NULL,
    resource_type varchar(100) NOT NULL,
    resource_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_audit_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_events_actor_type CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM')),
    CONSTRAINT ck_audit_events_actor_id CHECK (btrim(actor_id) <> ''),
    CONSTRAINT ck_audit_events_details CHECK (jsonb_typeof(details) = 'object')
);

CREATE TABLE outbox_events (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    event_version integer NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    last_error text,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT fk_outbox_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbox_events_snapshot FOREIGN KEY (tenant_id, snapshot_id) REFERENCES configuration_snapshots (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbox_events_version CHECK (event_version >= 1),
    CONSTRAINT ck_outbox_events_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_events_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX ix_feature_flags_active_list
    ON feature_flags (tenant_id, project_id, created_at DESC, id DESC)
    WHERE lifecycle_status = 'ACTIVE';
CREATE INDEX ix_flag_revisions_history
    ON flag_revisions (tenant_id, project_id, feature_flag_id, revision_number DESC);
CREATE INDEX ix_environment_flag_states_compile
    ON environment_flag_states (tenant_id, project_id, environment_id, feature_flag_id);
CREATE INDEX ix_configuration_snapshots_history
    ON configuration_snapshots (tenant_id, project_id, environment_id, snapshot_version DESC)
    INCLUDE (id, schema_version, checksum, generated_at);
CREATE INDEX ix_client_applications_environment
    ON client_applications (tenant_id, project_id, environment_id, id)
    WHERE lifecycle_status = 'ACTIVE';
CREATE INDEX ix_service_credentials_application
    ON service_credentials (tenant_id, project_id, client_application_id);
CREATE INDEX ix_audit_events_tenant_time
    ON audit_events (tenant_id, created_at DESC, id DESC);
CREATE INDEX ix_audit_events_resource_time
    ON audit_events (tenant_id, resource_type, resource_id, created_at DESC, id DESC);
CREATE INDEX ix_audit_events_correlation
    ON audit_events (tenant_id, correlation_id);
CREATE INDEX ix_outbox_events_pending
    ON outbox_events (next_attempt_at, created_at, id)
    WHERE published_at IS NULL;
CREATE INDEX ix_outbox_events_snapshot
    ON outbox_events (tenant_id, snapshot_id);

CREATE FUNCTION reject_published_revision_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_state = 'PUBLISHED' THEN
        RAISE EXCEPTION 'published revision % is immutable', OLD.id USING ERRCODE = '23514';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_flag_revisions_immutable
BEFORE UPDATE OR DELETE ON flag_revisions
FOR EACH ROW EXECUTE FUNCTION reject_published_revision_change();

CREATE FUNCTION reject_published_revision_child_change() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    parent_revision_id uuid;
BEGIN
    parent_revision_id := CASE
        WHEN TG_TABLE_NAME IN ('flag_variants', 'targeting_rules', 'rollout_allocations') THEN
            COALESCE(to_jsonb(NEW) ->> 'revision_id', to_jsonb(OLD) ->> 'revision_id')::uuid
        WHEN TG_TABLE_NAME = 'rule_conditions' THEN (
            SELECT revision_id
            FROM targeting_rules
            WHERE id = COALESCE(to_jsonb(NEW) ->> 'rule_id', to_jsonb(OLD) ->> 'rule_id')::uuid
        )
    END;

    IF EXISTS (SELECT 1 FROM flag_revisions WHERE id = parent_revision_id AND lifecycle_state = 'PUBLISHED') THEN
        RAISE EXCEPTION 'children of published revision % are immutable', parent_revision_id USING ERRCODE = '23514';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_flag_variants_published_parent
BEFORE INSERT OR UPDATE OR DELETE ON flag_variants
FOR EACH ROW EXECUTE FUNCTION reject_published_revision_child_change();
CREATE TRIGGER trg_targeting_rules_published_parent
BEFORE INSERT OR UPDATE OR DELETE ON targeting_rules
FOR EACH ROW EXECUTE FUNCTION reject_published_revision_child_change();
CREATE TRIGGER trg_rule_conditions_published_parent
BEFORE INSERT OR UPDATE OR DELETE ON rule_conditions
FOR EACH ROW EXECUTE FUNCTION reject_published_revision_child_change();
CREATE TRIGGER trg_rollout_allocations_published_parent
BEFORE INSERT OR UPDATE OR DELETE ON rollout_allocations
FOR EACH ROW EXECUTE FUNCTION reject_published_revision_child_change();

CREATE FUNCTION assert_targeting_rule_state(target_rule_id uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    target_result_type varchar(16);
    condition_count integer;
    allocation_count integer;
    allocation_total integer;
BEGIN
    SELECT result_type INTO target_result_type
    FROM targeting_rules
    WHERE id = target_rule_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*) INTO condition_count
    FROM rule_conditions
    WHERE rule_id = target_rule_id;
    IF condition_count = 0 THEN
        RAISE EXCEPTION 'targeting rule % must have at least one condition', target_rule_id USING ERRCODE = '23514';
    END IF;

    SELECT count(*), COALESCE(SUM(basis_points), 0)
    INTO allocation_count, allocation_total
    FROM rollout_allocations
    WHERE rule_id = target_rule_id;

    IF target_result_type = 'VARIANT' AND allocation_count <> 0 THEN
        RAISE EXCEPTION 'variant rule % must not have rollout allocations', target_rule_id USING ERRCODE = '23514';
    ELSIF target_result_type = 'ROLLOUT' THEN
        IF allocation_count = 0 OR allocation_total <> 10000 THEN
            RAISE EXCEPTION 'rollout rule % must have allocations totaling 10000, got % across % rows',
                target_rule_id, allocation_total, allocation_count USING ERRCODE = '23514';
        END IF;
    END IF;
END;
$$;

CREATE FUNCTION validate_targeting_rule_state() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    previous_rule_id uuid;
    current_rule_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'targeting_rules' THEN
        previous_rule_id := CASE WHEN TG_OP IN ('UPDATE', 'DELETE') THEN (to_jsonb(OLD) ->> 'id')::uuid END;
        current_rule_id := CASE WHEN TG_OP IN ('INSERT', 'UPDATE') THEN (to_jsonb(NEW) ->> 'id')::uuid END;
    ELSE
        previous_rule_id := CASE WHEN TG_OP IN ('UPDATE', 'DELETE') THEN (to_jsonb(OLD) ->> 'rule_id')::uuid END;
        current_rule_id := CASE WHEN TG_OP IN ('INSERT', 'UPDATE') THEN (to_jsonb(NEW) ->> 'rule_id')::uuid END;
    END IF;

    IF previous_rule_id IS NOT NULL AND previous_rule_id IS DISTINCT FROM current_rule_id THEN
        PERFORM assert_targeting_rule_state(previous_rule_id);
    END IF;
    IF current_rule_id IS NOT NULL THEN
        PERFORM assert_targeting_rule_state(current_rule_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_targeting_rules_final_state
AFTER INSERT OR UPDATE ON targeting_rules
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_targeting_rule_state();

CREATE CONSTRAINT TRIGGER trg_rule_conditions_final_state
AFTER INSERT OR UPDATE OR DELETE ON rule_conditions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_targeting_rule_state();

CREATE CONSTRAINT TRIGGER trg_rollout_allocations_final_state
AFTER INSERT OR UPDATE OR DELETE ON rollout_allocations
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_targeting_rule_state();

CREATE FUNCTION reject_immutable_row_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_configuration_snapshots_immutable
BEFORE UPDATE OR DELETE ON configuration_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_immutable_row_change();
CREATE TRIGGER trg_snapshot_entries_immutable
BEFORE UPDATE OR DELETE ON snapshot_entries
FOR EACH ROW EXECUTE FUNCTION reject_immutable_row_change();
CREATE TRIGGER trg_audit_events_immutable
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_row_change();

CREATE FUNCTION restrict_outbox_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.tenant_id <> OLD.tenant_id
       OR NEW.snapshot_id <> OLD.snapshot_id
       OR NEW.aggregate_type <> OLD.aggregate_type
       OR NEW.aggregate_id <> OLD.aggregate_id
       OR NEW.event_type <> OLD.event_type
       OR NEW.event_version <> OLD.event_version
       OR NEW.payload <> OLD.payload
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'outbox identity and payload are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_outbox_events_restricted_update
BEFORE UPDATE ON outbox_events
FOR EACH ROW EXECUTE FUNCTION restrict_outbox_update();
