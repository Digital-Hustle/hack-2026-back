---
name: backend-spring
description: Реализация Java/Spring микросервисов cashflow — feature-first, контроллеры и DTO, MapStruct, БД, миграции, HTTP и тесты. Используй для Java-бэкенда вместе с cashflow-architecture.
---

# Backend Spring

Архитектура и владельцы данных — в соседнем `cashflow-architecture/SKILL.md`. Текущий состав: auth-profile, gateway, preprocessor, orchestrator, forecast. БД только у auth-profile и orchestrator. Не переносить дополнительные сервисы или JWT-проверку в каждый сервис.

## Стек и структура

Java 21/Spring Boot, Gradle Kotlin DSL, version catalog и при необходимости convention plugins. Точные версии брать из текущей сборки. PostgreSQL/Liquibase для сервисов с БД; jOOQ или JPA по принятому решению. MapStruct и DTO records. Lombok допустим для constructor injection; без @Data на JPA entity и @SneakyThrows.

Пакет `ru.<project>.<service>`; внутри feature-first:

```text
api/controller/             интерфейс маршрутов/валидации/доступа
api/controller/impl/        тонкая реализация
api/dto/request/            *Rq records
api/dto/response/           *Rs records
api/mapper/                 MapStruct
features/<feature>/application/   use cases
features/<feature>/command/       запись
features/<feature>/query/         чтение
features/<feature>/model/         доменные типы
features/<feature>/repository/    только сервисам с БД
features/<feature>/gateway/       HTTP-адаптеры
config/                          validated properties
```

Зависимости api → application → command/query → repository. Между фичами — выделенные интерфейсы. В stateless вычислителе не создавать бессмысленную БД/репозиторий. Application задаёт транзакцию локальной БД, внешние HTTP-вызовы выполняются вне неё.

## Код и границы

- CurrentUser создаёт фильтр доверенных заголовков, не JWT-декодер. CompanyScope и актуальные права организации проверяет Orchestrator. Подробности в docs/GATEWAY_AUTH.md.
- Деньги BigDecimal, scale 2/HALF_EVEN на границах; numeric(19,2); JSON строка. Float/double не для учёта.
- Время через инжектируемый Clock: LocalDate для бизнес-дня, Instant для момента.
- Контроллеры валидируют/маппят и вызывают application; не содержат бизнес-расчёт. MapStruct с unmappedTargetPolicy=ERROR; entities наружу не отдавать.
- HTTP через интерфейс gateway + реализацию RestClient, таймауты/circuit breaker по конфигурации. Без JWT/TokenRelay; передавать только разрешённый контекст. Повторы мутаций только с идемпотентностью.
- Ошибки ProblemDetail/ErrorCode, включая 401/403. Логи без токенов и финансовых DTO.
- Миграции Liquibase formatted SQL, rollback где применим; применённые changesets не редактировать.
- Отдельные OpenAPI-файлы и генераторы не обязательны. Использовать обычные DTO и тесты HTTP-поведения.

## Тестирование

Unit — нормализация/категории/регулярности/остаток и правила доступа. Интеграционные с PostgreSQL Testcontainers в сервисах с БД. HTTP-заглушки для межсервисных вызовов. В gateway тестовые JWT; в бизнес-сервисах тестовый доверенный контекст. Проверять 401/403/404 и отдельные сетевые ограничения. ArchUnit фиксирует границы пакетов и запрет нежелательных зависимостей.

## Справка

- [references/service-layout.md](references/service-layout.md) — иллюстрация фичи Orchestrator.
- [references/data-access.md](references/data-access.md) — БД и HTTP.
- [references/security.md](references/security.md) — ссылка на актуальную схему безопасности.
- [references/audit.md](references/audit.md) — эталоны и известные ошибки.
