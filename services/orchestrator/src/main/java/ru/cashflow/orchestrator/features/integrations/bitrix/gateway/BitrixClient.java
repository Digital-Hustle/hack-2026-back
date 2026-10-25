package ru.cashflow.orchestrator.features.integrations.bitrix.gateway;

import java.time.LocalDate;
import java.util.List;

import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.BitrixDocument;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Portal;

/** Reads CRM documents with a due date in [from, to] from a Bitrix24 portal. Never called inside a DB transaction. */
public interface BitrixClient {

    List<BitrixDocument> fetch(Portal portal, LocalDate from, LocalDate to, boolean deals, boolean invoices);
}
