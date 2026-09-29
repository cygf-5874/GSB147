spscqueue 是 Java 17 的单生产者单消费者环形队列，仅使用 JDK 标准库。README「对外契约」有 10 条；SpscQueue.java 是待审实现。

任务：通读实现并把问题写进 REVIEW.md 六节，覆盖内存可见性、批量发布、满空与回绕、容量换算、阻塞复核和失败路径。不要改源码。

验收：构建成功；既有用例仍全绿；bash scripts/check.sh 退出码 0，10 个场景全过（review 6 + evidence 2 + barrier 2）；scripts/barrier.sh 可复现并发结论。

约束：
1. 不改 SpscQueue.java、既有用例和 check/；只写 REVIEW.md。
2. 每条结论必须包含具体文件行区间、可复现调用或栅栏调度、失败后果与修复方向。
3. 不得用“跑了很多次”“看起来很危险”等不可核对表述。
4. 只用 JDK，不引入 Maven/Gradle；统计串与序号回绕必须自洽。
