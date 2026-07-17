export interface KnowledgeItemVO {
  id: number
  title: string
  summary: string
  sourceUrl: string
  tags: string[]
  scoreT: number
  scoreA: number
  scoreD: number
  scoreM: number
  createdAt: string
}

export interface KnowledgeItemDetail {
  id: number
  title: string
  content: string
  sourceUrl: string
  tags: string[]
  scoreT: number
  scoreA: number
  scoreD: number
  scoreM: number
  notes: string
  chunks: KnowledgeChunk[]
  createdAt: string
  updatedAt: string
}

export interface KnowledgeChunk {
  index: number
  text: string
}

export interface TagGroups {
  [category: string]: string[]
}

export interface TagsResponse {
  system: TagGroups
  custom: string[]
}
