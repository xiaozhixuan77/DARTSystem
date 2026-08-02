import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import DOMPurify from 'dompurify'
import mermaid from 'mermaid'

mermaid.initialize({
  startOnLoad: false,
  theme: 'base',
  mindmap: { useMaxWidth: true },
  // 渲染失败时保留原始文本（流式中间态可能触发不完整代码块渲染）
  suppressErrorRendering: true
})

const md: MarkdownIt = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  highlight(str: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang, ignoreIllegals: true }).value}</code></pre>`
      } catch { /* fall through */ }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`
  }
})

const MERMAID_FENCE_RE = /```mermaid\s*\n([\s\S]*?)```/g

export function useMarkdown() {
  function render(text: string): string {
    // 提取 mermaid 代码块，替换为唯一占位符，避免 DOMPurify 处理
    const mermaidBlocks: string[] = []
    const preprocessed = text.replace(MERMAID_FENCE_RE, (_, content: string) => {
      const idx = mermaidBlocks.length
      mermaidBlocks.push(content.trim())
      return `MERMAID_BLOCK_${idx}`
    })

    const raw = md.render(preprocessed)
    const sanitized = DOMPurify.sanitize(raw) as unknown as string

    // 将占位符替换回 <div class="mermaid"> 块（占位符可能被 DOMPurify 去掉 <p> 包装）
    return sanitized.replace(/MERMAID_BLOCK_(\d+)/g, (_, idx: string) => {
      const i = parseInt(idx, 10)
      return `<div class="mermaid">${mermaidBlocks[i]}</div>`
    })
  }

  /**
   * 在容器元素上运行 mermaid.run()，需在 DOM 更新后调用。
   */
  async function renderMermaidDiagrams(container?: HTMLElement) {
    try {
      await mermaid.run(container ? { querySelector: '.mermaid' } : undefined)
    } catch (e) {
      console.warn('Mermaid 渲染失败', e)
    }
  }

  return { render, renderMermaidDiagrams }
}
