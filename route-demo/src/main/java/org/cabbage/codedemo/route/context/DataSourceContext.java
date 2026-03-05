package org.cabbage.codedemo.route.context;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * ThreadLocal 管理当前线程的数据源 key
 */
public class DataSourceContext {

    private static final ThreadLocal<String> DATA_SOURCE_KEY = new ThreadLocal<>();

    public static void set(String key) {
        DATA_SOURCE_KEY.set(key);
    }

    public static String get() {
        return DATA_SOURCE_KEY.get();
    }

    public static void clear() {
        DATA_SOURCE_KEY.remove();
    }
}
