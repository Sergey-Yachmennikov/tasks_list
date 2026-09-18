
class ProcessJobOriginal {

    @Override
    @Transactional
    @Scheduled(cron = "${cron.expression}") // "30 30 * * * *"
    public void processJobRunning() {

        List<Receipt> receipts = self.getRefundReceipts();

        for (Receipt receipt : receipts) {

            receipt.setProcessed(true);
            receiptDao.save(receipt);

            var receiptDto =
                    new ReceiptDto(
                            receipt.getId(),
                            receipt.getSum()
                    );

            taxClient.idempotentSendReceipt(receiptDto);
        }
    }
}