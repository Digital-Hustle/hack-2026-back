package ru.cashflow.orchestrator.features.ledger.model;

import java.util.List;

/**
 * System category catalog exposed by the public API. Titles mirror PreProcessor's CategoryCode so the UI
 * shows exactly the catalog that classifies the statements.
 */
public enum CategoryCode {
    REVENUE_ACQUIRING("Выручка: эквайринг", true),
    REVENUE_SBP("Выручка: СБП", true),
    REVENUE_CASH("Выручка: наличные", true),
    REVENUE_MARKETPLACE("Выручка: маркетплейсы", true),
    REVENUE_CUSTOMERS("Поступления от клиентов", true),
    SALARY("Зарплата", false),
    TAXES("Налоги и взносы", false),
    BANK_FEES("Банковские комиссии", false),
    OWN_TRANSFER("Переводы между своими счетами", false),
    OWNER_FINANCING("Финансирование от владельца", true),
    REFUND("Возвраты платежей", true),
    LOAN_RECEIPT("Получение кредита или займа", true),
    LOAN_REPAYMENT("Погашение кредита или займа", false),
    LEASING("Лизинг", false),
    RENT("Аренда помещения", false),
    EQUIPMENT_RENT("Аренда оборудования", false),
    FUEL("Топливо", false),
    TRANSPORT("Транспорт и дорожные сборы", false),
    REPAIRS("Ремонт и обслуживание", false),
    SUPPLIERS("Закупки у поставщиков", false),
    UTILITIES("Коммунальные услуги", false),
    COMMUNICATIONS("Связь и интернет", false),
    SOFTWARE("ПО и подписки", false),
    ACCOUNTING("Бухгалтерские услуги", false),
    SECURITY("Охрана", false),
    CLEANING("Уборка", false),
    ADVERTISING("Реклама", false),
    CONTRACTOR_SERVICES("Услуги подрядчиков", false),
    OFFICE_SUPPLIES("Канцтовары и хозяйственные товары", false),
    EQUIPMENT_PURCHASE("Покупка оборудования", false),
    OTHER("Не определено", false);

    private final String title;
    private final boolean incoming;

    CategoryCode(String title, boolean incoming) {
        this.title = title;
        this.incoming = incoming;
    }

    public String title() {
        return title;
    }

    public List<LedgerTypes.Direction> directions() {
        return List.of(incoming ? LedgerTypes.Direction.IN : LedgerTypes.Direction.OUT);
    }
}