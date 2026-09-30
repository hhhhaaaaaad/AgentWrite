package cn.sutone.ai.test.integration;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 集成测试基础设施可用性探测。
 *
 * <p>用于 {@code @EnabledIf} 门控：基础设施（docker-compose-environment.yml）未启动时
 * 集成测试整体跳过，而不是以连接超时失败告终——保证无基础设施的环境下 {@code mvn test} 仍可跑通单元测试。</p>
 */
public final class EvalInfra {

    private EvalInfra() {
    }

    /** MySQL 13306（docker-local 数据源）是否可达 */
    public static boolean mysqlAvailable() {
        return reachable("127.0.0.1", 13306);
    }

    /** Qdrant 6333 是否可达 */
    public static boolean qdrantAvailable() {
        return reachable("127.0.0.1", 6333);
    }

    /** MySQL 与 Qdrant 均可达（fencing / seed / reset 集成测试前置条件） */
    public static boolean mysqlAndQdrantAvailable() {
        return mysqlAvailable() && qdrantAvailable();
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
