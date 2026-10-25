package ru.cashflow.orchestrator.features.integrations.bitrix.gateway;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.BitrixDocument;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.DocumentKind;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Portal;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Stage;

/**
 * Fixed demo portal for showing the integration without a Bitrix24 account. Dates are relative to the company's
 * today, so every run lands inside the forecast horizon. Covers every branch of the sync: open deals with
 * probabilities, won and lost deals, a non-RUB deal and smart invoices in open, paid and declined stages.
 */
public final class BitrixDemoData {

    public static final Portal PORTAL = new Portal("demo", null);

    private BitrixDemoData() {
    }

    public static List<BitrixDocument> documents(LocalDate today) {
        return List.of(
                deal("101", "Поставка оборудования", "350000.00", "RUB", today.plusDays(5), Stage.OPEN, "C1:EXECUTING",
                        80, "11", "ООО «Ромашка»", today.minusDays(20)),
                deal("102", "Внедрение CRM, 2 этап", "180000.00", "RUB", today.plusDays(12), Stage.OPEN, "C1:FINAL_INVOICE",
                        50, "12", "АО «Вектор»", today.minusDays(35)),
                deal("103", "Годовой сервисный контракт", "96000.00", "RUB", today.plusDays(21), Stage.OPEN,
                        "C1:PREPARATION", 30, "13", "ИП Соколов", today.minusDays(4)),
                deal("104", "Пилотный проект", "120000.00", "RUB", today.plusDays(8), Stage.LOST, "C1:LOSE", 0, "14",
                        "ООО «Север»", today.minusDays(40)),
                deal("105", "Продление лицензий", "75000.00", "RUB", today.plusDays(3), Stage.WON, "C1:WON", 100, "11",
                        "ООО «Ромашка»", today.minusDays(15)),
                deal("106", "Экспортный заказ", "5000.00", "USD", today.plusDays(15), Stage.OPEN, "C1:EXECUTING", 60, "15",
                        "Global Parts Ltd", today.minusDays(10)),
                invoice("201", "2026-118", "Аренда стенда, ноябрь", "64000.00", today.plusDays(7), Stage.OPEN, "DT31_1:S",
                        "12", "АО «Вектор»", today.minusDays(3)),
                invoice("202", "2026-121", "Консультации", "42000.00", today.plusDays(16), Stage.OPEN, "DT31_1:N", "13",
                        "ИП Соколов", today.minusDays(1)),
                invoice("203", "2026-109", "Обучение персонала", "58000.00", today.plusDays(2), Stage.WON, "DT31_1:P", "11",
                        "ООО «Ромашка»", today.minusDays(12)),
                invoice("204", "2026-097", "Доработка отчётов", "31000.00", today.plusDays(9), Stage.LOST, "DT31_1:D", "14",
                        "ООО «Север»", today.minusDays(25)));
    }

    private static BitrixDocument deal(String id, String title, String amount, String currency, LocalDate due,
                                       Stage stage, String stageCode, Integer probability, String companyId,
                                       String companyName, LocalDate issued) {
        return new BitrixDocument(DocumentKind.DEAL, id, title, new BigDecimal(amount), currency, due, issued, stage,
                stageCode, probability, companyId, companyName, id);
    }

    private static BitrixDocument invoice(String id, String number, String title, String amount, LocalDate due,
                                          Stage stage, String stageCode, String companyId, String companyName,
                                          LocalDate issued) {
        return new BitrixDocument(DocumentKind.INVOICE, id, title, new BigDecimal(amount), "RUB", due, issued, stage,
                stageCode, null, companyId, companyName, number);
    }
}
