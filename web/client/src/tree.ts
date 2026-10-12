import type { Category, Item } from './api'

/** In-memory helpers over the (small) category list; port of the Android CategoryTree. */
export class CategoryTree {
  private byId: Map<number, Category>
  private byParent: Map<number | null, Category[]>

  constructor(public all: Category[]) {
    this.byId = new Map(all.map((c) => [c.id, c]))
    this.byParent = new Map()
    for (const c of all) {
      const list = this.byParent.get(c.parentId) ?? []
      list.push(c)
      this.byParent.set(c.parentId, list)
    }
  }

  get(id: number | null | undefined): Category | undefined {
    return id == null ? undefined : this.byId.get(id)
  }

  children(parentId: number | null): Category[] {
    return [...(this.byParent.get(parentId) ?? [])].sort((a, b) => a.name.localeCompare(b.name))
  }

  /** Root-first chain, e.g. [Bathroom Fittings, Faucets, Kitchen Faucets]. */
  chain(id: number | null | undefined): Category[] {
    const out: Category[] = []
    let c = this.get(id)
    while (c && out.length < 3) {
      out.unshift(c)
      c = this.get(c.parentId)
    }
    return out
  }

  path(id: number | null | undefined): string {
    return this.chain(id).map((c) => c.name).join(' › ')
  }

  /** id and everything below it, for filtering. */
  withDescendants(id: number): Set<number> {
    const out = new Set([id])
    const queue = [id]
    while (queue.length) {
      for (const c of this.children(queue.shift()!)) {
        if (!out.has(c.id)) {
          out.add(c.id)
          queue.push(c.id)
        }
      }
    }
    return out
  }

  /** Depth-first order for tree display. */
  flattened(): Category[] {
    const out: Category[] = []
    const visit = (id: number | null) => this.children(id).forEach((c) => { out.push(c); visit(c.id) })
    visit(null)
    return out
  }
}

export type StockLevel = 'OK' | 'LOW' | 'OUT'

export function stockLevel(item: Item, globalThreshold: number): StockLevel {
  if (item.qtyMilli <= 0) return 'OUT'
  if (item.qtyMilli <= (item.thresholdMilli ?? globalThreshold)) return 'LOW'
  return 'OK'
}
