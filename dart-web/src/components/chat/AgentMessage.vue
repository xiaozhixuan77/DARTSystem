<script setup lang="ts">
import { computed } from 'vue'
import { useMarkdown } from '@/composables/useMarkdown'

interface Source {
  title: string
  url: string
}

interface Scores {
  T: number
  A: number
  D: number
  M: number
}

interface AgentReport {
  title: string
  content: string
  sources: Source[]
  scores: Scores
  weightedScore?: number
  generatedAt?: string
}

const props = defineProps<{ report: AgentReport }>()
const { render } = useMarkdown()

const renderedContent = computed(() => render(props.report.content))
const hasSources = computed(() => props.report.sources.length > 0)
</script>

<template>
  <div class="agent-message">
    <h3 class="agent-title">{{ report.title || '分析报告' }}</h3>
    <div class="agent-content markdown" v-html="renderedContent" />

    <div class="agent-scores" v-if="report.scores">
      <span class="score-badge" v-for="(val, key) in report.scores" :key="key">
        <strong>{{ key }}</strong> {{ val }}
      </span>
    </div>

    <div class="agent-sources" v-if="hasSources">
      <div class="sources-title">参考来源</div>
      <a
        v-for="(s, i) in report.sources" :key="i"
        :href="s.url" target="_blank" class="source-item"
      >
        <span class="source-index">{{ i + 1 }}</span>
        {{ s.title }}
      </a>
    </div>

    <div class="agent-meta" v-if="report.weightedScore || report.generatedAt">
      <span v-if="report.weightedScore">综合评分: {{ report.weightedScore }}</span>
      <span v-if="report.generatedAt">{{ report.generatedAt }}</span>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.agent-message {
  background: $color-surface;
  border: 1px solid $color-border;
  border-radius: $radius-lg;
  padding: 20px;
}
.agent-title {
  font-family: $font-display;
  font-size: 18px;
  font-weight: 700;
  margin: 0 0 12px;
}
// markdown 元素样式（标题/列表/代码块/表格等）统一由全局 styles/markdown.scss 管理
.agent-content {
  margin-bottom: 16px;
}
.agent-scores {
  display: flex; gap: 8px; margin-bottom: 12px;
  flex-wrap: wrap;
}
.score-badge {
  padding: 2px 10px;
  background: $color-accent-soft;
  color: $color-accent;
  border-radius: 100px;
  font-size: 12px;
}
.agent-sources {
  margin-bottom: 12px;
}
.sources-title {
  font-size: 12px;
  font-weight: 600;
  color: $color-muted;
  margin-bottom: 6px;
}
.source-item {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 0;
  font-size: 13px;
  color: $color-accent;
  text-decoration: none;
  &:hover { text-decoration: underline; }
}
.source-index {
  width: 20px; height: 20px;
  border-radius: 50%;
  background: $color-accent-soft;
  display: grid; place-items: center;
  font-size: 11px; font-weight: 600;
  flex-shrink: 0;
}
.agent-meta {
  display: flex; gap: 16px;
  font-size: 12px; color: $color-muted;
}
</style>
