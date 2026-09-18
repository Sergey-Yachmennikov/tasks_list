import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
class ProcessJobFixed {

    private static final Logger log = LoggerFactory.getLogger(ProcessJobFixed.class);

    private final ReceiptDao receiptDao;
    private final TaxClient taxClient;

    // Самоинъекция прокси нужна только для markProcessed(...) ниже — см. комментарий там,
    // почему это единственный способ заставить @Transactional реально сработать при вызове
    // из метода этого же класса. @Lazy обязателен: без него Spring попытается полностью
    // создать этот же бин ещё до завершения его собственного конструктора и упадёт с
    // BeanCurrentlyInCreationException.
    @Lazy
    @Autowired
    private ProcessJobFixed self;

    public ProcessJobFixed(ReceiptDao receiptDao, TaxClient taxClient) {
        this.receiptDao = receiptDao;
        this.taxClient = taxClient;
    }

    // В оригинале был вызов self.getRefundReceipts(), хотя self нигде не был объявлен —
    // код не компилировался. Похоже, автор пытался обойти проблему self-invocation в Spring
    // (см. ниже про markProcessed), но применил обход не к тому методу: обычное чтение не
    // нуждается в прокси, транзакционность нужна только на записи результата.
    @Scheduled(cron = "${cron.expression}") // "30 30 * * * *"
    public void processJobRunning() {

        List<Receipt> receipts = receiptDao.getRefundReceipts();

        for (Receipt receipt : receipts) {
            // Раньше исключение из taxClient (или из БД) для одной квитанции прерывало весь
            // метод: он был целиком в одной @Transactional, поэтому падение на N-й квитанции
            // откатывало флаг processed=true, уже успевший закоммититься (в рамках этой же
            // транзакции) для квитанций 1..N-1 — хотя в налоговую они уже реально были
            // отправлены. Итог: при следующем запуске они отправлялись повторно (не страшно,
            // раз метод идемпотентный), а квитанции N+1..last в этом запуске вообще не
            // обрабатывались — одна "плохая" квитанция блокировала всю пачку без возможности
            // самостоятельно восстановиться (poison pill). Теперь каждая квитанция
            // обрабатывается независимо и в своей короткой транзакции — сбой на одной не
            // трогает остальные.
            try {
                processReceipt(receipt);
            } catch (Exception e) {
                log.error("Failed to process refund receipt {}", receipt.getId(), e);
            }
        }
    }

    private void processReceipt(Receipt receipt) {
        var receiptDto = new ReceiptDto(receipt.getId(), receipt.getSum());

        // Порядок операций был перевёрнут: в оригинале запись processed=true в БД шла ДО
        // фактической отправки в налоговую. Из-за этого возможен сценарий "приложение упало
        // между save() и idempotentSendReceipt()" — квитанция помечена обработанной, но в
        // налоговую так и не ушла, и это никогда не будет исправлено (следующий запуск её
        // уже не выберет, раз processed=true). Отправляем сначала: если сервис/приложение
        // упадёт до записи в БД, при следующем запуске квитанция найдётся снова и будет
        // отправлена ещё раз — это безопасно именно потому, что idempotentSendReceipt по
        // контракту идемпотентен (то же рассуждение, что и для callLender в
        // lending-application-service: внешний побочный эффект либо идемпотентен, либо
        // должен предшествовать необратимой фиксации локального состояния).
        taxClient.idempotentSendReceipt(receiptDto);

        // Вызов через self, а не через this: @Transactional — это Spring AOP поверх прокси
        // бина. Прямой вызов this.markProcessed(...) идёт мимо прокси (self-invocation),
        // и аннотация тихо не сработает — транзакция не откроется вообще, без единой ошибки
        // или предупреждения в логах. self — это инжектированный прокси того же бина, вызов
        // через него проходит полный AOP-стек.
        self.markProcessed(receipt);
    }

    // Транзакция теперь оборачивает только быструю работу с БД — ровно одну квитанцию,
    // без сетевого вызова внутри (в оригинале @Transactional висел на всём методе целиком,
    // включая цикл с HTTP-вызовами в налоговую — соединение с БД удерживалось на всё время
    // потенциально долгого похода в внешнюю систему для каждой квитанции в пачке).
    @Transactional
    public void markProcessed(Receipt receipt) {
        receipt.setProcessed(true);
        receiptDao.save(receipt);
    }
}
