package ru.cashflow.preprocessor.units;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ru.cashflow.preprocessor.features.statements.command.RuleCategorizer;
import ru.cashflow.preprocessor.features.statements.model.CategoryCode;
import ru.cashflow.preprocessor.features.statements.model.Direction;

import static org.assertj.core.api.Assertions.assertThat;

class RuleCategorizerTest {

    private final RuleCategorizer categorizer = new RuleCategorizer();

    @ParameterizedTest
    @MethodSource("clearPurposes")
    void classifiesByPurposeAndDirection(Direction direction, String purpose, String counterparty,
                                         CategoryCode expected) {
        var decision = categorizer.categorize(direction, purpose, counterparty);

        assertThat(decision.code()).isEqualTo(expected);
        assertThat(decision.source()).isEqualTo("RULE");
        assertThat(decision.ruleId()).isNotBlank();
        assertThat(decision.needsReview()).isFalse();
    }

    static Stream<Arguments> clearPurposes() {
        return Stream.of(
                Arguments.of(Direction.OUT, "Перечисление заработной платы (аванс)", "ПАО Банк",
                        CategoryCode.SALARY),
                Arguments.of(Direction.OUT, "Комиссия за ведение счета. Без НДС", "ПАО Банк",
                        CategoryCode.BANK_FEES),
                Arguments.of(Direction.OUT, "Погашение кредита: основной долг и проценты", "ПАО Банк",
                        CategoryCode.LOAN_REPAYMENT),
                Arguments.of(Direction.IN, "Зачисление средств по операциям эквайринга. Комиссия 50, возврат покупки 0",
                        "ПАО Банк", CategoryCode.REVENUE_ACQUIRING),
                Arguments.of(Direction.IN, "Зачисление по операциям оплаты через СБП (QR-код)", "ПАО Банк",
                        CategoryCode.REVENUE_SBP),
                Arguments.of(Direction.IN, "Взнос наличных. Торговая выручка", "Своя компания",
                        CategoryCode.REVENUE_CASH),
                Arguments.of(Direction.IN, "Перечисление денежных средств по договору оферты за период",
                        "ООО Маркетплейс", CategoryCode.REVENUE_MARKETPLACE),
                Arguments.of(Direction.OUT, "Единый налоговый платёж", "Казначейство",
                        CategoryCode.TAXES),
                Arguments.of(Direction.OUT, "  ЕНП  ", "Казначейство", CategoryCode.TAXES),
                Arguments.of(Direction.OUT, "Оплата за продукты питания. В т.ч. НДС 20%", "Поставщик",
                        CategoryCode.SUPPLIERS),
                Arguments.of(Direction.IN, "Возврат платежа: неверные реквизиты. Оплата по УПД", "Поставщик",
                        CategoryCode.REFUND),
                Arguments.of(Direction.OUT, "Возврат аванса клиенту", "Клиент", CategoryCode.REFUND),
                Arguments.of(Direction.OUT, "Перевод собственных средств на расчетный счет в другом банке", "Своя компания",
                        CategoryCode.OWN_TRANSFER),
                Arguments.of(Direction.IN, "Безвозмездная финансовая помощь от учредителя", "Учредитель",
                        CategoryCode.OWNER_FINANCING),
                Arguments.of(Direction.IN, "Получение кредита по договору", "ПАО Банк",
                        CategoryCode.LOAN_RECEIPT),
                Arguments.of(Direction.OUT, "Оплата аренды нежилого помещения", "Арендодатель", CategoryCode.RENT),
                Arguments.of(Direction.OUT, "Плата за аренду POS-терминалов", "ПАО Банк",
                        CategoryCode.EQUIPMENT_RENT),
                Arguments.of(Direction.OUT, "Пополнение лицевого счета (топливные карты)", "Поставщик",
                        CategoryCode.FUEL),
                Arguments.of(Direction.OUT, "Оплата провоза по дорогам федерального значения", "Поставщик",
                        CategoryCode.TRANSPORT),
                Arguments.of(Direction.OUT, "Оплата услуг связи и доступа в Интернет", "Телеком",
                        CategoryCode.COMMUNICATIONS),
                Arguments.of(Direction.OUT, "Предоставление права использования ПО (подписка)", "Разработчик",
                        CategoryCode.SOFTWARE),
                Arguments.of(Direction.OUT, "Оплата за бухгалтерские услуги", "Подрядчик",
                        CategoryCode.ACCOUNTING),
                Arguments.of(Direction.OUT, "Оплата по счету за ремонт автомобиля", "Подрядчик", CategoryCode.REPAIRS),
                Arguments.of(Direction.OUT, "Оплата за канцтовары и хозтовары", "Подрядчик",
                        CategoryCode.OFFICE_SUPPLIES),
                Arguments.of(Direction.OUT, "Пополнение рекламного кабинета", "Подрядчик",
                        CategoryCode.ADVERTISING),
                Arguments.of(Direction.OUT, "Оплата услуг по договору ГПХ", "Подрядчик",
                        CategoryCode.CONTRACTOR_SERVICES),
                Arguments.of(Direction.OUT, "Возврат займа по договору", "Кредитор", CategoryCode.LOAN_REPAYMENT),
                Arguments.of(Direction.OUT, "Погашение заёмных средств", "Кредитор", CategoryCode.LOAN_REPAYMENT),
                Arguments.of(Direction.OUT, "Оплата ремонта арендованного помещения", "Подрядчик", CategoryCode.REPAIRS),
                Arguments.of(Direction.OUT, "Оплата услуг охраны", "Подрядчик", CategoryCode.SECURITY),
                Arguments.of(Direction.OUT, "Оплата за услуги уборки", "Подрядчик", CategoryCode.CLEANING),
                Arguments.of(Direction.OUT, "Оплата за электроэнергию", "Поставщик", CategoryCode.UTILITIES),
                Arguments.of(Direction.OUT, "Платёж по договору лизинга", "Лизингодатель", CategoryCode.LEASING),
                Arguments.of(Direction.OUT, "Оплата за оборудование", "Поставщик", CategoryCode.EQUIPMENT_PURCHASE),
                Arguments.of(Direction.IN, "Оплата по УПД за продукцию", "Клиент", CategoryCode.REVENUE_CUSTOMERS)
        );
    }

