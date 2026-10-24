package ru.cashflow.orchestrator.features.settings.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;

@Service
public class SettingsService {

    private final JdbcClient jdbc;
    private final CompanyRepository companies;
    private final CompanyAccess access;
    private final ObjectMapper json;
    private final Clock clock;

    public SettingsService(JdbcClient jdbc, CompanyRepository companies, CompanyAccess access, ObjectMapper json,
                           Clock clock) {
        this.jdbc = jdbc;
        this.companies = companies;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    public Versioned get(UUID userId, UUID companyId, UUID accountId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return current(scope, accountId);
    }

    /** Settings change what Forecast computes, so they are revisioned and increment dataVersion (§3). */
    @Transactional
    public Versioned update(UUID userId, UUID companyId, UUID accountId, long version, ForecastSettings settings) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        if (!settings.valid()) {
            throw ApiException.unprocessable("INVALID_SETTINGS",
                    "windowWeeks 8|10|12, horizonDays 30, buffer >= 0, scenarios 100..5000, origins 12..100, step 7");
        }
        ForecastSettings normalized = new ForecastSettings(settings.windowWeeks(), 30,
                settings.bufferAmount().setScale(2), settings.scenarioCount(), settings.minCalibrationOrigins(), 7,
                ForecastSettings.ALGORITHM);
        Versioned current = current(scope, accountId);
        if (current.version() != version) {
            throw ApiException.conflict("VERSION_CONFLICT", "Настройки изменены другим запросом; обновите данные");
        }
        Instant now = clock.instant();
        String payload = write(normalized);
        long next = version + 1;
        int rows = version == 0 && current.stored() == false
                ? jdbc.sql("insert into account_settings (company_id, account_id, settings_json, version, updated_at) "
                        + "values (:company, :account, cast(:payload as jsonb), :next, :now) on conflict do nothing")
                .param("company", scope.companyId()).param("account", accountId).param("payload", payload)
                .param("next", next).param("now", Timestamp.from(now)).update()
                : jdbc.sql("update account_settings set settings_json = cast(:payload as jsonb), version = :next, "
                        + "updated_at = :now where company_id = :company and account_id = :account and version = :v")
                .param("payload", payload).param("next", next).param("now", Timestamp.from(now))
                .param("company", scope.companyId()).param("account", accountId).param("v", version).update();
        if (rows != 1) {
            throw ApiException.conflict("VERSION_CONFLICT", "Настройки изменены другим запросом; обновите данные");
        }
        jdbc.sql("insert into settings_revisions (company_id, account_id, known_at, version, payload, actor_id) "
                        + "values (:company, :account, :now, :next, cast(:payload as jsonb), :actor)")
                .param("company", scope.companyId()).param("account", accountId).param("now", Timestamp.from(now))
                .param("next", next).param("payload", payload).param("actor", userId).update();
        companies.incrementDataVersion(scope, now);
        return new Versioned(normalized, next, true);
    }

    /** Settings that were effective strictly before the cutoff; defaults if none were set yet. */
    public ForecastSettings knownBefore(CompanyScope scope, UUID accountId, Instant knownBefore) {
        Optional<String> payload = jdbc.sql("select payload::text from settings_revisions where company_id = :company "
                        + "and account_id = :account and known_at < :known order by known_at desc, version desc limit 1")
                .param("company", scope.companyId()).param("account", accountId)
                .param("known", Timestamp.from(knownBefore)).query(String.class).optional();
        return payload.map(this::read).orElseGet(ForecastSettings::defaults);
    }

    public Versioned current(CompanyScope scope, UUID accountId) {
        return jdbc.sql("select settings_json::text as payload, version from account_settings "
                        + "where company_id = :company and account_id = :account")
                .param("company", scope.companyId()).param("account", accountId)
                .query((rs, row) -> new Versioned(read(rs.getString("payload")), rs.getLong("version"), true))
                .optional().orElseGet(() -> new Versioned(ForecastSettings.defaults(), 0, false));
    }

    private String write(ForecastSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("windowWeeks", s.windowWeeks());
        m.put("horizonDays", s.horizonDays());
        m.put("bufferAmount", s.bufferAmount().toPlainString());
        m.put("scenarioCount", s.scenarioCount());
        m.put("minCalibrationOrigins", s.minCalibrationOrigins());
        m.put("calibrationStepDays", s.calibrationStepDays());
        m.put("algorithm", s.algorithm());
        try {
            return json.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ForecastSettings read(String payload) {
        try {
            JsonNode n = json.readTree(payload);
            return new ForecastSettings(n.path("windowWeeks").asInt(), n.path("horizonDays").asInt(),
                    new BigDecimal(n.path("bufferAmount").asText()).setScale(2), n.path("scenarioCount").asInt(),
                    n.path("minCalibrationOrigins").asInt(), n.path("calibrationStepDays").asInt(),
                    n.path("algorithm").asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Versioned(ForecastSettings settings, long version, boolean stored) {
    }
}
