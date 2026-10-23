# Запуск Auth/Profile

## Конфигурация

Скопируйте `.env.auth-profile.example` в `.env.auth-profile` и заполните БД, SMTP и origin фронта.
Замените `...` своими значениями; необязательные строки удалите, чтобы использовать defaults.
Для `bootRun` стандартный URL — `jdbc:postgresql://localhost:5432/auth_profile`,
пользователь — `auth_profile`, порт сервиса — `8081`. Compose задаёт URL и пользователя сам.
Секреты обязательны: сервис останавливается при пустом HMAC-ключе или неверной RSA-паре.
JWT подписывается RS256. Приватный ключ — base64 DER PKCS#8; публичный — base64 DER X.509.
Один и тот же HMAC-ключ используется всеми экземплярами сервиса и сохраняется между перезапусками.

Пример генерации ключей локально (OpenSSL; файлы ключей и env исключены из git):

```bash
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out auth-private.pem
openssl pkcs8 -topk8 -nocrypt -in auth-private.pem -outform DER | openssl base64 -A
openssl pkey -in auth-private.pem -pubout -outform DER | openssl base64 -A
openssl rand -base64 32
```

Первый base64 вставьте в `AUTH_JWT_PRIVATE_KEY`, второй — в `AUTH_JWT_PUBLIC_KEY`,
последний — в `AUTH_OTP_HMAC_SECRET`. Не публикуйте значения.

| Параметр | Назначение |
|---|---|
| `AUTH_DB_URL/USER/PASSWORD` | Отдельная PostgreSQL БД Auth/Profile |
| `AUTH_ALLOWED_ORIGINS` | Точные HTTPS origin фронта, через запятую, без завершающего слеша |
| `AUTH_MAIL_FROM`, `SMTP_*` | Реальный SMTP с таймаутами и STARTTLS; при сбое возвращается 503 |
| `AUTH_JWT_PRIVATE_KEY/PUBLIC_KEY/KEY_ID` | Согласованная RSA-пара и идентификатор ключа |
| `AUTH_JWT_ISSUER/AUDIENCE` | Должны совпадать с проверками Gateway |
| `AUTH_OTP_HMAC_SECRET` | Не менее 32 случайных байтов в base64 |
| `AUTH_PORT`, `AUTH_EDGE_NETWORK` | HTTP-порт при bootRun; внешняя сеть Compose (по умолчанию `cashflow-auth-edge`) |
| `AUTH_CODE_TTL`, `AUTH_RESEND_DELAY`, `AUTH_MAX_CODE_ATTEMPTS` | Срок кода, задержка повторной отправки и максимум неверных попыток |
| `AUTH_EMAIL_WINDOW`, `AUTH_EMAIL_LIMIT` | Окно и лимит отправок на email |
| `AUTH_SOURCE_WINDOW`, `AUTH_SOURCE_LIMIT`, `AUTH_VERIFY_SOURCE_LIMIT` | Окно, лимит отправок и проверок на сетевой источник |
| `AUTH_ACCESS_TTL`, `AUTH_REFRESH_TTL`, `AUTH_CSRF_TTL` | Сроки access, refresh и CSRF |

TTL/лимиты задаются Spring properties `auth.*`, в том числе переменными `AUTH_ACCESS_TTL`,
`AUTH_REFRESH_TTL`, `AUTH_CODE_TTL`, `AUTH_MAX_CODE_ATTEMPTS`.
По умолчанию: код 5 минут/5 попыток; resend через минуту; 5 отправок на email в час;
30 отправок и 100 проверок на источник за 15 минут; access 15 минут; refresh 30 дней; CSRF 12 часов.
Refresh продлевает срок сессии от момента обновления.

Лимиты хранятся в PostgreSQL и действуют между репликами/перезапусками.
Источник — фактический сетевой peer (`remoteAddr`), произвольный клиентский `X-Forwarded-For`
не используется. Если перед сервисом один Gateway, это общий лимит для него:
Gateway должен дополнительно ограничивать запросы по проверенному IP клиента.

## Сборка и инфраструктура

```bash
bash gradlew test build
docker network create cashflow-auth-edge
docker compose --env-file .env.auth-profile -f compose.auth-profile.yaml up --build -d
```

Compose поднимает Auth/Profile и PostgreSQL 16. Backend и БД не имеют host ports.
Сеть `auth-db` внутренняя; к внешней `cashflow-auth-edge` нужно подключить только доверенный Gateway.
Сервису требуется исходящий доступ к SMTP. Health: `GET /actuator/health` во внутренней сети.
HTTP-порт — 8081, контейнер запускается без root.

При `bootRun` переменные должны быть экспортированы в окружение процесса:
Spring не загружает `.env.auth-profile` автоматически.
Liquibase применяет схему `auth_profile` при старте; повторный запуск не меняет существующие данные.
Миграция `002-profile-ownership` переносит имя, версию и timestamps существующих пользователей
в `profiles`. Исходный changeset `001` сохранён. Старые колонки в `users` остаются для rollback;
актуальный профиль читается и изменяется только в `profiles`.

## Структура реализации

