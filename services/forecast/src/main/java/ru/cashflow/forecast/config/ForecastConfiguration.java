package ru.cashflow.forecast.config;

import java.time.Clock;
import java.time.ZoneId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ru.cashflow.forecast.api.json.ForecastJson;

@Configuration
@EnableConfigurationProperties(ForecastProperties.class)
public class ForecastConfiguration {
    @Bean
    public Clock forecastClock() {
        return Clock.system(ZoneId.of("Europe/Moscow"));
    }

    @Bean
    public ObjectMapper objectMapper() {
        return ForecastJson.mapper();
    }
}
