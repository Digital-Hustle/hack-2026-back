package ru.cashflow.orchestrator.features.companies.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.Account;
import ru.cashflow.orchestrator.features.companies.model.Company;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.model.LegalForm;
import ru.cashflow.orchestrator.features.companies.model.Membership;
import ru.cashflow.orchestrator.features.companies.model.Role;

@Repository
public class CompanyRepository {

    private static final String COMPANY_COLUMNS = "id, name, legal_form, inn, kpp, timezone, owner_user_id, archived, "
            + "data_version, version, created_at, updated_at";
    private static final String ACCOUNT_COLUMNS = "id, company_id, name, bank_name, account_number, external_company_id, "
            + "currency, archived, version, created_at, updated_at";

    private final JdbcClient jdbc;

    public CompanyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertCompany(Company company) {
        jdbc.sql("insert into companies (" + COMPANY_COLUMNS + ") values (:id, :name, :legalForm, :inn, :kpp, "
                        + ":timezone, :owner, false, 0, 0, :created, :created)")
                .param("id", company.id()).param("name", company.name())
                .param("legalForm", company.legalForm() == null ? null : company.legalForm().name())
                .param("inn", company.inn()).param("kpp", company.kpp()).param("timezone", company.timezone())
                .param("owner", company.ownerUserId()).param("created", Timestamp.from(company.createdAt()))
                .update();
    }

    public void insertMembership(Membership membership) {
        jdbc.sql("insert into memberships (company_id, user_id, role, version, joined_at) "
                        + "values (:company, :user, :role, 0, :joined)")
                .param("company", membership.companyId()).param("user", membership.userId())
                .param("role", membership.role().name()).param("joined", Timestamp.from(membership.joinedAt()))
                .update();
    }

    public Optional<Membership> findMembership(UUID companyId, UUID userId) {
        return jdbc.sql("select m.company_id, m.user_id, m.role, m.version, m.joined_at from memberships m "
                        + "join companies c on c.id = m.company_id "
                        + "where m.company_id = :company and m.user_id = :user and not c.archived")
                .param("company", companyId).param("user", userId)
                .query(CompanyRepository::membership).optional();
    }

    public Optional<Company> findCompany(CompanyScope scope) {
        return jdbc.sql("select " + COMPANY_COLUMNS + " from companies where id = :id and not archived")
                .param("id", scope.companyId()).query(CompanyRepository::company).optional();
    }

    public Optional<Company> lockCompany(CompanyScope scope) {
        return jdbc.sql("select " + COMPANY_COLUMNS + " from companies where id = :id and not archived for update")
                .param("id", scope.companyId()).query(CompanyRepository::company).optional();
    }

    public List<Company> listCompaniesForUser(UUID userId) {
        return jdbc.sql("select c.id, c.name, c.legal_form, c.inn, c.kpp, c.timezone, c.owner_user_id, c.archived, "
                        + "c.data_version, c.version, c.created_at, c.updated_at from companies c "
                        + "join memberships m on m.company_id = c.id "
                        + "where m.user_id = :user and not c.archived order by c.created_at, c.id")
                .param("user", userId).query(CompanyRepository::company).list();
    }

    public boolean updateCompany(CompanyScope scope, long expectedVersion, String name, LegalForm legalForm,
                                 String inn, String kpp, String timezone, Instant now) {
        return jdbc.sql("update companies set name = :name, legal_form = :legalForm, inn = :inn, kpp = :kpp, "
                        + "timezone = :timezone, version = version + 1, updated_at = :now "
                        + "where id = :id and version = :version and not archived")
                .param("name", name).param("legalForm", legalForm == null ? null : legalForm.name())
                .param("inn", inn).param("kpp", kpp).param("timezone", timezone)
                .param("now", Timestamp.from(now)).param("id", scope.companyId()).param("version", expectedVersion)
                .update() == 1;
    }

