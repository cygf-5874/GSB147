package spscqueue;

/**
 * 队列相关异常的基类。构造非法参数（例如非 2 的幂的容量）时抛出。
 */
public class QueueException extends RuntimeException {

    public QueueException(String message) {
        super(message);
    }
}
