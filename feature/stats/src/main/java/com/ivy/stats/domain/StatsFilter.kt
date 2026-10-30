package com.ivy.stats.domain

import com.ivy.data.model.AccountId
import com.ivy.data.model.CategoryId
import com.ivy.data.model.TagId

/**
 * Which expenses are counted. An expense is counted when its account,
 * its category and one of its tags are selected.
 * `null` stands for "no category" in [categories] and "no tags" in [tags].
 */
data class StatsFilter(
    val accounts: Set<AccountId>,
    val categories: Set<CategoryId?>,
    val tags: Set<TagId?>,
)
