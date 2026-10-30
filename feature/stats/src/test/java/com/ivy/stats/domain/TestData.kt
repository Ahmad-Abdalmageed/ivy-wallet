package com.ivy.stats.domain

import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.ExchangeRate
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Tag
import com.ivy.data.model.TagId
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

val Zone: ZoneOffset = ZoneOffset.UTC

fun account(name: String, currency: String): Account = Account(
    id = AccountId(UUID.randomUUID()),
    name = NotBlankTrimmedString.unsafe(name),
    asset = AssetCode.unsafe(currency),
    color = ColorInt(0),
    icon = null,
    includeInBalance = true,
    orderNum = 0.0,
)

fun category(name: String): Category = Category(
    id = CategoryId(UUID.randomUUID()),
    name = NotBlankTrimmedString.unsafe(name),
    color = ColorInt(0),
    icon = null,
    orderNum = 0.0,
)

fun tag(name: String): Tag = Tag(
    id = TagId(UUID.randomUUID()),
    name = NotBlankTrimmedString.unsafe(name),
    description = null,
    color = ColorInt(0),
    icon = null,
    orderNum = 0.0,
    creationTimestamp = Instant.EPOCH,
)

fun rate(base: String, currency: String, rate: Double): ExchangeRate = ExchangeRate(
    baseCurrency = AssetCode.unsafe(base),
    currency = AssetCode.unsafe(currency),
    rate = PositiveDouble.unsafe(rate),
    manualOverride = false,
)

private val metadata = TransactionMetadata(
    recurringRuleId = null,
    paidForDateTime = null,
    loanId = null,
    loanRecordId = null,
)

@Suppress("LongParameterList")
fun expense(
    amount: Double,
    account: Account,
    date: LocalDate,
    category: Category? = null,
    tags: List<Tag> = emptyList(),
    settled: Boolean = true,
): Expense = Expense(
    id = TransactionId(UUID.randomUUID()),
    title = null,
    description = null,
    category = category?.id,
    time = date.atTime(12, 0).toInstant(Zone),
    settled = settled,
    metadata = metadata,
    tags = tags.map(Tag::id),
    value = PositiveValue(PositiveDouble.unsafe(amount), account.asset),
    account = account.id,
)

fun income(amount: Double, account: Account, date: LocalDate): Income = Income(
    id = TransactionId(UUID.randomUUID()),
    title = null,
    description = null,
    category = null,
    time = date.atTime(12, 0).toInstant(Zone),
    settled = true,
    metadata = metadata,
    tags = emptyList(),
    value = PositiveValue(PositiveDouble.unsafe(amount), account.asset),
    account = account.id,
)
