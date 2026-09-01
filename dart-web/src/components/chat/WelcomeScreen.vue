<template>
  <div class="welcome-screen">
    <div class="welcome-icon">D</div>
    <h1 class="welcome-title">DART 智能技术检索</h1>
    <p class="welcome-desc">输入技术问题，Search Agent 全网搜索，Curator Agent 整理为结构化报告</p>
    <div class="welcome-steps">
      <div class="step">
        <div class="step-num">1</div>
        <div class="step-text">输入你关心的技术问题</div>
      </div>
      <div class="step">
        <div class="step-num">2</div>
        <div class="step-text">双 Agent 搜索与智能整理</div>
      </div>
      <div class="step">
        <div class="step-num">3</div>
        <div class="step-text">获得结构化报告，存入知识库</div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.welcome-screen {
  flex: 1;
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  padding: $gap-xl;
  text-align: center;
}
.welcome-icon {
  width: 72px; height: 72px;
  border-radius: 50%;
  background: $color-accent-soft;
  color: $color-accent;
  display: grid; place-items: center;
  font-family: $font-mono; font-size: 32px; font-weight: 700;
  margin-bottom: $gap-lg;
}
.welcome-title {
  font-family: $font-display;
  font-size: 28px; font-weight: 700;
  margin-bottom: 8px;
  color: $color-fg;
}
.welcome-desc {
  font-size: 15px; color: $color-muted;
  max-width: 420px; line-height: 1.6;
  margin-bottom: $gap-xl;
}
.welcome-steps {
  display: flex; gap: $gap-lg;
}
.step {
  display: flex; flex-direction: column;
  align-items: center; gap: 8px;
  padding: $gap-md;
  background: $color-surface;
  border: 1px solid $color-border;
  border-radius: $radius-lg;
  width: 160px;
}
.step-num {
  width: 32px; height: 32px;
  border-radius: 50%;
  background: $color-accent-soft;
  color: $color-accent;
  display: grid; place-items: center;
  font-weight: 700; font-size: 14px;
}
.step-text {
  font-size: 13px; color: $color-muted;
}
</style>
