import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * 最小正确对照实现（仅存在于 check/ 与脚本里，不是参考答案）。
 *
 * <p>本类只用来自证「固定件里的并发判据可被栅栏稳定复现」：在 {@code -Xint} 下，
 * 用 {@link CyclicBarrier} 把生产者与消费者两个线程凑齐后再放行，生产者「先写完槽位、
 * 再一次发布尾序号」，消费者自旋等尾序号推进后校验「已发布前缀」里每个槽位都已是新值、
 * 没有任何零值/旧值。对照实现语义正确，因此 200 轮全部稳定通过——证明判据本身是确定性的，
 * 不依赖跑了多少次。固定件 {@code barrier} 组的两条场景只跑这一类自测。
 *
 * <p>它刻意只覆盖「发布顺序 + 获取/释放语义」这一条主线，不实现本题要审查的其余契约，
 * 也完全不读 {@code SpscQueue.java}，因此修改 SpscQueue 不会影响这里的自测结果。
 */
public final class ReferenceImpl {

    private final int capacity;
    private final Object[] slots;

    private volatile long head;
    private volatile long tail;

    private static final VarHandle HEAD;
    private static final VarHandle TAIL;
    private static final VarHandle SLOT;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            HEAD = l.findVarHandle(ReferenceImpl.class, "head", long.class);
            TAIL = l.findVarHandle(ReferenceImpl.class, "tail", long.class);
            SLOT = MethodHandles.arrayElementVarHandle(Object[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public ReferenceImpl(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity 必须是 2 的幂");
        }
        this.capacity = capacity;
        this.slots = new Object[capacity];
    }

    private int idx(long seq) {
        return (int) (seq & (capacity - 1));
    }

    public boolean offer(Object e) {
        long t = tail;
        if (t - head >= capacity) {
            return false;
        }
        int i = idx(t);
        SLOT.setRelease(slots, i, e);
        TAIL.setRelease(this, t + 1);
        return true;
    }

    public Object poll() {
        long h = head;
        if (h >= tail) {
            return null;
        }
        int i = idx(h);
        Object v = SLOT.getAcquire(slots, i);
        SLOT.setRelease(slots, i, null);
        HEAD.setRelease(this, h + 1);
        return v;
    }

    public boolean offerBatch(Object[] src, int n) {
        long t = tail;
        if (t - head + n > capacity) {
            return false;
        }
        for (int k = 0; k < n; k++) {
            SLOT.setRelease(slots, idx(t + k), src[k]);
        }
        TAIL.setRelease(this, t + n);
        return true;
    }

    public int size() {
        return (int) (tail - head);
    }

    public Object pollBlocking() {
        while (head >= tail) {
            LockSupport.park();
        }
        int i = idx(head);
        Object v = SLOT.getAcquire(slots, i);
        SLOT.setRelease(slots, i, null);
        HEAD.setRelease(this, head + 1);
        return v;
    }

    /** 仅用于自测的可见性读取（volatile 读，等价于 acquire）。 */
    public long observedTail() {
        return tail;
    }

    /**
     * 栅栏场景：「已发布前缀合法性」。
     *
     * <p>生产者先写后发布；消费者自旋等尾序号推进到 {@code head + n}，再逐个 poll 校验
     * 取到的就是生产者写入的那批值。对照实现语义正确时，任意一轮都不会读到零值/旧值。
     *
     * @return 所有轮次前缀均合法返回 true，否则 false
     */
    public static boolean runPrefixLegality(int rounds) throws Exception {
        final int cap = 4;
        for (int r = 0; r < rounds; r++) {
            final ReferenceImpl q = new ReferenceImpl(cap);
            final int n = 1 + (r % cap); // 1..4
            final Integer[] data = new Integer[n];
            for (int k = 0; k < n; k++) {
                data[k] = r * 100 + k;
            }
            final CyclicBarrier gate = new CyclicBarrier(2);
            final AtomicBoolean ok = new AtomicBoolean(true);

            Thread producer = new Thread(() -> {
                try {
                    gate.await();
                    if (!q.offerBatch(data, n)) {
                        ok.set(false);
                    }
                } catch (Exception e) {
                    ok.set(false);
                }
            });
            Thread consumer = new Thread(() -> {
                try {
                    gate.await();
                    // 自旋等尾序号推进（acquire 语义），再校验已发布前缀
                    long target = n; // head 初始为 0
                    while (q.observedTail() < target) {
                        Thread.onSpinWait();
                    }
                    for (int k = 0; k < n; k++) {
                        Object v = q.poll();
                        if (v == null || !v.equals(data[k])) {
                            ok.set(false);
                        }
                    }
                } catch (Exception e) {
                    ok.set(false);
                }
            });
            producer.start();
            consumer.start();
            producer.join();
            consumer.join();
            if (!ok.get()) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        boolean ok = runPrefixLegality(rounds);
        System.out.println("prefix-legality rounds=" + rounds + " ok=" + ok);
        System.exit(ok ? 0 : 1);
    }
}
