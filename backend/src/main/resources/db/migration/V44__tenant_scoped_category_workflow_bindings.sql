-- V44: keep one shared Form binding while allowing a category workflow override per Tenant.
-- Existing CategoryVersion rows remain the default source of truth and are backfilled below.

CREATE TABLE tenants (
    id           UUID PRIMARY KEY,
    tenant_key   VARCHAR(128) NOT NULL UNIQUE,
    name         VARCHAR(200) NOT NULL,
    status       VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by   UUID NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_tenants_key CHECK (tenant_key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_tenants_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_tenants_lock CHECK (lock_version >= 0)
);

CREATE INDEX ix_tenants_status ON tenants(status);

CREATE TABLE tenant_memberships (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL,
    role         VARCHAR(32) NOT NULL,
    status       VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_memberships_user UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('TENANT_ADMIN', 'TENANT_MEMBER')),
    CONSTRAINT ck_tenant_memberships_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_tenant_memberships_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_tenant_memberships_lock CHECK (lock_version >= 0)
);

CREATE INDEX ix_tenant_memberships_user_status ON tenant_memberships(user_id, status);

CREATE TABLE ticket_category_workflow_bindings (
    id                   UUID PRIMARY KEY,
    ticket_category_id   UUID NOT NULL REFERENCES ticket_categories(id) ON DELETE CASCADE,
    tenant_id            UUID REFERENCES tenants(id) ON DELETE CASCADE,
    category_version_id  UUID NOT NULL,
    workflow_version_id  UUID NOT NULL REFERENCES workflow_versions(id) ON DELETE RESTRICT,
    created_by           UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    lock_version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_category_binding_version
        FOREIGN KEY (category_version_id, ticket_category_id)
        REFERENCES ticket_category_versions(id, ticket_category_id) ON DELETE RESTRICT,
    CONSTRAINT ck_category_binding_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_category_binding_lock CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX uq_category_workflow_binding_default
    ON ticket_category_workflow_bindings(ticket_category_id)
    WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX uq_category_workflow_binding_tenant
    ON ticket_category_workflow_bindings(ticket_category_id, tenant_id)
    WHERE tenant_id IS NOT NULL;
CREATE INDEX ix_category_workflow_binding_tenant
    ON ticket_category_workflow_bindings(tenant_id, ticket_category_id);

-- Existing categories continue to behave exactly as before after the upgrade.
INSERT INTO ticket_category_workflow_bindings(
    id, ticket_category_id, tenant_id, category_version_id, workflow_version_id,
    created_by, created_at, updated_at)
SELECT (md5('category-default-binding:' || tc.id::text))::uuid,
       tc.id, NULL, tcv.id, tcv.workflow_version_id,
       tcv.created_by, tcv.created_at, tcv.created_at
FROM ticket_categories tc
JOIN ticket_category_versions tcv ON tcv.id = tc.current_published_version_id
WHERE tcv.status = 'PUBLISHED'
ON CONFLICT DO NOTHING;
