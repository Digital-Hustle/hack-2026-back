#!/usr/bin/env python3
"""Демо-заглушка 1С: отдаёт фиксированный реестр неоплаченных счетов и налогов.

Это не подключение к 1С и не часть бизнес-сервисов. Только для показа формата данных.
Запуск: python3 tools/one-c-mock/one_c_mock.py [--port 8099]
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DOCUMENTS = [
    {
        "externalSystem": "1C_DEMO",
        "externalId": "invoice-1001",
        "documentType": "INVOICE",
        "documentNumber": "СФ-1001",
        "title": "Счёт покупателю ООО «Ромашка»",
        "direction": "IN",
        "amount": "100000.00",
        "issuedDate": "2026-10-20",
        "dueDate": "2026-11-10",
        "categoryCode": "SALES",
        "counterpartyName": "ООО «Ромашка»",
        "counterpartyInn": "7701234567",
    },
    {
        "externalSystem": "1C_DEMO",
        "externalId": "bill-2045",
        "documentType": "INVOICE",
        "documentNumber": "СЧ-2045",
        "title": "Счёт поставщика за оборудование",
        "direction": "OUT",
        "amount": "70000.00",
        "issuedDate": "2026-10-18",
        "dueDate": "2026-11-05",
        "categoryCode": "SUPPLIERS",
        "counterpartyName": "ООО «Поставщик»",
        "counterpartyInn": "7707654321",
    },
    {
        "externalSystem": "1C_DEMO",
        "externalId": "tax-usn-9m-2026",
        "documentType": "TAX",
        "documentNumber": None,
        "title": "Налог по УСН за 9 месяцев (рассчитан в 1С)",
        "direction": "OUT",
        "amount": "25000.00",
        "issuedDate": "2026-10-10",
        "dueDate": "2026-10-28",
        "categoryCode": "TAXES",
        "counterpartyName": "ФНС",
        "counterpartyInn": None,
    },
]

PATH = "/api/1c/future-payments"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self._send(200, {"status": "UP"})
        if url.path != PATH:
            return self._send(404, {"code": "NOT_FOUND"})
        query = parse_qs(url.query)
        return self._send(200, {
            "source": "1C_DEMO",
            "demo": True,
            "companyId": query.get("companyId", [None])[0],
            "accountId": query.get("accountId", [None])[0],
            "items": DOCUMENTS,
        })

    def _send(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        print("one_c_mock %s %s" % (self.command, urlparse(self.path).path))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8099)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("1C demo mock: http://%s:%d%s" % (args.host, args.port, PATH))
    server.serve_forever()


if __name__ == "__main__":
    main()
