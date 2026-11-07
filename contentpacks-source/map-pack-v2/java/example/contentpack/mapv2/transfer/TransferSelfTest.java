package example.contentpack.mapv2.transfer;

import java.util.UUID;

/**
 * 分块传输的纯逻辑自检：启动时与离线反射 harness 都跑这一份。
 *
 * <p>只断言协议不变量，不依赖世界/网络；失败抛 {@link AssertionError}，
 * 由调用方记 ERROR（validate 脚本据此判 FAIL）。
 */
public final class TransferSelfTest {

    private TransferSelfTest() {
    }

    public static void run() {
        roundtrip(1);
        roundtrip(BlobChunker.CHUNK_BYTES);
        roundtrip(BlobChunker.CHUNK_BYTES + 1);
        roundtrip(100_000);
        rejections();
        timeout();
        budget();
    }

    private static void roundtrip(int size) {
        byte[] blob = pattern(size);
        int parts = BlobChunker.parts(size);
        require(parts > 0, "parts must be positive for size " + size);
        BlobAssembler assembler = new BlobAssembler();
        UUID id = UUID.randomUUID();
        BlobAssembler.Result result = null;
        for (int part = 0; part < parts; part++) {
            result = assembler.accept(id, part, parts, size, BlobChunker.part(blob, part),
                    1_000L);
            require(result.status() != BlobAssembler.Status.REJECTED,
                    "roundtrip rejected at " + part + ": " + result.error());
        }
        require(result != null && result.status() == BlobAssembler.Status.COMPLETE,
                "roundtrip did not complete for size " + size);
        require(java.util.Arrays.equals(blob, result.data()),
                "roundtrip bytes differ for size " + size);
        require(assembler.inFlight() == 0, "assembler must release finished transfer");
    }

    private static void rejections() {
        BlobAssembler assembler = new BlobAssembler();
        byte[] data = pattern(64);
        UUID id = UUID.randomUUID();

        expectRejected(assembler.accept(id, 3, 3, 192, data, 1L), "part out of range");
        expectRejected(assembler.accept(id, 0, 3, 0, data, 1L), "zero blob");
        expectRejected(assembler.accept(id, 0, 3, BlobChunker.MAX_BLOB_BYTES + 1, data, 1L),
                "oversized blob");
        expectRejected(assembler.accept(id, 0, 3, 192, new byte[BlobChunker.CHUNK_BYTES + 1], 1L),
                "oversized part");
        require(assembler.inFlight() == 0, "rejected parts must not start a transfer");

        BlobAssembler.Result first = assembler.accept(id, 0, 3, 192, data, 1L);
        require(first.status() == BlobAssembler.Status.INCOMPLETE, "first part must be accepted");
        expectRejected(assembler.accept(id, 0, 3, 192, data, 1L), "duplicate part");
        require(assembler.inFlight() == 0, "a rejected transfer must be dropped");

        BlobAssembler.Result second = assembler.accept(id, 0, 3, 192, data, 1L);
        require(second.status() == BlobAssembler.Status.INCOMPLETE, "fresh transfer must start");
        expectRejected(assembler.accept(id, 1, 4, 192, data, 1L), "metadata changed");
        require(assembler.inFlight() == 0, "metadata mismatch must drop the transfer");
    }

    private static void timeout() {
        BlobAssembler assembler = new BlobAssembler(2, 100L);
        byte[] data = pattern(32);
        UUID id = UUID.randomUUID();
        require(assembler.accept(id, 0, 2, 64, data, 0L).status()
                == BlobAssembler.Status.INCOMPLETE, "first part accepted");
        require(assembler.expire(99L) == 0, "not expired yet");
        require(assembler.expire(100L) == 1, "must expire at deadline");
        require(assembler.inFlight() == 0, "expired transfer must be dropped");
        require(assembler.accept(id, 1, 2, 64, data, 200L).status()
                == BlobAssembler.Status.INCOMPLETE, "expired id can restart");
    }

    private static void budget() {
        TransferBudget budget = new TransferBudget(1000);
        require(budget.tryTake(1000, 0L), "burst must allow one second worth");
        require(!budget.tryTake(1, 0L), "empty bucket must refuse");
        require(budget.tryTake(500, 500_000_000L), "half a second must refill 500 bytes");
        require(!budget.tryTake(600, 500_000_000L), "cannot take more than refilled");
        require(budget.tryTake(500, 1_500_000_000L), "another half second refills");
    }

    private static void expectRejected(BlobAssembler.Result result, String what) {
        require(result.status() == BlobAssembler.Status.REJECTED,
                what + " must be rejected, was " + result.status());
    }

    private static byte[] pattern(int size) {
        byte[] blob = new byte[size];
        for (int i = 0; i < size; i++) {
            blob[i] = (byte) (i * 31);
        }
        return blob;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
