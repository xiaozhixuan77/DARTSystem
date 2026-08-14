import type { KnowledgeItemDetail } from '@/types/knowledge'

export function buildMarkdownExport(item: KnowledgeItemDetail): string {
  const lines: string[] = [
    `# ${item.title}`,
    '',
    item.sourceUrl ? `> 来源：[${item.sourceUrl}](${item.sourceUrl})` : '',
    item.tags ? `> 标签：${item.tags}` : '',
    item.createdAt ? `> 沉淀时间：${item.createdAt}` : '',
    '',
    '---',
    '',
    item.content || '',
  ]
  if (item.notes) {
    lines.push('', '---', '', `> 备注：${item.notes}`)
  }
  return lines.join('\n')
}

export function sanitizeFilename(title: string): string {
  const cleaned = title.replace(/[\\/:*?"<>|]/g, '_').trim()
  return cleaned || 'knowledge'
}

export function downloadMarkdown(title: string, content: string) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${sanitizeFilename(title)}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
