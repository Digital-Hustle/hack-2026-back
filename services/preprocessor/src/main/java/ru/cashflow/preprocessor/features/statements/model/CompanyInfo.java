package ru.cashflow.preprocessor.features.statements.model;

import java.time.LocalDate;
import java.util.Map;

public record CompanyInfo(String companyId, String companyName, String companyInn, String legalForm, String industry,
                          LocalDate firstOperationDate, LocalDate lastOperationDate, Integer operationsCount,
                          Map<String, String> sourceFields) {}
