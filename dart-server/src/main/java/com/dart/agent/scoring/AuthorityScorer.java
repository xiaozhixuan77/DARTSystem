package com.dart.agent.scoring;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * 权威性评分：基于域名 5 级映射表。
 */
public class AuthorityScorer {

    private static final Map<String, Integer> DOMAIN_SCORES = Map.ofEntries(
            // 官方文档 (5)
            Map.entry("docs.oracle.com", 5),
            Map.entry("spring.io", 5),
            Map.entry("react.dev", 5),
            Map.entry("vuejs.org", 5),
            Map.entry("developer.mozilla.org", 5),
            Map.entry("kubernetes.io", 5),
            Map.entry("learn.microsoft.com", 5),
            // 知名技术社区 (4)
            Map.entry("stackoverflow.com", 4),
            Map.entry("github.com", 4),
            Map.entry("medium.com", 4),
            Map.entry("wikipedia.org", 4),
            Map.entry("reddit.com", 4),
            // 知名技术博客/教程站 (4)
            Map.entry("baeldung.com", 4),
            Map.entry("geeksforgeeks.org", 4),
            Map.entry("w3schools.com", 4),
            Map.entry("tutorialspoint.com", 4),
            Map.entry("freecodecamp.org", 4),
            Map.entry("howtodoinjava.com", 4),
            Map.entry("jenkov.com", 4),
            Map.entry("mkyong.com", 4),
            Map.entry("digitalocean.com", 4),
            // 技术博客平台 (3)
            Map.entry("dev.to", 3),
            Map.entry("juejin.cn", 3),
            Map.entry("segmentfault.com", 3),
            Map.entry("blog.csdn.net", 3),
            Map.entry("csdn.net", 3),
            Map.entry("cnblogs.com", 3),
            Map.entry("zhihu.com", 3),
            Map.entry("infoq.cn", 3),
            Map.entry("runoob.com", 3),
            Map.entry("liaoxuefeng.com", 3),
            Map.entry("baike.baidu.com", 3),
            Map.entry("jianshu.com", 3),
            Map.entry("huaweicloud.com", 3),
            Map.entry("alibabacloud.com", 3)
    );

    private static final Set<String> PERSONAL_DOMAINS = Set.of(
            "github.io", "vercel.app", "netlify.app", "pages.dev"
    );

    public int score(String url) {
        if (url == null || url.isBlank()) {
            return 1;
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return 1;
        }
        if (host == null) {
            return 1;
        }
        host = host.toLowerCase();

        // 精确匹配
        Integer score = DOMAIN_SCORES.get(host);
        if (score != null) {
            return score;
        }

        // 子域名匹配（精确匹配已在上面处理，这里只检查子域名）
        for (var entry : DOMAIN_SCORES.entrySet()) {
            if (host.endsWith("." + entry.getKey())) {
                return entry.getValue();
            }
        }

        // 个人域名后缀匹配
        for (String suffix : PERSONAL_DOMAINS) {
            if (host.endsWith("." + suffix)) {
                return 2;
            }
        }

        return 1;
    }
}
