package ru.cashflow.orchestrator.config;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import ru.cashflow.orchestrator.api.security.TrustedUserResolver;

@Configuration
public class OrchestratorConfiguration implements WebMvcConfigurer {

    private final TrustedUserResolver userResolver;

    public OrchestratorConfiguration(TrustedUserResolver userResolver) {
        this.userResolver = userResolver;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(userResolver);
    }
}
