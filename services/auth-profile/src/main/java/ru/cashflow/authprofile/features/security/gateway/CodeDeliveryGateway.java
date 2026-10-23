package ru.cashflow.authprofile.features.security.gateway;

public interface CodeDeliveryGateway {

    void send(String email, String code);
}
