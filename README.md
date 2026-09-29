# spscqueue · 单生产者单消费者无锁环形队列

`SpscQueue` 是一个 Java 17 的 SPSC（单写单读）无锁环形队列实现，**仅用 JDK 标准库**，
不依赖任何第三方包，也不使用 Maven / Gradle。构建与自检都靠 `bash scripts/*.sh`。

> 本仓库是一个**代码理解 / 审查**题：`SpscQueue.java` 是待审实现，`REVIEW.md` 是待填写的
> 审查模板。你不需要改代码，只要把违反契约的地方写进 `REVIEW.md`。

## 对外契约（10 条）

1. `SpscQueue(int capacity)`：`capacity` 必须是 **2 的幂**，否则抛 `QueueException`；
   槽位下标一律用 `seq & (capacity - 1)`，不许用 `%`。
2. **单写单读**：一个生产者线程 `offer`、一个消费者线程 `poll`；并发下不得丢元素、
   不得重复、顺序严格 FIFO。
3. **内存可见性**：`head`/`tail` 与槽位数组之间必须有 acquire/release 语义
   （`volatile` 字段 + 手写屏障说明，或 `VarHandle` 的 `setRelease`/`getAcquire`）；
   生产者写槽位 → **发布**序号；消费者读序号 → **再**读槽位，顺序不许颠倒。
4. **批量发布**：`offerBatch(T[] src, int n)` 必须先把 n 个槽位写完，再**一次**发布 `tail`；
   消费者看到的已发布前缀必须是完整写入的前缀（不许出现「序号已推进、槽位还是旧值/零值」）。
5. **满/空判定**：`size()` 必须区分满与空 —— 队列填满时 `size() == capacity`（不得报 0），
   `offer` 在满时返回 `false`。
6. **失败路径无副作用**：满队列 `offer` 返回 `false` 后，已入队元素的内容与顺序不得被改写，
   `size()` 不变。
7. **序号回绕**：`head`/`tail` 是单调 `long`；`size() == tail - head`，跨任意次回绕后仍正确
   （连续 offer/poll 3×capacity + 7 轮后 `size()` 与 FIFO 顺序必须都对）。
8. **阻塞等待**：`pollBlocking()` 被 `LockSupport.unpark` 唤醒后必须**复核条件**（`while` 而非 `if`），
   伪唤醒（无元素时被 unpark）不得返回 `null` 或脏值。
9. `stats()` 是固定格式 Golden 字符串，自洽：`offered - polled == size()`。
10. 并发判据必须可用**栅栏**稳定复现（`CyclicBarrier` 凑齐 2 个线程 + `-Xint`），
    不许用「跑了多少次」「放行数」这类会摆动的量当证据。

## 怎么跑

```bash
# 构建
bash scripts/build.sh

# 既有用例（13 个，单线程路径，起点应全绿）
bash scripts/test.sh

# 固定验收（check/ 是固定件，勿改）
bash scripts/check.sh                 # 跑全部 10 个场景
bash scripts/check.sh --only review   # 只跑 review 组
bash scripts/check.sh -list           # 列出全部场景名

# 并发判据自身可复现性的栅栏自测（check/ 自带最小正确对照实现）
bash scripts/barrier.sh
```

## 目录结构

```
src/main/java/spscqueue/SpscQueue.java   # 待审实现
src/main/java/spscqueue/QueueException.java
src/test/java/spscqueue/SpscQueueTest.java  # 13 个既有用例（单线程）
check/Checker.java                        # 固定验收程序（勿改）
check/ReferenceImpl.java                  # 最小正确对照实现（仅供栅栏自测，非参考答案）
REVIEW.md                                 # 待填写的审查模板（交付物）
README.md / PROMPT.md
scripts/build.sh / test.sh / check.sh / barrier.sh
```
