--liquibase formatted sql
--changeset cashflow:001-auth-profile
CREATE SCHEMA auth_profile;
CREATE TABLE auth_profile.users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0 AND version <= 9007199254740991),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (email = lower(email))
);
CREATE TABLE auth_profile.code_challenges (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX challenges_email_idx ON auth_profile.code_challenges(email);
CREATE INDEX challenges_expiry_idx ON auth_profile.code_challenges(expires_at);
CREATE TABLE auth_profile.sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth_profile.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX sessions_user_idx ON auth_profile.sessions(user_id);
CREATE TABLE auth_profile.refresh_credentials (
    token_hash VARCHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_profile.sessions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);
CREATE INDEX refresh_session_idx ON auth_profile.refresh_credentials(session_id);
CREATE TABLE auth_profile.rate_limits (
    bucket_key VARCHAR(64) PRIMARY KEY,
    window_end TIMESTAMPTZ NOT NULL,
    used INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
    next_allowed_at TIMESTAMPTZ NOT NULL
);
--rollback DROP SCHEMA auth_profile CASCADE;
