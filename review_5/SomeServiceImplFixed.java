import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Критические баги оригинала (SomeServiceImpl):
 * 1. taxService.sendTax(r) — компилировать не может: r объявлен внутри for и не виден
 *    после его закрывающей скобки. Похоже, отправку в налоговую забыли занести внутрь цикла.
 * 2. dao.findAll() — тащит ВСЮ таблицу без фильтра по статусу и без лимита. При 100k
 *    чеков/сутки это и есть тот самый долгий fetchAll(), про который прямо предупреждает ТЗ.
 * 3. r.setProcessed(true) нигде не сохраняется (dao.save(...)/dao.saveAll(...) не вызван) —
 *    даже если бы компилировалось, ни один чек не помечался бы обработанным в БД.
 * 4. Как следствие (2)+(3): при 8 подах с одинаковым cron КАЖДЫЙ под на каждом тике
 *    забирал бы одни и те же (никогда не помечаемые) чеки и слал бы их в налоговую заново —
 *    не разовый дубль при рестарте, а перманентное 8-кратное дублирование каждый час.
 * 5. Весь метод в одной @Transactional, включая (не)REST-вызов — соединение с БД держится
 *    на всё время похода в налоговую (таймаут там 60с), что при большом батче держит
 *    транзакцию открытой очень долго и вычерпывает пул соединений на под.
 * 6. Нет claim/lock (SELECT ... FOR UPDATE SKIP LOCKED), нет attempt/nextRetryAt/lockedUntil —
 *    то есть отсутствует вся модель эксклюзивности и ретраев, описанная в ТЗ.
 * <p>
 * Ниже — тот же паттерн, что уже реализован в этом репозитории для outbox
 * (см. JdbcOutboxRepository.lockBatchForPublishing в lending-application-service):
 * атомарный claim одной короткой транзакцией, сам REST-вызов вне транзакции, фиксация
 * результата — снова короткой отдельной транзакцией.
 * <p>
 * Требует добавить в ReceiptDao (в этом файле их нет, т.к. дан только сервис):
 * - {@code List<Receipt> claimBatch(int limit, Instant now, Instant lockedUntil)} —
 *   один атомарный UPDATE ... WHERE status='NEW' OR (status='FAILED' AND next_retry_at <= now())
 *   OR (status='SENDING' AND locked_until <= now()) ... RETURNING, с SELECT ... FOR UPDATE
 *   SKIP LOCKED внутри — ровно как в lockBatchForPublishing.
 * - {@code void markSent(UUID receiptId, String taxResponseId, Instant sentAt)}
 * - {@code void markFailed(UUID receiptId, String error, Instant nextRetryAt)} —
 *   nextRetryAt = null для бизнес-отказов, которые не должны ретраиться автоматически.
 */
@Service
@RequiredArgsConstructor
public class SomeServiceImplFixed implements SomeService {

    private static final Logger log = LoggerFactory.getLogger(SomeServiceImplFixed.class);

    // Сколько чеков забираем за один тик — ограничивает и память (не тащим всю таблицу,
    // как findAll() в оригинале), и суммарное время батча.
    private static final int CLAIM_BATCH_SIZE = 500;
    // На время между claim и фиксацией результата чек "заперт" за этим инстансом; если под
    // упадёт посреди REST-вызова, другой под подхватит чек только после истечения lockTtl —
    // без этого поля зависший под навсегда держал бы чек в SENDING. Берём с запасом
    // над 60-секундным таймаутом налоговой.
    private static final Duration LOCK_TTL = Duration.ofMinutes(2);
    // Ограничивает число одновременных REST-вызовов в налоговую на один под — без этого пула
    // 500 чеков ушли бы в сеть одновременно и попытались бы занять 500 соединений разом.
    private static final int MAX_CONCURRENT_CALLS = 20;

    private final TaxService taxService;
    private final ReceiptDao dao;

    // Самоинъекция прокси нужна по той же причине, что и в ProcessJobFixed (review_4):
    // claimBatch/markSent/markFailed помечены @Transactional и вызываются из другого метода
    // этого же бина — без прокси self-invocation тихо отключает @Transactional.
    @Lazy
    @Autowired
    private SomeServiceImplFixed self;

