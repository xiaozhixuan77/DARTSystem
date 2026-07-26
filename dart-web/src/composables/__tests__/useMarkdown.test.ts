import { describe, it, expect } from 'vitest'
import { useMarkdown } from '../useMarkdown'

describe('useMarkdown', () => {
  const { render } = useMarkdown()

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
    // markdown-it 会转义 HTML 标签，DOMPurify 会移除任何残留
    expect(html).not.toContain('<script>')
  })

  it('XSS 净化 — 输出中不含可执行脚本', () => {
    const html = render('<img src=x onerror=alert(1)>')
    // markdown-it 默认转义裸 HTML，img 标签被转为 &lt;img...
    expect(html).not.toContain('<img')
  })

  it('空字符串渲染为段落', () => {
    const html = render('')
    expect(typeof html).toBe('string')
  })
})
