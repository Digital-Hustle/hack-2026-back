# Cashflow backend

Планируемая архитектура: Auth/Profile, Gateway, PreProcessor, Orchestrator и Forecast.
Архитектурные инструкции находятся в [backend-skills](backend-skills/docs/BACKEND_GUIDE.md).

Запускаемый **Auth/Profile v0.1.0** на Java 21 / Spring Boot:

- [Инструкция для фронта и Gateway](docs/AUTH_PROFILE_API.md).
- [OpenAPI 3.1](docs/openapi/auth-profile.yaml) — публичный HTTP-контракт.
- [Java API](services/auth-profile/src/main/java/ru/cashflow/authprofile/api) — интерфейсы контроллеров, DTO records и тип principal.
- [HTTP-сценарий](http/auth-profile.http).

Реализованы email OTP через SMTP, автоматическое создание пользователя/профиля,
JWT RS256, Secure/HttpOnly cookies, CSRF/Origin, ротация хешированного refresh,
список/отзыв сессий, logout и профиль с optimistic locking.
Сервис использует отдельную PostgreSQL БД, Liquibase и типизированный jOOQ.
Архитектура следует `AGENTS.md` из certis-api: feature-first CQRS, отдельная orchestration,
публичные межфичевые API, MapStruct на HTTP-границе и gateway для SMTP.
Вход соответствует контракту этой ветки (email-код).

[Запуск и подключение Gateway](docs/AUTH_PROFILE_RUNTIME.md).
[Gateway: маршруты, конфигурация и проверки](services/gateway-server/README.md).
Auth/Profile принимает доверенный контекст от Gateway,
его backend-порт не должен быть доступен из браузера.

Java 21, Gradle Kotlin DSL. Проверка и сборка:

```powershell
.\gradlew.bat test build
```

```bash
bash gradlew test build
bash gradlew :services:auth-profile:bootRun
```

OpenAPI включается в JAR по пути `openapi/auth-profile.yaml`. Отчёт тестов:
`services/auth-profile/build/reports/tests/test/index.html`.
Для запуска заполните [.env.auth-profile.example](.env.auth-profile.example).
Перед компиляцией `generateJooq` применяет Liquibase к временному PostgreSQL и генерирует
таблицы и records. Доступ к рабочей БД и локальные секреты для сборки не нужны.
Интеграционные тесты используют настоящий embedded PostgreSQL без Docker и мок `CodeDeliveryGateway`;
используется loopback TCP. Запуск от root требует поддержки user namespaces.
Для внешней тестовой БД задайте `TEST_DATABASE_URL`
(отдельная пустая БД, логин/пароль `postgres`).
