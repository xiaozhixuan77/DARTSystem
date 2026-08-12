package com.dart.agent.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchScorerTest {

    private MatchScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new MatchScorer();
    }

    @Test
    void evaluate_highHitRateAndTitleMatch_returns5() {
        int score = scorer.evaluate("Spring Boot 配置",
                "Spring Boot 配置指南 - 详细介绍Spring Boot的自动配置、YAML配置和属性文件配置等核心内容。");
        assertThat(score).isEqualTo(5);
    }

    @Test
    void evaluate_highHitRateNoTitleMatch_returns4() {
        // 用 200+ 字符填充确保前两个关键词不在标题区域，仅靠命中率得 4 分
        String padding = "Z".repeat(250);
        int score = scorer.evaluate("Django ORM",
                padding + " Django ORM provides great database access for Python developers.");
        assertThat(score).isEqualTo(4);
    }

    @Test
    void evaluate_moderateHitRate_returns3() {
        int score = scorer.evaluate("React Hooks useState useEffect",
                "React is a frontend JavaScript library for building user interfaces. React Hooks are useful.");
        assertThat(score).isEqualTo(3);
    }

    @Test
    void evaluate_lowHitRate_returns2() {
        int score = scorer.evaluate("Java Spring Boot配置",
                "Kotlin is a programming language that runs on JVM. It can be used for Android development.");
        assertThat(score).isEqualTo(1);
    }

    @Test
    void evaluate_noMatch_returns1() {
        int score = scorer.evaluate("Python Django", "Java Spring Boot is a framework...");
        assertThat(score).isEqualTo(1);
    }

    @Test
    void evaluate_whenContentEmpty_returns1() {
        assertThat(scorer.evaluate("查询", "")).isEqualTo(1);
    }

    @Test
    void evaluate_whenContentNull_returns1() {
        assertThat(scorer.evaluate("查询", null)).isEqualTo(1);
    }

    @Test
    void evaluate_whenQueryNull_returns1() {
        assertThat(scorer.evaluate(null, "一些内容")).isEqualTo(1);
    }

    @Test
    void evaluate_whenQueryBlank_returns1() {
        assertThat(scorer.evaluate("   ", "一些内容")).isEqualTo(1);
    }

    @Test
    void evaluate_titleMatchBonus() {
        int score = scorer.evaluate("Spring Boot入门指南",
                "Spring Boot入门指南 - 本文详细介绍Spring Boot的基础知识、自动配置原理和核心特性...");
        assertThat(score).isGreaterThanOrEqualTo(4);
    }

    @Test
    void evaluate_shortKeywordsSkipped() {
        // 英文 token 提取（≥2 字符）："a" 被过滤，[in, python] 有效；
        // "language" 含 "in" 误命中 + "python" 命中 → 0.5 命中率 → 3
        int score = scorer.evaluate("a in Python", "Learning Python programming language");
        assertThat(score).isEqualTo(3);
    }

    // === 中文分词（无标点长句不再整句匹配）===

    @Test
    void evaluate_chineseLongQuery_extractsKeywords() {
        // 旧实现：整句 "spring框架中ioc容器和aop的概念与作用" 一个关键词 → M=1
        // 新实现：英文 token [spring,ioc,aop] + 中文切分 [框架,容器,概念,作用] → 命中率高
        int score = scorer.evaluate("Spring框架中IoC容器和AOP的概念与作用",
                "Spring 框架的核心是 IoC 容器，AOP 用于处理横切关注点，本文介绍框架的作用。");
        assertThat(score).isGreaterThanOrEqualTo(4);
    }

    @Test
    void evaluate_chineseQuery_stopWordsStripped() {
        int score = scorer.evaluate("什么是Spring框架",
                "Spring 框架是一个 Java 企业级应用框架。");
        assertThat(score).isEqualTo(5);
    }

    @Test
    void evaluate_chineseLongQuery_noKeywordHit_returns1() {
        // 中文长句查询对完全无关内容（腰椎问答）仍返回 1 → 组合过滤条件可用
        int score = scorer.evaluate("Spring框架中IoC容器和AOP的概念与作用",
                "腰椎间盘突出患者日常护理注意事项，疼痛缓解方法与康复训练建议。");
        assertThat(score).isEqualTo(1);
    }
}
