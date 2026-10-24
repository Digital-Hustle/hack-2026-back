package ru.cashflow.preprocessor.features.statements.model;

public enum CategoryCode {
    REVENUE_ACQUIRING("Выручка: эквайринг"),
    REVENUE_SBP("Выручка: СБП"),
    REVENUE_CASH("Выручка: наличные"),
    REVENUE_MARKETPLACE("Выручка: маркетплейсы"),
    REVENUE_CUSTOMERS("Поступления от клиентов"),
    SALARY("Зарплата"),
    TAXES("Налоги и взносы"),
    BANK_FEES("Банковские комиссии"),
    OWN_TRANSFER("Переводы между своими счетами"),
    OWNER_FINANCING("Финансирование от владельца"),
    REFUND("Возвраты платежей"),
    LOAN_RECEIPT("Получение кредита или займа"),
    LOAN_REPAYMENT("Погашение кредита или займа"),
    LEASING("Лизинг"),
    RENT("Аренда помещения"),
    EQUIPMENT_RENT("Аренда оборудования"),
    FUEL("Топливо"),
    TRANSPORT("Транспорт и дорожные сборы"),
    REPAIRS("Ремонт и обслуживание"),
    SUPPLIERS("Закупки у поставщиков"),
    UTILITIES("Коммунальные услуги"),
    COMMUNICATIONS("Связь и интернет"),
    SOFTWARE("ПО и подписки"),
    ACCOUNTING("Бухгалтерские услуги"),
    SECURITY("Охрана"),
    CLEANING("Уборка"),
    ADVERTISING("Реклама"),
    CONTRACTOR_SERVICES("Услуги подрядчиков"),
    OFFICE_SUPPLIES("Канцтовары и хозяйственные товары"),
    EQUIPMENT_PURCHASE("Покупка оборудования"),
    OTHER("Не определено");

    private final String title;

    CategoryCode(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
