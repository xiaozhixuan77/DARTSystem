package com.dart.agent.scoring;

import java.time.Clock;
import java.time.Year;

/**
 * 时效性评分：T = max(1, 5 − max(0, currentYear − resourceYear))
 */
public class TimelinessScorer {

    private final Clock clock;

    public TimelinessScorer(Clock clock) {
        this.clock = clock;
    }

    public TimelinessScorer() {
        this(Clock.systemUTC());
    }

    public int score(int resourceYear) {
        if (resourceYear <= 0) {
            return 1;
        }
        int currentYear = Year.now(clock).getValue();
        if (resourceYear > currentYear) {
            return 5;
        }
        return Math.max(1, 5 - Math.max(0, currentYear - resourceYear));
    }
}
