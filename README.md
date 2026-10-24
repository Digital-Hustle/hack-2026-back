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

## CI

[Backend CI](.github/workflows/ci-build.yml) запускается на всех pull request,
при push в `main`, `develop`, `feature/**` и вручную через Actions.
Общий [workflow](.github/workflows/common-jobs.yml) определяет изменённые сервисы
и запускает их сборку отдельными параллельными jobs (`matrix`, `fail-fast: false`).
Каждый job выполняет из корня репозитория
`bash gradlew :services:<service>:test :services:<service>:build --continue --no-daemon`:
тесты остаются обязательными, а Checkstyle запускается там, где подключён в Gradle.
`--continue` позволяет собрать результаты независимых проверок после ошибки,
при этом неуспешные тесты и Checkstyle по-прежнему завершают сборку с ошибкой.
Auth/Profile и Orchestrator используют embedded PostgreSQL; отдельная БД для CI не нужна.
Тестовые результаты и отчёты Gradle каждого сервиса сохраняются отдельным артефактом,
включая отчёты Checkstyle и JaCoCo, если сервис их генерирует.

JUnit публикуется в GitHub Checks как `JUnit (<service>)` и в Summary соответствующего job:
видны результаты тестов, время выполнения и аннотации ошибок.
Checkstyle публикуется через reviewdog отдельными проверками
`Checkstyle (<service> / main)` и `Checkstyle (<service> / test)`, когда XML-отчёты существуют.
Отчёты публикуются и после неуспешной сборки; отсутствие отчётов после ошибки компиляции
не вызывает дополнительную ошибку публикации.
Для PR из форков и запусков Dependabot используются аннотации без записи в Checks API;
JUnit Summary и артефакты остаются доступны. Публикация не создаёт комментарии в PR.

При PR сравнивается ветка с merge base целевой ветки; при push — весь диапазон
от предыдущего SHA до текущего. Изменения общих файлов Gradle, Wrapper или `.github/`
проверяют все сервисы. Изменение `docs/openapi/auth-profile.yaml` проверяет Auth/Profile,
поскольку контракт включается в его JAR. Остальная документация вне `services/`
не запускает сборку сервисов. Первый push новой ветки, недоступный предыдущий SHA
и ручной запуск проверяют все существующие сервисы.
Итоговый job `verify` проходит только после успешной сборки выбранных сервисов
либо корректного пропуска при отсутствии затронутых сервисов.

Проверка определения сервисов локально:

```bash
python3 -m unittest discover -s .github/scripts/tests -v
```