    // В реальном проде разумнее вынести в managed ThreadPoolTaskExecutor-бин (метрики,
    // единое место конфигурации размера пула). Оставлено полем ради односайлового фикса.
    private final ExecutorService taxCallExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_CALLS);

    @PreDestroy
    void shutdown() {
        taxCallExecutor.shutdown();
    }

    // Без @Transactional на самом методе: он больше не открывает одну транзакцию на весь
    // батч + все сетевые вызовы — транзакционность есть только у claimBatch()/markSent()/
    // markFailed() по отдельности, каждая своя короткая транзакция.
    @Scheduled(cron = "30 30 * * * *")
    @Override
    public void processJob() {
        // Атомарный claim: одна короткая транзакция переводит подходящие чеки в SENDING.
        // Эксклюзивность обеспечивает БД (FOR UPDATE SKIP LOCKED), а не код — поэтому 8 подов
        // с одинаковым cron не заберут один и тот же чек дважды.
        List<Receipt> claimed = self.claimBatch();
        if (claimed.isEmpty()) {
            return;
        }

        // Каждый чек шлётся независимо и ограниченно параллельно, вне транзакции. Метод
        // намеренно не блокируется на завершении батча: бэкпрешер и так обеспечен и
        // размером батча, и общим на под пулом taxCallExecutor — даже если следующий тик
        // @Scheduled стартует раньше, чем долетит текущая пачка, число одновременных
        // вызовов в налоговую всё равно не превысит MAX_CONCURRENT_CALLS. Ждать завершения
        // (например, через CompletableFuture.allOf(...).join()) здесь не нужно ещё и
        // потому, что @Scheduled по умолчанию делит один поток на все scheduled-задачи
        // приложения — держать его часами на .join() означало бы заодно остановить все
        // остальные @Scheduled-джобы в сервисе.
        //
        // Прямой submit в пул, а не CompletableFuture.runAsync(...): результат никто не
        // читает (нет ни join, ни composition), так что обёртка была бы чистым оверхедом —
        // и даже более опасным: необработанное исключение внутри runAsync осело бы в
        // непрочитанном Future и исчезло бы бесследно, тогда как исключение из execute()
        // уйдёт в UncaughtExceptionHandler потока и хотя бы попадёт в stderr.
        for (Receipt receipt : claimed) {
            taxCallExecutor.execute(() -> processOne(receipt));
        }
    }

    private void processOne(Receipt receipt) {
        try {
            // receipt.getId() — идемпотентный ключ: при повторной отправке (ретрай после
            // таймаута или новая попытка из FAILED) налоговая должна распознать этот же id
            // и не зарегистрировать чек повторно.
            TaxResponse response = taxService.sendTax(new TaxSendRequest(receipt.getId(), receipt.getSum()));
            self.markSent(receipt.getId(), response.taxResponseId());
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                // Явный отказ налоговой — бизнес-ошибка (например, чек не прошёл валидацию),
                // а не сетевая: повторная отправка того же содержимого получит тот же отказ.
                // nextRetryAt = null сознательно — claimBatch() такие чеки больше не выберет
                // автоматически, ими должен заняться отдельный воркер/оператор (см. ТЗ:
                // "надо продумать бизнес-логику проверки FAILED — ретраить может не иметь смысла").
                log.error("Tax service rejected receipt {} as invalid (status {})", receipt.getId(), e.status(), e);
                self.markFailed(receipt.getId(), e.getMessage(), null);
            } else {
                log.warn("Tax service transient failure for receipt {} (attempt {}, status {})",
                        receipt.getId(), receipt.getAttempt(), e.status(), e);
                self.markFailed(receipt.getId(), e.getMessage(), computeNextRetryAt(receipt.getAttempt()));
            }
        } catch (Exception e) {
            // Таймаут (60с на стороне налоговой) и прочие сетевые сбои — транзиентная
            // ошибка, ретраим с экспоненциальным backoff.
            log.warn("Tax submission failed for receipt {} (attempt {})", receipt.getId(), receipt.getAttempt(), e);
            self.markFailed(receipt.getId(), e.getMessage(), computeNextRetryAt(receipt.getAttempt()));
        }
    }

    /**
     * Атомарный захват пачки чеков. dao.claimBatch(...) должен выполнять одним запросом
     * (SELECT и UPDATE — раздельными вызовами нельзя: между ними появится окно гонки, в
     * которое два пода успеют выбрать одну и ту же строку):
     * <pre>{@code
     * UPDATE receipt
     * SET status = 'SENDING',
     *     locked_until = :lockedUntil,
     *     attempt = attempt + 1
     * WHERE id IN (
     *     SELECT id FROM receipt
     *     WHERE status = 'NEW'
     *        OR (status = 'FAILED'  AND next_retry_at <= :now)
     *        OR (status = 'SENDING' AND locked_until  <= :now)
     *     ORDER BY next_retry_at NULLS FIRST, created_at
     *     LIMIT :limit
     *     FOR UPDATE SKIP LOCKED
     * )
     * RETURNING id, client_id, status, attempt, locked_until, next_retry_at,
     *           tax_response_id, last_error, created_at
     * }</pre>
     * SKIP LOCKED — то, что делает 8 одновременных вызовов этого запроса с разных подов
     * безопасными: конкурирующая транзакция просто пропускает уже заблокированную другим
     * подом строку вместо того, чтобы ждать её или (хуже) прочитать и попытаться захватить
     * повторно. ORDER BY нужен, чтобы просроченные старые записи не оттеснялись новыми —
     * без него FOR UPDATE SKIP LOCKED не гарантирует никакого порядка выбора строк.
     */
    @Transactional
    public List<Receipt> claimBatch() {
        Instant now = Instant.now();
        return dao.claimBatch(CLAIM_BATCH_SIZE, now, now.plus(LOCK_TTL));
    }

    @Transactional
    public void markSent(UUID receiptId, String taxResponseId) {
        dao.markSent(receiptId, taxResponseId, Instant.now());
    }

    @Transactional
    public void markFailed(UUID receiptId, String error, Instant nextRetryAt) {
        dao.markFailed(receiptId, error, nextRetryAt);
    }

    private Instant computeNextRetryAt(int attempt) {
        long backoffSeconds = Math.min(30L * (1L << Math.min(attempt, 10)), Duration.ofHours(1).toSeconds());
        return Instant.now().plusSeconds(backoffSeconds);
    }
}
