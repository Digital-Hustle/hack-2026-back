# Демо-заглушка 1С

Отдаёт фиксированный JSON с неоплаченными счетами и рассчитанным налогом, как могла бы отдать
обработка на стороне 1С. Это **не** подключение к реальной 1С: данные статичные, бэкенд их пока
не сохраняет и в прогноз не передаёт.

```bash
python3 tools/one-c-mock/one_c_mock.py --port 8099
curl "http://127.0.0.1:8099/api/1c/future-payments?companyId=<uuid>&accountId=<uuid>"
```

Ответ: `{source, demo, companyId, accountId, items: [...]}`. Поля документа: `externalSystem`,
`externalId`, `documentType` (`INVOICE`/`TAX`), `documentNumber`, `title`, `direction` (`IN`/`OUT`),
`amount` (строка, 2 знака), `issuedDate`, `dueDate`, `categoryCode`, `counterpartyName`,
`counterpartyInn`. `GET /health` — проверка доступности.

Зависимостей нет, нужен только Python 3.
