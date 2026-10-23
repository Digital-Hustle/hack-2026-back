package ru.cashflow.authprofile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import ru.cashflow.authprofile.config.properties.AuthProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthProfileApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthProfileApplication.class, args);
    }
}
