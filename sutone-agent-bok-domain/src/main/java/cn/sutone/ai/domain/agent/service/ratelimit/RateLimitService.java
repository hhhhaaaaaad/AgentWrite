package cn.sutone.ai.domain.agent.service.ratelimit;

import cn.sutone.ai.types.common.RedisKeyConstants;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private static final long RATE = 5;
    private static final long RATE_INTERVAL = 1;

    private final RedissonClient redissonClient;

    public RateLimitService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public boolean tryAcquire(Long userId) {
        RRateLimiter limiter = redissonClient.getRateLimiter(RedisKeyConstants.RATE_LIMIT_PREFIX + userId);
        // 每用户每分钟最多 5 次 AI 调用
        limiter.trySetRate(RateType.OVERALL, RATE, RATE_INTERVAL, RateIntervalUnit.MINUTES);
        return limiter.tryAcquire();
    }
}
