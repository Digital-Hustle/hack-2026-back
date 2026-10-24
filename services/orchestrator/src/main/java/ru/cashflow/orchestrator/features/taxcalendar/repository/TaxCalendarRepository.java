package ru.cashflow.orchestrator.features.taxcalendar.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.taxcalendar.model.TaxDeadline;

@Repository
public class TaxCalendarRepository {

    private final JdbcClient jdbc;

    public TaxCalendarRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TaxDeadline> list(int year) {
        return jdbc.sql("select due_date, title, tax_code, audience, source_url from tax_calendar "
                        + "where year = :year order by due_date, tax_code, title")
                .param("year", year)
                .query((rs, row) -> new TaxDeadline(rs.getObject("due_date", LocalDate.class),
                        rs.getString("title"), rs.getString("tax_code"), rs.getString("audience"),
                        rs.getString("source_url")))
                .list();
    }
}
