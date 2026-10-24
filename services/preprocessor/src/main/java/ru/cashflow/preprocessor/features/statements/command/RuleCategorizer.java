package ru.cashflow.preprocessor.features.statements.command;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import ru.cashflow.preprocessor.features.statements.model.CategoryCode;
import ru.cashflow.preprocessor.features.statements.model.CategoryDecision;
import ru.cashflow.preprocessor.features.statements.model.Direction;

public final class RuleCategorizer {

    public static final String RULES_VERSION = "2026.10-1";
    private static final Pattern CUSTOMER_PAYMENT = Pattern.compile(
            "\\bоплата\\b|\\bдоплата\\b|\\bпредоплата\\b|окончательный расчет", Pattern.UNICODE_CHARACTER_CLASS);
    private static final List<Rule> RULES = List.of(
            rule("loan-return", Direction.OUT, CategoryCode.LOAN_REPAYMENT, "^возврат\\s+(займа|кредита|заемных)"),
            rule("refund", null, CategoryCode.REFUND,
                    "^возврат\\b|возврат\\s+(ошибочн\\w*|платеж\\w*|денежн\\w*\\s+средств|аванса|предоплаты)"),
            rule("own-transfer", null, CategoryCode.OWN_TRANSFER,
                    "перевод\\s+собственных\\s+средств|между\\s+своими\\s+счетами"),
            rule("owner-financing", Direction.IN, CategoryCode.OWNER_FINANCING,
                    "(помощь|взнос|пополнение|финансирование).*(учредител|собственник|владел)|"
                            + "(учредител|собственник|владел).*(помощь|взнос|пополнение|финансирование)"),
            rule("loan-repayment", Direction.OUT, CategoryCode.LOAN_REPAYMENT,
                    "погашени\\w*\\s+(кредит|займ|заем|задолженности по кредит)|"
                            + "возврат\\s+займа|процент\\w*\\s+по\\s+(кредит|займ)"),
            rule("loan-receipt", Direction.IN, CategoryCode.LOAN_RECEIPT,
                    "(получение|выдача|предоставление|зачисление).*(кредит|займ)|"
                            + "кредитные\\s+средства|заемные\\s+средства"),
            rule("salary", Direction.OUT, CategoryCode.SALARY,
                    "заработн\\w*\\s+плат|\\bзарплат|аванс.*сотрудник"),
            rule("tax", Direction.OUT, CategoryCode.TAXES,
                    "единый\\s+налогов\\w*\\s+платеж|\\bенп\\b|\\bндфл\\b|"
                            + "(уплата|перечисление|оплата)\\s+налог|страхов\\w*\\s+взнос"),
            rule("acquiring-revenue", Direction.IN, CategoryCode.REVENUE_ACQUIRING, "эквайринг"),
            rule("sbp-revenue", Direction.IN, CategoryCode.REVENUE_SBP,
                    "зачислен\\w*.*(\\bсбп\\b|qr.?код)"),
            rule("cash-revenue", Direction.IN, CategoryCode.REVENUE_CASH,
                    "инкассированная\\s+выручка|торговая\\s+выручка|взнос\\s+наличных.*выручк"),
            rule("bank-fee", Direction.OUT, CategoryCode.BANK_FEES,
                    "комисси\\w*\\s+(за|по).*(ведение\\s+счета|обслуживание\\s+счета|банковск|перевод|эквайринг)|"
                            + "банковск\\w*\\s+комисси|расчетно.кассовое\\s+обслуживание"),
            rule("repairs", Direction.OUT, CategoryCode.REPAIRS,
                    "\\bремонт|обслуживани\\w*.*(автомобил|оборудован)|шиномонтаж"),
            rule("leasing", Direction.OUT, CategoryCode.LEASING, "лизинг"),
            rule("equipment-rent", Direction.OUT, CategoryCode.EQUIPMENT_RENT,
                    "аренд\\w*.*(pos|терминал|оборудован|техник)"),
            rule("rent", Direction.OUT, CategoryCode.RENT, "\\bаренд(?:а|ы|у|е|ой|н\\w*)\\b"),
            rule("fuel", Direction.OUT, CategoryCode.FUEL, "топлив|дизел|бензин|\\bгсм\\b"),
            rule("road-transport", Direction.OUT, CategoryCode.TRANSPORT,
                    "провоза\\s+по\\s+дорогам|платон|транспортн|перевозк|доставк|экспедиц"),
            rule("communications", Direction.OUT, CategoryCode.COMMUNICATIONS,
                    "услуг\\w*\\s+связи|интернет|телефони"),
            rule("software", Direction.OUT, CategoryCode.SOFTWARE,
                    "использования\\s+по\\b|программн\\w*\\s+обеспечени|подписк|лицензи\\w*.*(по\\b|программ)"),
            rule("accounting", Direction.OUT, CategoryCode.ACCOUNTING, "бухгалтер"),
            rule("security", Direction.OUT, CategoryCode.SECURITY, "услуг\\w*\\s+охран|охранн"),
            rule("cleaning", Direction.OUT, CategoryCode.CLEANING, "уборк|клининг"),
            rule("utilities", Direction.OUT, CategoryCode.UTILITIES,
                    "коммунальн|электроэнерг|водоснабж|отоплен|вывоз\\s+(тко|мусора|отходов)"),
            rule("advertising", Direction.OUT, CategoryCode.ADVERTISING, "реклам|маркетинг"),
            rule("office-supplies", Direction.OUT, CategoryCode.OFFICE_SUPPLIES,
                    "канцтовар|хозтовар|хозяйственн\\w*\\s+товар"),
            rule("equipment-purchase", Direction.OUT, CategoryCode.EQUIPMENT_PURCHASE,
                    "(покупк|приобретени|оплат).*оборудован"),
            rule("contractor-services", Direction.OUT, CategoryCode.CONTRACTOR_SERVICES,
                    "договор\\w*\\s+гпх|самозанят|услуг\\w*\\s+подрядчик"),
            rule("suppliers", Direction.OUT, CategoryCode.SUPPLIERS,
                    "за\\s+(товар|продукц|продукт|материал|сырье|кофе|мясн|хлебобулоч|напитк|"
                            + "автозапчаст|запчаст|удобрени|семен|косметик)|договор\\w*\\s+поставки")
    );

