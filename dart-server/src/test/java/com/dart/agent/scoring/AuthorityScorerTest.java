package com.dart.agent.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityScorerTest {

    private AuthorityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new AuthorityScorer();
    }

    @Test
    void score_whenOracleDocs_returns5() {
        assertThat(scorer.score("https://docs.oracle.com/en/java/javase/17/")).isEqualTo(5);
    }

    @Test
    void score_whenSpringIO_returns5() {
        assertThat(scorer.score("https://spring.io/guides/gs/spring-boot")).isEqualTo(5);
    }

    @Test
    void score_whenReactDev_returns5() {
        assertThat(scorer.score("https://react.dev/reference/react/useState")).isEqualTo(5);
    }

    @Test
    void score_whenVuejsOrg_returns5() {
        assertThat(scorer.score("https://vuejs.org/guide/introduction.html")).isEqualTo(5);
    }

    @Test
    void score_whenMDN_returns5() {
        assertThat(scorer.score("https://developer.mozilla.org/en-US/docs/Web/JavaScript")).isEqualTo(5);
    }

    @Test
    void score_whenStackOverflow_returns4() {
        assertThat(scorer.score("https://stackoverflow.com/questions/123/java")).isEqualTo(4);
    }

    @Test
    void score_whenGitHub_returns4() {
        assertThat(scorer.score("https://github.com/spring-projects/spring-boot")).isEqualTo(4);
    }

    @Test
    void score_whenMedium_returns4() {
        assertThat(scorer.score("https://medium.com/@user/java-tutorial")).isEqualTo(4);
    }

    @Test
    void score_whenDevTo_returns3() {
        assertThat(scorer.score("https://dev.to/author/react-hooks-guide")).isEqualTo(3);
    }

    @Test
    void score_whenJuejin_returns3() {
        assertThat(scorer.score("https://juejin.cn/post/123456")).isEqualTo(3);
    }

    @Test
    void score_whenSegmentfault_returns3() {
        assertThat(scorer.score("https://segmentfault.com/a/119000004")).isEqualTo(3);
    }

    @Test
    void score_whenCSDN_returns3() {
        assertThat(scorer.score("https://blog.csdn.net/author/article/details/123")).isEqualTo(3);
    }

    @Test
    void score_whenGithubIo_returns2() {
        assertThat(scorer.score("https://username.github.io/my-blog/java-tips")).isEqualTo(2);
    }

    @Test
    void score_whenVercelApp_returns2() {
        assertThat(scorer.score("https://myblog.vercel.app/posts/spring")).isEqualTo(2);
    }

    @Test
    void score_whenUnknownDomain_returns1() {
        assertThat(scorer.score("https://random-unknown.com/article/java")).isEqualTo(1);
    }

    @Test
    void score_whenNullUrl_returns1() {
        assertThat(scorer.score((String) null)).isEqualTo(1);
    }

    @Test
    void score_whenEmptyUrl_returns1() {
        assertThat(scorer.score("")).isEqualTo(1);
    }

    @Test
    void score_whenMalformedUrl_returns1() {
        assertThat(scorer.score("not-a-valid-url")).isEqualTo(1);
    }
}