    /** Called inside the same transaction as any change of financial inputs (handoff §3). */
    public long incrementDataVersion(CompanyScope scope, Instant now) {
        return jdbc.sql("update companies set data_version = data_version + 1, updated_at = :now "
                        + "where id = :id returning data_version")
                .param("now", Timestamp.from(now)).param("id", scope.companyId())
                .query(Long.class).single();
    }

    public List<Membership> listMembers(CompanyScope scope) {
        return jdbc.sql("select company_id, user_id, role, version, joined_at from memberships "
                        + "where company_id = :company order by joined_at, user_id")
                .param("company", scope.companyId()).query(CompanyRepository::membership).list();
    }

    public boolean updateMemberRole(CompanyScope scope, UUID userId, Role role, long expectedVersion) {
        return jdbc.sql("update memberships set role = :role, version = version + 1 "
                        + "where company_id = :company and user_id = :user and version = :version and role <> 'OWNER'")
                .param("role", role.name()).param("company", scope.companyId()).param("user", userId)
                .param("version", expectedVersion).update() == 1;
    }

    public boolean deleteMember(CompanyScope scope, UUID userId) {
        return jdbc.sql("delete from memberships where company_id = :company and user_id = :user and role <> 'OWNER'")
                .param("company", scope.companyId()).param("user", userId).update() == 1;
    }

    public void insertAccount(Account account) {
        jdbc.sql("insert into accounts (" + ACCOUNT_COLUMNS + ") values (:id, :company, :name, :bank, :number, "
                        + ":external, 'RUB', false, 0, :created, :created)")
                .param("id", account.id()).param("company", account.companyId()).param("name", account.name())
                .param("bank", account.bankName()).param("number", account.accountNumber())
                .param("external", account.externalCompanyId()).param("created", Timestamp.from(account.createdAt()))
                .update();
    }

    public Optional<Account> findAccount(CompanyScope scope, UUID accountId) {
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from accounts "
                        + "where id = :id and company_id = :company and not archived")
                .param("id", accountId).param("company", scope.companyId())
                .query(CompanyRepository::account).optional();
    }

    public List<Account> listAccounts(CompanyScope scope) {
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from accounts where company_id = :company and not archived "
                        + "order by created_at, id")
                .param("company", scope.companyId()).query(CompanyRepository::account).list();
    }

    public boolean updateAccount(CompanyScope scope, UUID accountId, long expectedVersion, String name,
                                 String bankName, String accountNumber, String externalCompanyId, Instant now) {
        return jdbc.sql("update accounts set name = :name, bank_name = :bank, account_number = :number, "
                        + "external_company_id = :external, version = version + 1, updated_at = :now "
                        + "where id = :id and company_id = :company and version = :version and not archived")
                .param("name", name).param("bank", bankName).param("number", accountNumber)
                .param("external", externalCompanyId).param("now", Timestamp.from(now)).param("id", accountId)
                .param("company", scope.companyId()).param("version", expectedVersion).update() == 1;
    }

    private static Company company(ResultSet rs, int row) throws SQLException {
        String legalForm = rs.getString("legal_form");
        return new Company(rs.getObject("id", UUID.class), rs.getString("name"),
                legalForm == null ? null : LegalForm.valueOf(legalForm), rs.getString("inn"), rs.getString("kpp"),
                rs.getString("timezone"), rs.getObject("owner_user_id", UUID.class), rs.getBoolean("archived"),
                rs.getLong("data_version"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Membership membership(ResultSet rs, int row) throws SQLException {
        return new Membership(rs.getObject("company_id", UUID.class), rs.getObject("user_id", UUID.class),
                Role.valueOf(rs.getString("role")), rs.getLong("version"), rs.getTimestamp("joined_at").toInstant());
    }

    private static Account account(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), rs.getString("bank_name"), rs.getString("account_number"),
                rs.getString("external_company_id"), rs.getString("currency"), rs.getBoolean("archived"),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
