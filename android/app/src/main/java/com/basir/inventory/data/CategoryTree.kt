package com.basir.inventory.data

import com.basir.inventory.data.db.CategoryEntity

/** In-memory helpers over the (small) category list. */
class CategoryTree(val all: List<CategoryEntity>) {
    private val byId = all.associateBy { it.id }
    private val byParent = all.groupBy { it.parentId }

    fun get(id: Long?): CategoryEntity? = id?.let(byId::get)

    fun children(parentId: Long?): List<CategoryEntity> = byParent[parentId].orEmpty().sortedBy { it.name.lowercase() }

    /** Root-first chain, e.g. [Bathroom Fittings, Faucets, Kitchen Faucets]. */
    fun chain(id: Long?): List<CategoryEntity> {
        val out = ArrayList<CategoryEntity>(3)
        var c = get(id)
        while (c != null && out.size < 3) {
            out.add(0, c)
            c = get(c.parentId)
        }
        return out
    }

    fun path(id: Long?): String = chain(id).joinToString(" › ") { it.name }

    /** [id] and everything below it, for filtering. */
    fun withDescendants(id: Long): Set<Long> {
        val out = mutableSetOf(id)
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) children(queue.removeFirst()).forEach { if (out.add(it.id)) queue.add(it.id) }
        return out
    }

    /** Depth-first order for tree display. */
    fun flattened(): List<CategoryEntity> {
        val out = ArrayList<CategoryEntity>(all.size)
        visitChildren(null, out)
        return out
    }

    private fun visitChildren(id: Long?, out: MutableList<CategoryEntity>) {
        children(id).forEach { out.add(it); visitChildren(it.id, out) }
    }
}
