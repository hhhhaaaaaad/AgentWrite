package cn.sutone.ai.trigger.security;

import java.time.Duration;

/**
 * 评测凭据（{@code role=EVAL}）签发工具 —— 离线 CLI，不暴露任何 HTTP 端点。
 *
 * <h2>为什么需要它</h2>
 * 评测平台（eval-platform）以 {@code Authorization: Bearer} 调用 {@code /api/v1/eval/**}，
 * 而该路径在 {@code SecurityConfig} 里要求 {@code hasRole("EVAL")}。
 * 但系统里<b>没有任何代码路径会签发带 role 的 token</b>：唯一的签发点是
 * {@code AuthController.login}，它调用的是二参 {@code generateToken(userId, username)}
 * （该重载把 role 传成 null）。于是「给评测平台配一个 token」这件事本身无处落地——
 * 本工具补的就是这个缺口。
 *
 * <h2>为什么是 CLI 而不是一个签发端点</h2>
 * 一个「按需签发任意角色 token」的 HTTP 端点，等价于在认证边界上开一个后门：
 * 它必须自己再做一套鉴权，否则任何能访问它的人都能拿到 EVAL 凭据。
 * 评测凭据的使用者是<b>运维/开发本人</b>、频率是「部署时签一次」，
 * 完全不需要在线服务——CLI 用零攻击面换到了同样的能力。
 *
 * <h2>用法</h2>
 * <pre>
 * mvn -q -pl sutone-agent-bok-trigger exec:java \
 *     -Dexec.mainClass=cn.sutone.ai.trigger.security.EvalTokenCli \
 *     -Dexec.args="--expires-days 30 --format env"
 * </pre>
 *
 * <p>签出来的 token 填进 eval-platform 的 {@code java_eval_token}。
 * 密钥必须与 AgentWrite 运行实例的 {@code jwt.secret} 一致
 * （即环境变量 {@code JWT_SECRET}，未设置时为本项目配置文件里的默认值）。</p>
 *
 * <h2>安全约束</h2>
 * <ul>
 *   <li>token 是<b>长期凭据</b>，等同密码：只应进 {@code .env} 或密钥管理，
 *       <b>绝不入库</b>（本仓库的 {@code .gitignore} 已排除 {@code .env}）；</li>
 *   <li>使用默认密钥时本工具会打印显式告警——默认密钥在仓库里是公开的，
 *       照签出的 token 对任何读过本仓库的人都有效；</li>
 *   <li>轮换方式：重新签一个、更新 {@code java_eval_token}、重启平台进程。
 *       旧 token 无需吊销——它会随过期时间自然失效。</li>
 * </ul>
 */
public final class EvalTokenCli {

    /**
     * 与 {@code application.yml} / {@code application-eval.yml} 里
     * {@code jwt.secret: ${JWT_SECRET:...}} 的兜底值保持一致。
     * 不一致会导致签出的 token 全部验签失败，故这里显式对齐并加注释钉住。
     */
    static final String DEFAULT_SECRET = "sutone-agent-bok-jwt-secret-key-2026";

    private static final String DEFAULT_USERNAME = "eval-platform";

    /**
     * 默认有效期 30 天。
     *
     * <p>远长于普通登录 token 的 2 小时，因为使用场景不同：登录 token 面向人类会话，
     * 短有效期是安全收益；评测凭据是<b>机器凭据</b>，每次评测都要人工换一次 token
     * 会让「跑评测」这件事从自动化退化成手工操作。真正的防护手段是它只存在于
     * 服务端配置里（不经浏览器、不进日志），而不是靠短过期。</p>
     */
    private static final int DEFAULT_EXPIRES_DAYS = 30;

    /**
     * token 的 subject。
     *
     * <p><b>评测端点不读 principal</b>——{@code MemoryEvalController} 的每个方法都从
     * 请求体取 {@code evalUserId} 并按评测命名空间区间校验，认证主体的作用仅限于
     * 「证明调用方持有 EVAL 凭据」。因此这里用一个明确的哨兵值，而不是挑一个真实用户 id
     * 去冒充。用 0 是为了让任何按 id 反查用户的日志一眼看出「这不是用户，是平台凭据」。</p>
     */
    private static final long DEFAULT_USER_ID = 0L;

    private static final String ROLE_EVAL = "EVAL";

    private EvalTokenCli() {
    }

    public static void main(String[] args) {
        String secret = option(args, "--secret", System.getenv("JWT_SECRET"), DEFAULT_SECRET);
        int expiresDays = Integer.parseInt(
                option(args, "--expires-days", null, String.valueOf(DEFAULT_EXPIRES_DAYS)));
        String username = option(args, "--username", null, DEFAULT_USERNAME);
        long userId = Long.parseLong(option(args, "--user-id", null, String.valueOf(DEFAULT_USER_ID)));
        String format = option(args, "--format", null, "plain");

        if (expiresDays <= 0) {
            fail("--expires-days 必须为正数，实际: " + expiresDays);
        }

        JwtTokenProvider provider = JwtTokenProvider.forSecret(
                secret, Duration.ofDays(expiresDays).toMillis());
        String token = provider.generateToken(userId, username, ROLE_EVAL);

        // 自检：签完立刻用同一个实例验一遍。密钥/算法若有不一致，错误在这里暴露，
        // 而不是等到几十分钟后在评测平台上表现为一个无从下手的 403。
        if (!provider.validateToken(token)) {
            fail("自检失败：签出的 token 无法被自身校验通过（密钥或算法配置异常）");
        }
        String parsedRole = provider.getRoleFromToken(token);
        if (!ROLE_EVAL.equals(parsedRole)) {
            fail("自检失败：role 声明为 " + parsedRole + "，期望 " + ROLE_EVAL);
        }

        printBanner(secret, expiresDays, userId, username);
        if ("env".equalsIgnoreCase(format)) {
            System.out.println("JAVA_EVAL_TOKEN=" + token);
        } else {
            System.out.println(token);
        }
    }

    private static void printBanner(String secret, int expiresDays, long userId, String username) {
        // 所有提示走 stderr：stdout 只保留 token 本身，
        // 这样 `... | clip` 或 `> token.txt` 拿到的就是干净的凭据，不必再剪裁。
        System.err.println("---------------------------------------------------------------");
        System.err.println("  评测凭据（role=EVAL）签发成功");
        System.err.println("  subject   : userId=" + userId + "  username=" + username);
        System.err.println("  有效期    : " + expiresDays + " 天");
        System.err.println("  验签自检  : 通过");
        if (DEFAULT_SECRET.equals(secret)) {
            System.err.println();
            System.err.println("  [警告] 正在使用仓库内置的默认 jwt.secret。");
            System.err.println("         该密钥是公开的（就在本项目配置文件里），");
            System.err.println("         据此签出的 token 对任何读过本仓库的人都有效。");
            System.err.println("         仅限本地开发；任何共享/线上环境请先设置 JWT_SECRET。");
        }
        System.err.println();
        System.err.println("  下一步：把下面这行写进 eval-platform 的 backend/.env");
        System.err.println("          （该文件已被 .gitignore 排除，勿把 token 提交进任何仓库）");
        System.err.println("---------------------------------------------------------------");
    }

    /**
     * 取命令行选项：{@code --key value}，缺失时回退到 {@code fallback}。
     *
     * <p>刻意不引第三方参数解析库——本工具只有四个选项，且必须在
     * 「只编译 trigger 模块」的条件下可运行。</p>
     */
    private static String option(String[] args, String key, String fallback, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return defaultValue;
    }

    private static void fail(String message) {
        System.err.println("[FAIL] " + message);
        System.exit(1);
    }
}
