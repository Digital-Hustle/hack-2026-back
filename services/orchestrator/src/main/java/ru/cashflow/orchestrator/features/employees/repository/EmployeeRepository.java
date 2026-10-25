package ru.cashflow.orchestrator.features.employees.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.employees.model.Employee;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;

@Repository
public class EmployeeRepository {
    private final JdbcClient jdbc;

    public EmployeeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Employee> find(CompanyScope scope, UUID id) {
        return jdbc.sql("select * from employees where company_id = :company and id = :id")
                .param("company", scope.companyId()).param("id", id).query(EmployeeRepository::employee).optional();
    }

    public List<Employee> list(CompanyScope scope, boolean includeArchived, PageQuery page) {
        return jdbc.sql("select * from employees where company_id = :company and (:archived or not archived) "
                        + "order by full_name, id limit :limit offset :offset")
                .param("company", scope.companyId()).param("archived", includeArchived)
                .param("limit", page.size()).param("offset", page.offset()).query(EmployeeRepository::employee).list();
    }

    public long count(CompanyScope scope, boolean includeArchived) {
        return jdbc.sql("select count(*) from employees where company_id = :company and (:archived or not archived)")
                .param("company", scope.companyId()).param("archived", includeArchived).query(Long.class).single();
    }

    public Optional<Employee> byPattern(CompanyScope scope, UUID patternId) {
        return jdbc.sql("select * from employees where company_id = :company and recurring_pattern_id = :pattern")
                .param("company", scope.companyId()).param("pattern", patternId)
                .query(EmployeeRepository::employee).optional();
    }

    public void insert(Employee e) {
        jdbc.sql("insert into employees (id, company_id, full_name, position, note, inn, archived, "
                        + "recurring_pattern_id, version, created_at, updated_at) values (:id, :company, :name, "
                        + ":position, :note, :inn, false, null, 0, :created, :updated)")
                .param("id", e.id()).param("company", e.companyId()).param("name", e.fullName())
                .param("position", e.position()).param("note", e.note()).param("inn", e.inn())
                .param("created", Timestamp.from(e.createdAt())).param("updated", Timestamp.from(e.updatedAt())).update();
    }

    public void update(Employee e) {
        int rows = jdbc.sql("update employees set full_name = :name, position = :position, note = :note, "
                        + "inn = :inn, archived = :archived, recurring_pattern_id = :pattern, version = :version, "
                        + "updated_at = :now where company_id = :company and id = :id and version = :previous")
                .param("name", e.fullName()).param("position", e.position()).param("note", e.note())
                .param("inn", e.inn()).param("archived", e.archived()).param("pattern", e.recurringPatternId())
                .param("version", e.version())
                .param("now", Timestamp.from(e.updatedAt())).param("company", e.companyId()).param("id", e.id())
                .param("previous", e.version() - 1).update();
        if (rows != 1) {
            throw new IllegalStateException("employee changed concurrently");
        }
    }

    private static Employee employee(ResultSet rs, int row) throws SQLException {
        return new Employee(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("full_name"), rs.getString("position"), rs.getString("note"), rs.getString("inn"),
                rs.getBoolean("archived"), rs.getObject("recurring_pattern_id", UUID.class), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