    @Test
    void doesNotInferPurposeFromBankNameOrVatMention() {
        assertThat(categorizer.categorize(Direction.IN, "", "ПАО Банк").code()).isEqualTo(CategoryCode.OTHER);
        assertThat(categorizer.categorize(Direction.OUT, "Без НДС", "Казначейство").code()).isEqualTo(CategoryCode.OTHER);
        assertThat(categorizer.categorize(Direction.OUT, "Зачисление средств по эквайрингу", "ПАО Банк").code())
                .isEqualTo(CategoryCode.OTHER);
    }

    @Test
    void unknownPurposeRequiresReviewWithoutInventedConfidence() {
        var decision = categorizer.categorize(Direction.OUT, "По соглашению сторон", "Компания");

        assertThat(decision.code()).isEqualTo(CategoryCode.OTHER);
        assertThat(decision.source()).isEqualTo("UNCLASSIFIED");
        assertThat(decision.ruleId()).isNull();
        assertThat(decision.needsReview()).isTrue();
    }

    @Test
    void doesNotLabelNonBankCommissionAsBankFee() {
        var decision = categorizer.categorize(Direction.OUT, "Оплата комиссии агента по договору", "ООО Агент");

        assertThat(decision.code()).isEqualTo(CategoryCode.OTHER);
        assertThat(decision.needsReview()).isTrue();
        assertThat(categorizer.categorize(Direction.OUT, "Комиссия за посреднические услуги", "ООО Агент").code())
                .isEqualTo(CategoryCode.OTHER);
    }
}
