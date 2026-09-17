package com.example.tasklist.kyc.application.service.exception;

import java.util.UUID;

/**
 * Заявка найдена, но принадлежит другому клиенту. Отдельный от {@link ApplicationNotFoundException}
 * тип нужен, чтобы явно отличать "нет такой заявки" от "заявка не ваша" — важно для того, чтобы
 * не обрабатывать оба случая одинаково там, где это имеет значение (например, для аудита).
 */
public class ApplicationClientMismatchException extends RuntimeException {
    public ApplicationClientMismatchException(UUID applicationId, UUID clientId) {
        super("Application %s does not belong to client %s".formatted(applicationId, clientId));
    }
}