    public CategoryDecision categorize(Direction direction, String purpose, String counterpartyName) {
        String text = normalize(purpose);
        for (Rule rule : RULES) {
            if ((rule.direction() == null || rule.direction() == direction) && rule.pattern().matcher(text).find()) {
                return new CategoryDecision(rule.category(), rule.id(), RULES_VERSION);
            }
        }
        if (direction == Direction.IN && !text.isBlank()) {
            String counterparty = normalize(counterpartyName);
            if ((counterparty.contains("маркетплейс") || counterparty.contains("wildberries")
                    || counterparty.contains("ozon")) && (text.contains("оферт") || text.contains("реализац"))) {
                return new CategoryDecision(CategoryCode.REVENUE_MARKETPLACE, "marketplace-revenue", RULES_VERSION);
            }
            if (CUSTOMER_PAYMENT.matcher(text).find()) {
                return new CategoryDecision(CategoryCode.REVENUE_CUSTOMERS, "customer-payment", RULES_VERSION);
            }
        }
        return new CategoryDecision(CategoryCode.OTHER, null, RULES_VERSION);
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").strip();
    }

    private static Rule rule(String id, Direction direction, CategoryCode category, String expression) {
        return new Rule(id, direction, category, Pattern.compile(expression, Pattern.UNICODE_CHARACTER_CLASS));
    }

    private record Rule(String id, Direction direction, CategoryCode category, Pattern pattern) {}
}
