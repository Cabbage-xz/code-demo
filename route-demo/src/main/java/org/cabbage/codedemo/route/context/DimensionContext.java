package org.cabbage.codedemo.route.context;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 存储当前请求的维度信息（dimensionKey + tableName）
 * 必须在请求结束后调用 clear()，防止线程池复用导致数据污染
 */
public class DimensionContext {

    private static final ThreadLocal<String> DIMENSION_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> TABLE_NAME    = new ThreadLocal<>();

    public static void set(String dimensionKey, String tableName) {
        DIMENSION_KEY.set(dimensionKey);
        TABLE_NAME.set(tableName);
    }

    public static String getDimensionKey() {
        return DIMENSION_KEY.get();
    }

    public static String getTableName() {
        return TABLE_NAME.get();
    }

    public static void clear() {
        DIMENSION_KEY.remove();
        TABLE_NAME.remove();
    }
}
