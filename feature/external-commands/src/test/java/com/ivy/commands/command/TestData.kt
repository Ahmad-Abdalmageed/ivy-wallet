package com.ivy.commands.command

import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.ExchangeRate
import com.ivy.data.model.Tag
import com.ivy.data.model.TagId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import java.time.Instant
import java.util.UUID

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
