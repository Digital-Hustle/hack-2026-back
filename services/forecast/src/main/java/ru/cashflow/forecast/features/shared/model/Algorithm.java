package ru.cashflow.forecast.features.shared.model;

/**
 * V1 is the plain weekday mean. V2 keeps the same calendar/plan arithmetic but estimates the residual weekday level
 * with a holiday-aware, outlier-capped, shrinkage estimator. V2 is opt-in and is always scored next to V1 in backtests.
 */
public enum Algorithm { WEEKDAY_MEAN_V1, WEEKDAY_ROBUST_V2 }
