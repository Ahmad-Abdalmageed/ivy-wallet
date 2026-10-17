package com.ivy.voicecommands.parser

import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import java.time.LocalDate
import java.util.UUID

// Saturday
val Today: LocalDate = LocalDate.of(2026, 10, 17)

fun category(name: String): Category = Category(
    id = CategoryId(UUID.randomUUID()),
    name = NotBlankTrimmedString.unsafe(name),
    color = ColorInt(0),
    icon = null,
    orderNum = 0.0,
)

fun account(name: String): Account = Account(
    id = AccountId(UUID.randomUUID()),
    name = NotBlankTrimmedString.unsafe(name),
    asset = AssetCode.unsafe("USD"),
    color = ColorInt(0),
    icon = null,
    includeInBalance = true,
    orderNum = 0.0,
)
