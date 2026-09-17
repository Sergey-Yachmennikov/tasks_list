package com.example.tasklist.order.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    void save(OutboxMessage message);

    /**
     * Атомарно захватывает до {@code limit} готовых к отправке сообщений (статус {@code NEW},
     * {@code nextAttemptAt} в прошлом), переводя их в {@code PUBLISHING}, и возвращает их,
     * упорядоченные по {@code nextAttemptAt}. Реализация должна делать захват одним
     * выражением {@code UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING ...},
     * чтобы несколько инстансов могли одновременно опрашивать одну таблицу без двойного захвата,
     * и чтобы блокировка строки удерживалась только на время этого одного быстрого выражения —
     * никогда на время последующего сетевого вызова в Kafka.
     */
    List<OutboxMessage> lockBatchForPublishing(int limit);

    void markSent(UUID messageId);

    /**
     * Фиксирует неудачную попытку публикации, возвращая строку обратно в {@code NEW}
     * (с инкрементом счётчика попыток и сдвигом {@code nextAttemptAt} на вычисленный
     * вызывающим кодом backoff), чтобы она снова была подхвачена будущим вызовом
     * {@link #lockBatchForPublishing}, когда придёт её время.
     */
    void recordFailure(UUID messageId, String errorMessage, Instant nextAttemptAt);

    /**
     * Переводит строку в терминальный статус {@link OutboxStatus#DEAD_LETTERED} после
     * исчерпания попыток, окончательно убирая её из цикла публикации. Отдельная dead-letter
     * таблица/топик и алерт должны обрабатывать строки в этом статусе; их настройка вне
     * рамок этого модуля.
     */
    void markDeadLettered(UUID messageId, String errorMessage);

    /**
     * Возвращает в {@code NEW} строки, застрявшие в {@code PUBLISHING} дольше, чем
     * {@code staleAfter} (с инкрементом счётчика попыток). Покрывает случай, когда инстанс
     * поллера упал или был убит между захватом пачки и записью результата — без этого такие
     * строки никогда бы не были повторно обработаны. Консьюмеры и так обязаны терпеть
     * повторную доставку, так что сообщение, которое реально успело опубликоваться прямо
     * перед падением, в худшем случае будет доставлено ещё раз.
     *
     * @return количество возвращённых строк
     */
    int reclaimStalePublishing(Instant claimedBefore);

    /** Количество строк, всё ещё ожидающих публикации; используется для gauge-метрики backlog. */
    long countPending();

    /** Удаляет строки SENT старше {@code cutoff}. Используется job'ом ретеншена. */
    int deleteSentOlderThan(Instant cutoff);
}
