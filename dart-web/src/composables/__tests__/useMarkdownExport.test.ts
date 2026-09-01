import { describe, it, expect, vi, afterEach } from 'vitest'
import { buildMarkdownExport, sanitizeFilename, downloadMarkdown } from '../useMarkdownExport'
import type { KnowledgeItemDetail } from '@/types/knowledge'

const item: KnowledgeItemDetail = {
  id: 1, title: 'Go 并发模式', content: '# 正文\n\n内容段落', sourceUrl: 'https://go.dev',
  tags: 'Go,并发', scoreT: 4, scoreA: 5, scoreD: 4, scoreM: 5,
  notes: '学习笔记', createdAt: '2026-01-01 10:00', updatedAt: ''
}

describe('buildMarkdownExport', () => {
  it('包含标题、来源、标签、正文', () => {
    const md = buildMarkdownExport(item)
    expect(md).toContain('# Go 并发模式')
    expect(md).toContain('来源：[https://go.dev](https://go.dev)')
    expect(md).toContain('标签：Go,并发')
    expect(md).toContain('# 正文')
    expect(md).toContain('内容段落')
  })

  it('包含备注', () => {
    expect(buildMarkdownExport(item)).toContain('备注：学习笔记')
  })

  it('无来源/标签/备注时不输出空行占位', () => {
    const bare: KnowledgeItemDetail = {
      ...item, sourceUrl: '', tags: '', notes: '', content: '只有正文'
    }
    const md = buildMarkdownExport(bare)
    expect(md).not.toContain('来源：')
    expect(md).not.toContain('标签：')
    expect(md).not.toContain('备注：')
    expect(md).toContain('只有正文')
  })

  it('空正文仍输出标题', () => {
    const md = buildMarkdownExport({ ...item, content: '' })
    expect(md).toContain('# Go 并发模式')
  })
})

describe('sanitizeFilename', () => {
  it('替换非法文件名字符', () => {
    expect(sanitizeFilename('a/b\\c:d*e?f"g<h>i|j')).toBe('a_b_c_d_e_f_g_h_i_j')
  })

  it('空标题回退默认名', () => {
    expect(sanitizeFilename('   ')).toBe('knowledge')
  })
})

describe('downloadMarkdown', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('创建 Blob 并触发下载', () => {
    const createSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock')
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    downloadMarkdown('Go 并发', '# 内容')

    expect(createSpy).toHaveBeenCalledTimes(1)
    const blob = createSpy.mock.calls[0][0] as Blob
    expect(blob.type).toContain('text/markdown')
    expect(revokeSpy).toHaveBeenCalledWith('blob:mock')
    expect(clickSpy).toHaveBeenCalledTimes(1)
  })
})
