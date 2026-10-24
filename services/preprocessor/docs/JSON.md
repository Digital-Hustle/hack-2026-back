# Пример подготовленного JSON

Ниже искусственный пример, не выгрузка пользовательских финансов. В ответе всегда есть `companies`, `operations`, `dailyBalances` и `report`; необязательные значения не выдумываются.

```json
{
  "schemaVersion": "1",
  "rulesVersion": "2026.10-1",
  "inputFingerprint": "0000000000000000000000000000000000000000000000000000000000000000",
  "preparedAt": "2026-10-24T00:00:00Z",
  "companies": [
    {
      "companyId": "DEMO",
      "companyName": null,
      "companyInn": null,
      "legalForm": null,
      "industry": null,
      "firstOperationDate": null,
      "lastOperationDate": null,
      "operationsCount": null,
      "sourceFields": {}
    }
  ],
  "operations": [
    {
      "companyId": "DEMO",
      "operationId": "DEMO-1",
      "operationDate": "2026-09-30",
      "amount": "990.00",
      "direction": "OUT",
      "counterpartyInn": "0012345678",
      "counterpartyName": "Тестовый банк",
      "paymentPurpose": "Комиссия за ведение счета",
      "balanceEod": "1000.00",
      "category": {
        "code": "BANK_FEES",
        "title": "Банковские комиссии",
        "source": "RULE",
        "ruleId": "bank-fee",
        "rulesVersion": "2026.10-1",
        "needsReview": false
      },
      "sourceRecord": 1,
      "sourceLine": 2,
      "sourceFields": {
        "company_id": "DEMO",
        "operation_id": "DEMO-1",
        "operation_date": "2026-09-30",
        "amount": "990.00",
        "direction": "Списание",
        "counterparty_inn": "0012345678",
        "counterparty_name": "Тестовый банк",
        "payment_purpose": "Комиссия за ведение счета",
        "balance_eod": "1000.00"
      }
    }
  ],
  "dailyBalances": [
    {
      "companyId": "DEMO",
      "date": "2026-09-30",
      "inflow": "0.00",
      "outflow": "990.00",
      "balanceEod": "1000.00",
      "balanceStatus": "OBSERVED",
      "reconcilesWithPrevious": null
    }
  ],
  "report": {
    "status": "PREPARED",
    "rowsTotal": 1,
    "rowsAccepted": 1,
    "rowsRejected": 0,
    "rowsDuplicate": 0,
    "unclassifiedRows": 0,
    "issueCount": 0,
    "issuesTruncated": false,
    "issueCounts": {},
    "issues": []
  }
}
```

Нулевой `inputFingerprint` выше — иллюстрация формата. Настоящий ответ содержит вычисленный SHA-256. Это не ID импорта в БД и не доказательство пользовательских прав.

Пример проблемы строки:

```json
{
  "code": "INVALID_FIELD",
  "severity": "ERROR",
  "source": "STATEMENTS",
  "sourceRecord": 4,
  "sourceLine": 5,
  "field": "amount",
  "message": "Сумма должна быть положительной и помещаться в NUMERIC(19,2)"
}
```

`rowsTotal = rowsAccepted + rowsRejected + rowsDuplicate`. Повтор с конфликтующим ID исключает все его версии из корректных операций. Список деталей ограничен, но `issueCount` и `issueCounts` учитывают все обнаруженные проблемы. `PREPARED` допускает предупреждения; `PARTIAL` не должен трактоваться как полностью завершённый пользовательский импорт.

Ни `regular`, ни `recurring`, ни произвольные проценты уверенности не добавляются. В ответе нет состояния сохранения/доставки в Orchestrator.
