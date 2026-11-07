package example.contentpack.mapv2.transfer;

/**
 * 令牌桶：限制每个接收者每 tick 能拿走多少字节。纯逻辑。
 *
 * <p>调用方按 tick 传 {@code nowNanos}；桶按时间补满，突发上限是
 * {@code bytesPerSecond}（一秒的量攒着一次花），避免一帧发出整块瓦片。
 */
public final class TransferBudget {

    private final long bytesPerSecond;
    private final double capacity;
    private double tokens;
    private long lastNanos;
    private boolean started;

    public TransferBudget(long bytesPerSecond) {
        this.bytesPerSecond = Math.max(1, bytesPerSecond);
        this.capacity = this.bytesPerSecond;
        this.tokens = this.capacity;
    }

    /** 够就扣掉并返回 true；不够保持原样，下一 tick 再试。 */
    public boolean tryTake(int bytes, long nowNanos) {
        refill(nowNanos);
        if (bytes <= 0) {
            return true;
        }
        if (tokens + 1e-9 < bytes) {
            return false;
        }
        tokens -= bytes;
        return true;
    }

    public long bytesPerSecond() {
        return bytesPerSecond;
    }

    private void refill(long nowNanos) {
        if (!started) {
            started = true;
            lastNanos = nowNanos;
            return;
        }
        long elapsed = nowNanos - lastNanos;
        if (elapsed <= 0L) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsed * (double) bytesPerSecond / 1e9);
        lastNanos = nowNanos;
    }
}
