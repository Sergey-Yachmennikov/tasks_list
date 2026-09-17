package com.example.tasklist.kyc.application.domain;

/**
 * В исходном ТЗ {@code permits} ссылался на несуществующий {@code Success} и дважды на
 * {@code ValidationError} — не компилировалось. Здесь оба варианта — настоящие nested-типы
 * самого sealed-интерфейса, а не соседние top-level классы, чтобы {@code permits} совпадал
 * с реальными именами без лишней квалификации.
 */
public sealed interface NotificationResult {

    record Success(String messageId) implements NotificationResult {}

    record ValidationError(String reason) implements NotificationResult {}
}
