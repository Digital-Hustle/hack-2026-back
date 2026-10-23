# Gateway

Java 21, Spring Cloud Gateway WebFlux. Настройки хранятся локально в
`src/main/resources/application.yml`; Config Server не нужен.

## Маршруты

| Метод и внешний путь | Получатель | Access cookie |
|---|---|---|
| `GET /api/v1/auth-profile/auth/csrf` | Auth/Profile, путь сохраняется | Нет |
| `POST /api/v1/auth-profile/auth/code`, `/verify` | Auth/Profile, путь сохраняется | Нет |
| `POST /api/v1/auth-profile/auth/refresh`, `/logout` | Auth/Profile, путь сохраняется | Нет |
| `GET`, `PUT /api/v1/auth-profile/profile/me` | Auth/Profile, путь сохраняется | Обязательна |
| `GET`, `DELETE /api/v1/auth-profile/auth/sessions` | Auth/Profile, путь сохраняется | Обязательна |
| `DELETE /api/v1/auth-profile/auth/sessions/{sessionId}` | Auth/Profile, путь сохраняется | Обязательна |
| `POST /api/v1/preprocessor/statements/prepare` | PreProcessor: `/internal/v1/statements/prepare` | Обязательна |

Последний маршрут принимает `multipart/form-data`: обязательный `file` и необязательный
`companies`. Тело передаётся потоком; gateway не собирает CSV в памяти. Лимиты размера,
формата и числа строк проверяет PreProcessor. Это **подготовка JSON**, без сохранения
операций и подтверждения владения компанией. Пользовательский импорт в Orchestrator
и проверка CompanyScope остаются отдельной интеграцией; внешние ID из CSV прав не дают.

Произвольные `/internal/**`, backend actuator, неизвестные пути и методы не маршрутизируются.
Health самого gateway: `GET /actuator/health`, без деталей.

## Конфигурация

| Переменная | Значение |
|---|---|
| `GATEWAY_PORT` | `5001` |
| `AUTH_PROFILE_URL` | `http://localhost:8081`; в контейнерной сети `http://auth-profile:8081` |
| `PREPROCESSOR_URL` | `http://localhost:8082`; в контейнерной сети `http://preprocessor:8082` |
| `GATEWAY_JWT_PUBLIC_KEY` | Обязательный публичный RSA-ключ Auth/Profile, base64 DER X.509; fallback `AUTH_JWT_PUBLIC_KEY` |
| `AUTH_JWT_ISSUER` | `https://auth.cashflow.internal`, как у Auth/Profile |
| `AUTH_JWT_AUDIENCE` | `cashflow-api`, как у Auth/Profile |
| `AUTH_JWT_KEY_ID` | `cashflow-1`, как у Auth/Profile |
| `GATEWAY_ALLOWED_ORIGINS` | Точные origin через запятую; fallback `AUTH_ALLOWED_ORIGINS`, затем `https://localhost` |

Gateway не получает приватный RSA-ключ или `AUTH_OTP_HMAC_SECRET`.
Публичный ключ должен соответствовать ключу выпуска; JWKS в Auth/Profile отсутствует.
Ротация требует согласованного обновления ключа и `kid`.
`GATEWAY_JWT_SECRET` больше не используется.

```bash
# Экспортируйте публичный ключ и origin из настроек своего Auth/Profile.
export GATEWAY_JWT_PUBLIC_KEY="$AUTH_JWT_PUBLIC_KEY"
export GATEWAY_ALLOWED_ORIGINS="$AUTH_ALLOWED_ORIGINS"
bash gradlew :services:gateway-server:bootRun
```

Для Docker из корня репозитория:

```bash
docker build -f services/gateway-server/Dockerfile -t cashflow-gateway .
```

Подключите gateway к закрытым сетям Auth/Profile и PreProcessor; задайте URL с DNS-именами
контейнеров. Auth/Profile Compose использует внешнюю `cashflow-auth-edge`.
PreProcessor по умолчанию слушает `127.0.0.1`; в контейнере требуется
`PREPROCESSOR_BIND_ADDRESS=0.0.0.0`, при этом его host port публиковать не следует.
Браузерный сценарий требует HTTPS на внешнем reverse proxy: Auth/Profile выдаёт Secure cookies.
Сетевые ACL, TLS, production SMTP и совместный запуск всех сервисов проверяются при развёртывании.

## Граница аутентификации

Защищённые маршруты принимают ровно одну `__Host-cashflow-access` cookie.
Проверяются RS256, RSA-подпись, `kid`, issuer, audience, обязательный `exp`, срок действия,
`nbf` и канонические UUID в `sub`/`sid`. Ошибка даёт `401 application/problem+json`.
Bearer header не заменяет браузерную cookie.

Перед проксированием удаляются все клиентские `X-User-*`/`X-Auth-*`, Authorization и Cookie;
устанавливаются ровно один `X-User-Id` и `X-Auth-Session-Id` из проверенного токена.
Актуальность сессии дополнительно проверяет Auth/Profile. PreProcessor принимает access
до `exp`; немедленный отзыв для него не реализован.

Lifecycle получает только CSRF cookie; refresh/logout дополнительно получают refresh cookie.
Access cookie в Auth/Profile не передаётся. Отдельные `Set-Cookie`, Cache-Control,
Retry-After и диагностические заголовки ответа сохраняются.

Для защищённых PUT/DELETE/POST обязательны разрешённый `Origin`, CSRF cookie и
`X-CSRF-Token`, полученный из `GET /api/v1/auth-profile/auth/csrf`.
Gateway сверяет binding через внутренний вызов того же bootstrap endpoint:
Auth/Profile проверяет подпись/срок cookie и возвращает связанный token. Случайная новая
привязка при неверной cookie не совпадёт с присланным заголовком. Ответ bootstrap
из этого внутреннего вызова клиенту не передаётся. Ошибка привязки — `403`,
недоступность Auth/Profile — `503`; изменение дальше не отправляется.
Это дополнительный HTTP-вызов на защищённую мутацию; ключ HMAC остаётся у Auth/Profile.
Lifecycle проверяет CSRF/Origin внутри Auth/Profile. CORS и preflight обслуживает gateway.

## Проверки и ограничения

```bash
bash gradlew :services:gateway-server:check :services:gateway-server:bootJar
```

HTTP-тесты поднимают настоящий gateway и две HTTP-заглушки. Проверяются маршруты,
RS256/cookies, неверные/истёкшие токены, подмена контекста, CSRF, multipart больше 256 KiB,
сохранение тела и Set-Cookie, CORS и отсутствие внешних внутренних маршрутов.
Это не end-to-end прогон с БД, SMTP и реальным парсером CSV.

До публичного запуска нужны проверка сетевой изоляции и лимиты на gateway по проверенному
IP клиента. Сейчас лимиты OTP внутри Auth/Profile видят общий сетевой peer gateway.
Маршрут Orchestrator и сохранение CSV-импорта не входят в эту конфигурацию.
