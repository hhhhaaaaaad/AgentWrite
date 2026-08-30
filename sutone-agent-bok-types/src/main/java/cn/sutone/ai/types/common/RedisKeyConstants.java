package cn.sutone.ai.types.common;

public final class RedisKeyConstants {

    private RedisKeyConstants() {}

    /** AI 接口限流 per user */
    public static final String RATE_LIMIT_PREFIX = "ai:limit:";

    /** 文章详情缓存 */
    public static final String ARTICLE_DETAIL_PREFIX = "article:detail:";

    /** 文章列表缓存 */
    public static final String ARTICLE_PAGE_PREFIX = "article:page:";

    /** 文章缓存互斥锁（防击穿） */
    public static final String ARTICLE_LOCK_PREFIX = "article:lock:";

    /** AI 任务防重复提交锁 */
    public static final String AI_TASK_LOCK_PREFIX = "ai:task:lock:";

    /** AI 写作意图预检凭证 token */
    public static final String PRECHECK_TOKEN_PREFIX = "ai:precheck:token:";

    /** AI 写作意图预检结果缓存 */
    public static final String PRECHECK_CACHE_PREFIX = "ai:precheck:cache:";

    /** AI 写作意图预检接口限流 */
    public static final String PRECHECK_RATE_LIMIT_PREFIX = "ai:precheck:limit:";

    /** AI 写作意图预检模型二判限流 */
    public static final String PRECHECK_MODEL_RATE_LIMIT_PREFIX = "ai:precheck:model:limit:";

    /** 文章点赞用户集合 */
    public static final String ARTICLE_LIKE_PREFIX = "article:like:";

    /** 文章被收藏用户集合 */
    public static final String ARTICLE_FAVORITE_PREFIX = "article:favorite:";

    /** 用户收藏文章列表 */
    public static final String USER_FAVORITE_PREFIX = "user:favorite:";

    /** 用户点赞文章列表 */
    public static final String USER_LIKE_PREFIX = "user:like:";

    /** 热门排行榜 */
    public static final String LEADERBOARD_VIEW_PREFIX = "leaderboard:view:";

    /** 文章点赞数计数器 */
    public static final String ARTICLE_LIKE_COUNT_PREFIX = "article:like:cnt:";
    /** 文章收藏数计数器 */
    public static final String ARTICLE_FAVORITE_COUNT_PREFIX = "article:favorite:cnt:";

    /** 文章评论数计数器 */
    public static final String COMMENT_COUNT_PREFIX = "article:comment:count:";
    /** 评论点赞用户集合 */
    public static final String COMMENT_LIKE_PREFIX = "comment:like:";
    /** 评论点赞数计数器 */
    public static final String COMMENT_LIKE_COUNT_PREFIX = "comment:like:cnt:";
    /** 评论内容去重 */
    public static final String COMMENT_DUP_PREFIX = "comment:dup:";

    /** 用户粉丝集合 */
    public static final String USER_FOLLOWERS_PREFIX = "user:followers:";
    /** 用户关注集合 */
    public static final String USER_FOLLOWING_PREFIX = "user:following:";
    /** 粉丝数计数器 */
    public static final String USER_FOLLOWERS_COUNT_PREFIX = "user:follow:cnt:followers:";
    /** 关注数计数器 */
    public static final String USER_FOLLOWING_COUNT_PREFIX = "user:follow:cnt:following:";
}
