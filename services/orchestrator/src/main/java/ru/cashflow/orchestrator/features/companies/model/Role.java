package ru.cashflow.orchestrator.features.companies.model;

public enum Role {
    OWNER,
    ACCOUNTANT,
    VIEWER;

    public boolean canWriteFinance() {
        return this == OWNER || this == ACCOUNTANT;
    }

    public boolean canManageMembers() {
        return this == OWNER;
    }
}
