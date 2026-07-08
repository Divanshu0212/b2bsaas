CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE organizations (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL,
    plan       TEXT NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL REFERENCES organizations(id),
    email         CITEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, email)
);

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    org_id     UUID NOT NULL REFERENCES organizations(id),
    user_id    UUID NOT NULL REFERENCES users(id),
    family_id  UUID NOT NULL,
    token_hash TEXT NOT NULL,
    parent_id  UUID,
    used       BOOLEAN NOT NULL DEFAULT false,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_family ON refresh_tokens(family_id);

CREATE TABLE accounts (
    id             UUID PRIMARY KEY,
    org_id         UUID NOT NULL REFERENCES organizations(id),
    name           TEXT NOT NULL,
    industry       TEXT,
    employee_count INT,
    website        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_accounts_org ON accounts(org_id);

CREATE TABLE contacts (
    id         UUID PRIMARY KEY,
    org_id     UUID NOT NULL REFERENCES organizations(id),
    account_id UUID REFERENCES accounts(id),
    first_name TEXT,
    last_name  TEXT,
    email      CITEXT,
    phone      TEXT,
    title      TEXT
);
CREATE INDEX idx_contacts_org ON contacts(org_id);

CREATE TABLE leads (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL REFERENCES organizations(id),
    contact_id    UUID REFERENCES contacts(id),
    account_id    UUID REFERENCES accounts(id),
    source        TEXT,
    status        TEXT NOT NULL,
    raw_notes     TEXT,
    current_score NUMERIC(5,4),
    owner_id      UUID REFERENCES users(id),
    version       INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_leads_org_status ON leads(org_id, status);
CREATE INDEX idx_leads_org_owner ON leads(org_id, owner_id);

CREATE TABLE deal_stages (
    id       UUID PRIMARY KEY,
    org_id   UUID NOT NULL REFERENCES organizations(id),
    name     TEXT NOT NULL,
    position INT NOT NULL,
    is_won   BOOLEAN NOT NULL DEFAULT false,
    is_lost  BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (org_id, position)
);

CREATE TABLE deals (
    id                  UUID PRIMARY KEY,
    org_id              UUID NOT NULL REFERENCES organizations(id),
    lead_id             UUID REFERENCES leads(id),
    account_id          UUID REFERENCES accounts(id),
    stage_id            UUID NOT NULL REFERENCES deal_stages(id),
    owner_id            UUID REFERENCES users(id),
    amount              NUMERIC(14,2),
    currency            CHAR(3),
    expected_close_date DATE,
    entered_stage_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_deals_org_stage ON deals(org_id, stage_id);

CREATE TABLE deal_stage_history (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL REFERENCES organizations(id),
    deal_id       UUID NOT NULL REFERENCES deals(id),
    from_stage_id UUID,
    to_stage_id   UUID NOT NULL,
    changed_by    UUID,
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_deal_history_deal ON deal_stage_history(deal_id);

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    org_id      UUID NOT NULL,
    actor_id    UUID,
    action      TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   UUID,
    diff        JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_org ON audit_log(org_id, created_at DESC);
