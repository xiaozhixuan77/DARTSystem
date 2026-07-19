package com.dart.agent.scoring;

/**
 * 内容深度评分：D = min(5, wordCount/500 + codeBlocks×0.5 + hasDiagram + hasExercise)
 * 下限为 1（有内容时），0 表示空内容。
 */
public class DepthScorer {

    public int score(int wordCount, int codeBlockCount, boolean hasDiagram, boolean hasExercise) {
        boolean hasContent = wordCount > 0 || codeBlockCount > 0 || hasDiagram || hasExercise;
        if (!hasContent) {
            return 0;
        }
        double raw = wordCount / 500.0
                + codeBlockCount * 0.5
                + (hasDiagram ? 1 : 0)
                + (hasExercise ? 1 : 0);
        int d = (int) raw;
        if (d < 1) {
            d = 1;
        }
        if (d > 5) {
            d = 5;
        }
        return d;
    }
}
