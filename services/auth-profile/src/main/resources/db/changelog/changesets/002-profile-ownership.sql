--liquibase formatted sql
--changeset cashflow:002-profile-ownership
CREATE TABLE auth_profile.profiles (
    user_id UUID PRIMARY KEY REFERENCES auth_profile.users(id) ON DELETE CASCADE,
    display_name VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0 AND version <= 9007199254740991),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
INSERT INTO auth_profile.profiles(user_id, display_name, version, created_at, updated_at)
SELECT id, display_name, version, created_at, updated_at FROM auth_profile.users;
-- Keep the original users columns for compatibility and rollback; new writes belong to profiles.
--rollback UPDATE auth_profile.users u SET display_name=p.display_name, version=p.version, updated_at=p.updated_at FROM auth_profile.profiles p WHERE p.user_id=u.id;
--rollback DROP TABLE auth_profile.profiles;
