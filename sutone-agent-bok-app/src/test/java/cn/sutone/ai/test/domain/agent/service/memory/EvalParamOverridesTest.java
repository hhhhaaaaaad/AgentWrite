package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.model.valobj.EvalParamOverrides;
import cn.sutone.ai.domain.agent.model.valobj.RetrieverParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 参数覆盖的合并语义。
 *
 * <p>这层合并看起来「只是几个三元表达式」，但它是「调参对照实验成不成立」的分水岭：
 * 合并错了会导致两类相反的失败——覆盖了不该覆盖的（动了别的维度，实验不再单变量），
 * 或没覆盖该覆盖的（参数没传到底，退化成改造前那个「只变指纹不变行为」的缺陷）。
 * 两种都不会抛异常，只会在几个 run 之后的指标里以「解释不了的差异」形式出现。</p>
 */
@DisplayName("EvalParamOverrides 合并语义")
class EvalParamOverridesTest {

    /** 服务端配置的「当前值」，覆盖要盖在它上面。 */
    private static final RetrieverParams BASE =
            new RetrieverParams(60, 0.1, 0.1, 30.0, 0.15, 0.0, 800);

    @Test
    @DisplayName("NONE 全字段不覆盖：生效参数逐位等于基线")
    void noneLeavesBaselineUntouched() {
        assertTrue(EvalParamOverrides.NONE.isEmpty());

        RetrieverParams resolved = EvalParamOverrides.NONE.resolve(BASE);

        // 逐字段比而不是比对象：record 的 equals 会在任一字段不同时失败，
        // 但失败信息只说「不相等」，看不出是哪个参数被动了。
        assertEquals(BASE.rrfK(), resolved.rrfK());
        assertEquals(BASE.alpha(), resolved.alpha());
        assertEquals(BASE.beta(), resolved.beta());
        assertEquals(BASE.recencyHalfLifeDays(), resolved.recencyHalfLifeDays());
        assertEquals(BASE.profileBoost(), resolved.profileBoost());
        assertEquals(BASE.minConfidence(), resolved.minConfidence());
        assertEquals(BASE.injectMaxTokens(), resolved.injectMaxTokens());
    }

    @Test
    @DisplayName("只覆盖一个字段时，其余六个原样来自基线——这是单变量对照的前提")
    void singleFieldOverrideLeavesOthersFromBaseline() {
        EvalParamOverrides overrides =
                new EvalParamOverrides(null, 0.5, null, null, null, null, null);

        assertFalse(overrides.isEmpty());
        RetrieverParams resolved = overrides.resolve(BASE);

        assertEquals(0.5, resolved.alpha(), "被覆盖的字段应取覆盖值");
        assertEquals(BASE.rrfK(), resolved.rrfK(), "未覆盖字段必须保持基线，不能退化成某个默认值");
        assertEquals(BASE.beta(), resolved.beta());
        assertEquals(BASE.recencyHalfLifeDays(), resolved.recencyHalfLifeDays());
        assertEquals(BASE.profileBoost(), resolved.profileBoost());
        assertEquals(BASE.minConfidence(), resolved.minConfidence());
        assertEquals(BASE.injectMaxTokens(), resolved.injectMaxTokens());
    }

    @Test
    @DisplayName("整数型参数可被覆盖为 0——0 是合法值，不能用「非零才生效」判断")
    void zeroIsAValidOverride() {
        // minConfidence=0.0 与 injectMaxTokens 这类「0 有意义」的字段最容易在实现里
        // 被写成 `x != null && x != 0` 之类的判断而静默失效。这里把它钉住。
        EvalParamOverrides overrides =
                new EvalParamOverrides(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0);

        assertFalse(overrides.isEmpty(), "全 0 不等于「没有覆盖」");

        RetrieverParams resolved = overrides.resolve(BASE);
        assertEquals(0, resolved.rrfK());
        assertEquals(0.0, resolved.alpha());
        assertEquals(0, resolved.injectMaxTokens());
    }

    @Test
    @DisplayName("全字段覆盖时逐位等于覆盖值")
    void fullOverrideReplacesEverything() {
        EvalParamOverrides overrides =
                new EvalParamOverrides(10, 0.9, 0.8, 7.0, 0.5, 0.6, 200);

        RetrieverParams resolved = overrides.resolve(BASE);

        assertEquals(new RetrieverParams(10, 0.9, 0.8, 7.0, 0.5, 0.6, 200), resolved);
    }
}
