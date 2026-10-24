package ru.cashflow.orchestrator.features.companies.model;

/** Contract permissions derived solely from the membership role. */
public record Permissions(boolean canEditCompany, boolean canManageMembers, boolean canImport,
                          boolean canEditPayments, boolean canManagePlans, boolean canRunForecast,
                          boolean canRunBacktest) {

    public static Permissions of(Role role) {
        boolean owner = role == Role.OWNER;
        boolean accountant = role == Role.ACCOUNTANT;
        return new Permissions(owner, owner, owner || accountant, owner || accountant,
                owner || accountant, owner || accountant, owner || accountant);
    }
}