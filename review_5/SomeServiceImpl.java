

@Service
@RequiredArgsConstructor
public class SomeServiceImpl implements SomeService {

    private final TaxService taxService;
    private final ReceiptDao dao;

    @Scheduled(cron = "30 30 * * * *")
    @Override
    @Transactional
    public void processJob() {
        List<Receipt> receipts = dao.findAll();
        for (Receipt r : receipts) {
            r.setProcessed(true);
        }

        taxService.sendTax(r);
    }

    @Transactional(readOnly = true)
    public List<Receipt> findAll() {
        return dao.findAll();
    }
}