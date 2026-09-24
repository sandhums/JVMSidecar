package com.atrius.sidecar.cr

import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.ValueSet

internal const val EXPANSION_PAGE_SIZE = 1000

private const val MAX_EXPANSION_PAGES = 100

/**
 * True when the caller did not ask for a bounded `$expand` page.
 *
 * CQF measure evaluation calls `ValueSet/{id}/$expand` with no `count`. HTS then returns a short
 * page (observed default 36) while `expansion.total` is the full size, and that default page is
 * not the offset-0 prefix. Membership scans only `expansion.contains`, so codes absent from the
 * default page are reported not-in.
 */
internal fun parametersOmitExpandCount(parameters: Parameters?): Boolean {
    val named = parameters?.parameter ?: return true
    return named.none { it.name == "count" && it.value != null }
}

internal fun expansionPageParameters(offset: Int, count: Int): Parameters =
    Parameters().apply {
        addParameter().setName("count").value = IntegerType(count)
        addParameter().setName("offset").value = IntegerType(offset)
    }

/**
 * Replace a short `$expand` with pages requested from offset 0.
 *
 * HTS's default page is not a prefix of the offset-ordered expansion. Keeping those codes and
 * continuing at `offset = contains.size` skips codes that sit at the front of the paged sequence
 * (WHO ICD-10 `I63.9` on the ischemic-stroke set). [loadPage] therefore starts at offset 0.
 * An empty page stops the walk. If offset 0 itself is empty, the original page is kept.
 */
internal fun completePartialExpansion(
    first: ValueSet,
    pageSize: Int = EXPANSION_PAGE_SIZE,
    loadPage: (offset: Int, count: Int) -> ValueSet,
): ValueSet {
    val expansion = first.expansion ?: return first
    if (!expansion.hasTotal() || expansion.total <= expansion.contains.size) return first
    val merged = ArrayList<ValueSet.ValueSetExpansionContainsComponent>()
    var offset = 0
    var pages = 0
    while (merged.size < expansion.total && pages < MAX_EXPANSION_PAGES) {
        pages++
        val more = loadPage(offset, pageSize).expansion?.contains.orEmpty()
        if (more.isEmpty()) break
        merged.addAll(more)
        offset += more.size
    }
    if (merged.isEmpty()) return first
    expansion.contains.clear()
    expansion.contains.addAll(merged)
    return first
}