`features.security` владеет идентификацией, кодами и сессиями; `features.profile` — профилями.
В каждой feature команды и запросы имеют отдельные модели, сервисы и репозитории.
Запросы выполняются read-only. Запись lastSeen относится к отдельной команде аутентификации
на внутреннем входе, а не к запросу списка сессий.

Application services координируют регистрацию, refresh, доставку кода и изменение профиля,
не обращаясь к SQL или репозиториям. Создание пользователя, профиля, сессии и погашение
кода входят в одну транзакцию. Межфичевые вызовы идут через `ProfileProvisioner` и `ProfileLookup`.
Проверки состояния вынесены в validators, время — в `ApplicationClock`.

SQL остаётся в репозиториях на jOOQ с генерируемыми таблицами/records. `generateJooq`
запускает временный embedded PostgreSQL и применяет актуальный Liquibase changelog перед
генерацией; генерируемые файлы находятся в `build` и не коммитятся. Для этой стадии сборки
нужна среда, поддерживающая embedded PostgreSQL; внешняя рабочая БД не используется.
Репозитории используют `fetchOptionalInto` / `fetchSingleInto` / `fetchInto` для чтения
и `dsl.newRecord(table, model)` для сохранения моделей. Компоненты Java records сопоставляются
по именам; JOIN-проекции задают необходимые SQL aliases. Преобразование timestamps в `Instant`
проверяется интеграционными тестами, включая значения с ненулевым часовым смещением.

Контроллеры состоят из интерфейсов и тонких реализаций. DTO и MapStruct-мапперы находятся
на HTTP-границе, доменные исключения переводятся в существующий JSON централизованно.
Конструкторы зависимостей генерирует Lombok `@RequiredArgsConstructor`, records создаются
через `@Builder`. MapStruct использует те же builders; совместная обработка подключена
через `lombok-mapstruct-binding`. Lombok требуется только при компиляции. Для records и
builders с кодами, токенами и ключами сохранён безопасный `toString()` без значений секретов.
`ResponseEntity` оставлен только в операциях с динамическими cookie-заголовками.
SMTP скрыт за `CodeDeliveryGateway`, сбой доставки инвалидирует уже сохранённый challenge.

## Gateway

Сохранить полный префикс `/api/v1/auth-profile`. Настроить Gateway на `http://auth-profile:8081`.
Проверять access JWT только на Gateway: RS256, публичный ключ, `kid`, issuer, audience,
exp/nbf, UUID `sub` и `sid`. Публичный ключ должен соответствовать ключу выпуска;
JWKS endpoint в этом сервисе не публикуется. Ротация ключа требует согласованного обновления Gateway.

Перед передачей защищённого запроса удалить клиентские `X-User-*`/`X-Auth-*`, Authorization
и cookies, установить ровно один `X-User-Id` и `X-Auth-Session-Id`.
Auth/Profile дополнительно проверяет активность и принадлежность сессии в БД.
Cookie/JWT не заменяют доверенный контекст на внутреннем входе.

Для csrf/code/verify пропустить только CSRF cookie, для refresh/logout — refresh и CSRF.
CSRF/Origin защищённых PUT/DELETE проверяет Gateway перед удалением cookies.
Lifecycle endpoints проверяют CSRF/Origin повторно внутри Auth/Profile.
Bootstrap без Origin допускается только с `Sec-Fetch-Site: same-origin` и Referer
с origin из allowlist; так работают обычные same-origin GET из браузера.
Действующая CSRF-привязка переиспользуется между вкладками.

Сохранить отдельные Set-Cookie, Cache-Control, Retry-After и X-Trace-Id.
Не проксировать actuator наружу. CORS/preflight реализует Gateway.
Отзыв сессии сразу запрещает работу с Auth/Profile и refresh;
для других сервисов access может оставаться действующим до exp.

Закрытые порты Compose — часть сетевой границы; на целевом хосте дополнительно проверить,
кто подключён к auth-edge и кто может обращаться к контейнерной сети.
Развёртывание вместе с Gateway и доставка через конкретный production SMTP проверяются отдельно.

## Проверки

`bash gradlew test check build` запускает unit, архитектурные, контрактные и интеграционные проверки.
Интеграционные тесты работают с PostgreSQL и проверяют вход/повторный вход,
TTL/попытки/resend, SMTP-сбой, CSRF, JWT/cookies, отзыв сессий, строгий JSON и профиль.
Конкурентные сценарии: verify одного challenge, refresh одного credential,
обновление одной версии профиля. Дополнительно проверяются миграция существующих профилей,
откат регистрации при сбое profile API и отсутствие записи в query-пути.
Все интеграционные тесты наследуют общий `AbstractIntegrationTest` и используют
мок `CodeDeliveryGateway`; unit-тесты адаптера SMTP подменяют `JavaMailSender`.
Письма наружу не отправляются.

Тестовая БД использует loopback TCP без Unix-сокетов. Для запуска от root нужны user namespaces.
Если среда не позволяет запустить embedded PostgreSQL, используйте отдельную
внешнюю БД через `TEST_DATABASE_URL`; тесты удаляют данные в схеме `auth_profile`.
GitHub Actions запускает полный набор от обычного пользователя.
Для проверки через HTTPS используйте `http/auth-profile.http` с подключённым Gateway.
