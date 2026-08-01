import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useMarkdown } from '../useMarkdown'

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    run: vi.fn().mockResolvedValue(undefined)
  }
}))

describe('useMarkdown', () => {
  const { render, renderMermaidDiagrams } = useMarkdown()

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('渲染纯文本包含原文', () => {
    const html = render('Hello World')
    expect(html).toContain('Hello World')
  })

  it('渲染加粗文本', () => {
    const html = render('**bold**')
    expect(html).toContain('<strong>bold</strong>')
  })

  it('渲染代码块包含代码', () => {
    const html = render('```javascript\nconst x = 1;\n```')
    expect(html).toContain('<code>')
  })

  it('渲染链接', () => {
    const html = render('[Google](https://google.com)')
    expect(html).toContain('href="https://google.com"')
  })

  it('渲染列表', () => {
    const html = render('- Item 1\n- Item 2')
    expect(html).toContain('<li>')
  })

  it('XSS 净化 — 移除危险标签', () => {
    const html = render('<script>alert("xss")</script>')
    expect(html).not.toContain('<script>')
  })

  it('XSS 净化 — 输出中不含可执行脚本', () => {
    const html = render('<img src=x onerror=alert(1)>')
    expect(html).not.toContain('<img')
  })

  it('空字符串渲染为段落', () => {
    const html = render('')
    expect(typeof html).toBe('string')
  })

  // === Mermaid 渲染 ===

  it('将 ```mermaid 代码块转为 <div class="mermaid">', () => {
    const html = render('```mermaid\nmindmap\n  root((Java))\n```')
    expect(html).toContain('<div class="mermaid">')
    expect(html).toContain('mindmap')
    expect(html).toContain('root((Java))')
  })

  it('不转换非 mermaid 代码块', () => {
    const html = render('```javascript\nconst x = 1;\n```')
    expect(html).not.toContain('<div class="mermaid">')
    expect(html).toContain('<code>')
  })

  it('同时包含 mermaid 和普通代码块时各自正确渲染', () => {
    const html = render(
      '```mermaid\nmindmap\n  root((Java))\n```\n\n```js\nconst y = 2;\n```'
    )
    expect(html).toContain('<div class="mermaid">')
    expect(html).toContain('mindmap')
    // 普通代码块保持为 <pre><code>
    const mermaidCount = (html.match(/class="mermaid"/g) || []).length
    expect(mermaidCount).toBe(1)
  })

  it('renderMermaidDiagrams 调用 mermaid.run', async () => {
    const { default: mermaid } = await import('mermaid')
    const container = document.createElement('div')
    await renderMermaidDiagrams(container)
    expect(mermaid.run).toHaveBeenCalled()
  })

  it('不含 mermaid 代码块时正常渲染', () => {
    const html = render('## 核心概念\n\nSpring Boot 是...')
    expect(html).toContain('核心概念')
    expect(html).not.toContain('class="mermaid"')
  })
})
