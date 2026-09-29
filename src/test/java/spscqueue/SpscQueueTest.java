package spscqueue;

/**
 * 既有用例：13 个，全部只跑单线程、小容量、非满路径。
 * 它们验证「实现能编译、单线程基础语义正确」，并不覆盖并发、填满、非 2 的幂容量、
 * 伪唤醒、失败路径副作用等场景——这些正是审查要找出来的。
 *
 * <p>运行：bash scripts/test.sh（内部先构建，再执行本类 main）。退出码 0 表示 13/13 全绿。
 */
public final class SpscQueueTest {

    private static int passed = 0;

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("断言失败: " + msg);
        }
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("断言失败: " + msg + "  期望=" + expected + " 实际=" + actual);
        }
    }

    private static void section(String name) {
        passed++;
        System.out.println("PASS " + name);
    }

    // ---- 13 个用例，全部单线程、非满、容量为 2 的幂 ----

    private static void testConstructorAndBasic() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        assertTrue(q.size() == 0, "初始 size 应为 0");
        assertTrue(q.isEmpty(), "初始应为空");
        assertTrue(!q.isFull(), "初始不应为满");
        assertTrue(q.offer(1), "offer 应返回 true");
        assertEquals(q.poll(), 1, "poll 应返回 1");
        section("constructorAndBasic");
    }

    private static void testFifoOrder() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        q.offer(1);
        q.offer(2);
        q.offer(3);
        q.offer(4);
        assertEquals(q.poll(), 1, "FIFO 第 1 个");
        assertEquals(q.poll(), 2, "FIFO 第 2 个");
        assertEquals(q.poll(), 3, "FIFO 第 3 个");
        assertEquals(q.poll(), 4, "FIFO 第 4 个");
        assertEquals(q.poll(), null, "空队列 poll 应返回 null");
        section("fifoOrder");
    }

    private static void testSizeAfterOps() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        q.offer(1);
        q.offer(2);
        q.offer(3);
        assertTrue(q.size() == 3, "offer 3 后 size 应为 3");
        q.poll();
        assertTrue(q.size() == 2, "poll 1 后 size 应为 2");
        q.offer(4);
        assertTrue(q.size() == 3, "再 offer 1 后 size 应为 3");
        section("sizeAfterOps");
    }

    private static void testOfferReturnValue() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        assertTrue(q.offer(1), "首次 offer 应返回 true");
        assertTrue(q.offer(2), "二次 offer 应返回 true");
        section("offerReturnValue");
    }

    private static void testPollEmptyNull() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        assertEquals(q.poll(), null, "空队列 poll 应返回 null");
        section("pollEmptyNull");
    }

    private static void testBatchBasic() {
        SpscQueue<Integer> q = new SpscQueue<>(16);
        Integer[] a = {10, 20, 30};
        assertTrue(q.offerBatch(a, 3), "offerBatch 应返回 true");
        assertEquals(q.poll(), 10, "批量第 1 个");
        assertEquals(q.poll(), 20, "批量第 2 个");
        assertEquals(q.poll(), 30, "批量第 3 个");
        assertTrue(q.size() == 0, "取空后 size 应为 0");
        section("batchBasic");
    }

    private static void testBatchSize() {
        SpscQueue<Integer> q = new SpscQueue<>(16);
        Integer[] a = {1, 2, 3, 4};
        q.offerBatch(a, 4);
        assertTrue(q.size() == 4, "批量 4 后 size 应为 4");
        section("batchSize");
    }

    private static void testPollBlockingWithElement() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        q.offer(99);
        try {
            assertEquals(q.pollBlocking(), 99, "pollBlocking 有元素时应立即返回");
        } catch (InterruptedException e) {
            throw new AssertionError("不应被中断", e);
        }
        section("pollBlockingWithElement");
    }

    private static void testWraparoundPartial() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        for (int k = 0; k < 6; k++) {
            q.offer(k);
        }
        for (int k = 0; k < 4; k++) {
            assertEquals(q.poll(), k, "先取前 4 个");
        }
        q.offer(60);
        q.offer(70);
        assertTrue(q.size() == 4, "绕回后 size 应为 4");
        assertEquals(q.poll(), 4, "绕回第 1 个应为 4");
        assertEquals(q.poll(), 5, "绕回第 2 个应为 5");
        assertEquals(q.poll(), 60, "绕回第 3 个应为 60");
        assertEquals(q.poll(), 70, "绕回第 4 个应为 70");
        section("wraparoundPartial");
    }

    private static void testStatsSelfConsistent() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        q.offer(1);
        q.offer(2);
        q.offer(3);
        q.poll();
        String s = q.stats();
        // 解析 offered / polled / size
        long offered = parseField(s, "offered=");
        long polled = parseField(s, "polled=");
        long size = parseField(s, "size=");
        assertTrue(offered - polled == size, "stats 自洽: offered - polled == size");
        section("statsSelfConsistent");
    }

    private static long parseField(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            throw new AssertionError("stats 缺少 " + key);
        }
        int j = i + key.length();
        int end = j;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        return Long.parseLong(s.substring(j, end));
    }

    private static void testIsEmptyInitially() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        assertTrue(q.isEmpty(), "初始应为空");
        assertTrue(q.size() == 0, "初始 size 应为 0");
        assertTrue(!q.isFull(), "初始不应为满");
        section("isEmptyInitially");
    }

    private static void testIsFullNonFull() {
        SpscQueue<Integer> q = new SpscQueue<>(8);
        q.offer(1);
        q.offer(2);
        q.offer(3);
        q.offer(4);
        assertTrue(!q.isFull(), "半满不应判为满");
        assertTrue(q.size() == 4, "半满 size 应为 4");
        section("isFullNonFull");
    }

    private static void testMultipleBatches() {
        SpscQueue<Integer> q = new SpscQueue<>(16);
        Integer[] a = {1, 2};
        Integer[] b = {3, 4};
        q.offerBatch(a, 2);
        q.offerBatch(b, 2);
        assertTrue(q.size() == 4, "两次批量后 size 应为 4");
        assertEquals(q.poll(), 1, "第 1 个");
        assertEquals(q.poll(), 2, "第 2 个");
        assertEquals(q.poll(), 3, "第 3 个");
        assertEquals(q.poll(), 4, "第 4 个");
        section("multipleBatches");
    }

    public static void main(String[] args) {
        int total = 13;
        Runnable[] tests = {
            SpscQueueTest::testConstructorAndBasic,
            SpscQueueTest::testFifoOrder,
            SpscQueueTest::testSizeAfterOps,
            SpscQueueTest::testOfferReturnValue,
            SpscQueueTest::testPollEmptyNull,
            SpscQueueTest::testBatchBasic,
            SpscQueueTest::testBatchSize,
            SpscQueueTest::testPollBlockingWithElement,
            SpscQueueTest::testWraparoundPartial,
            SpscQueueTest::testStatsSelfConsistent,
            SpscQueueTest::testIsEmptyInitially,
            SpscQueueTest::testIsFullNonFull,
            SpscQueueTest::testMultipleBatches,
        };
        int failed = 0;
        for (Runnable t : tests) {
            try {
                t.run();
            } catch (AssertionError e) {
                failed++;
                System.out.println("FAIL " + t + "  " + e.getMessage());
            } catch (Throwable e) {
                failed++;
                System.out.println("ERROR " + t + "  " + e);
            }
        }
        int ok = total - failed;
        System.out.println("结果：通过 " + ok + "/" + total);
        if (failed > 0) {
            System.exit(1);
        }
        System.exit(0);
    }
}
