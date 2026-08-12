export interface KnowledgeItemVO {
  id?: number
  title: string
  sourceUrl?: string
  tags?: string
  scoreT?: number
  scoreA?: number
  scoreD?: number
  scoreM?: number
  weightedScore?: number
  createdAt?: string
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

export interface GraphNode {
  id: string
  label: string
  category: string
}

export interface GraphEdge {
  source: string
  target: string
  label: string
}

export interface GraphData {
  nodes: GraphNode[]
  edges: GraphEdge[]
}
