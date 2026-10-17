@file:OptIn(ExperimentalMaterial3Api::class)

package com.ivy.voicecommands.review

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ivy.base.model.TransactionType
import com.ivy.data.model.AccountId
import com.ivy.design.system.colors.IvyColors
import com.ivy.navigation.IvyPreview
import com.ivy.navigation.navigation
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

@Composable
fun VoiceReviewScreenImpl() {
    val viewModel: VoiceReviewViewModel = screenScopedViewModel()
    val uiState = viewModel.uiState()

    val nav = navigation()
    val context = LocalContext.current
    val savedMessage = uiState.savedCount?.let { stringResource(R.string.voice_review_saved, it) }
    LaunchedEffect(savedMessage) {
        if (savedMessage != null) {
            Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
            nav.back()
        }
    }

    VoiceReviewUi(
        uiState = uiState,
        onEvent = viewModel::onEvent
    )
}

@Composable
private fun VoiceReviewUi(
    uiState: VoiceReviewState,
    onEvent: (VoiceReviewEvent) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.voice_review_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (uiState.items.isNotEmpty()) {
                        TextButton(onClick = {
                            onEvent(VoiceReviewEvent.OnAllCheckedChange(!uiState.allChecked))
                        }) {
                            Text(
                                text = if (uiState.allChecked) {
                                    stringResource(R.string.voice_review_deselect_all)
                                } else {
                                    stringResource(R.string.voice_review_select_all)
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            SaveBar(
                checkedCount = uiState.checkedCount,
                enabled = uiState.canSave,
                saving = uiState.saving,
                onSave = { onEvent(VoiceReviewEvent.OnSaveClick) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    modifier = Modifier.padding(bottom = 12.dp),
                    text = stringResource(R.string.voice_review_description),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            items(
                items = uiState.items,
                key = { it.index }
            ) { item ->
                ReviewItemCard(
                    modifier = Modifier.padding(bottom = 12.dp),
                    item = item,
                    accounts = uiState.accounts,
                    onEvent = onEvent
                )
            }
        }
    }
}

@Composable
private fun BackButton() {
    val nav = navigation()
    IconButton(onClick = { nav.back() }) {
        Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = "Back"
        )
    }
}

@Composable
private fun SaveBar(
    checkedCount: Int,
    enabled: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            enabled = enabled,
            onClick = onSave
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(text = stringResource(R.string.voice_review_save, checkedCount))
            }
        }
    }
}

@Composable
private fun ReviewItemCard(
    item: VoiceReviewItem,
    accounts: ImmutableList<ReviewAccount>,
    onEvent: (VoiceReviewEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val transfer = item.type == TransactionType.TRANSFER
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (item.checked) 1f else UncheckedAlpha),
        border = CardDefaults.outlinedCardBorder(enabled = item.checked),
    ) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { onEvent(VoiceReviewEvent.OnCheckedChange(item.index, it)) }
                )
                TypeChip(
                    type = item.type,
                    onTypeChange = { onEvent(VoiceReviewEvent.OnTypeChange(item.index, it)) }
                )
                Spacer(modifier = Modifier.weight(1f))
                DateChip(
                    date = item.date,
                    onDateChange = { onEvent(VoiceReviewEvent.OnDateChange(item.index, it)) }
                )
            }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                item.description?.let { description ->
                    Text(
                        modifier = Modifier.padding(bottom = 4.dp),
                        text = description,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AmountField(
                    label = stringResource(R.string.voice_review_amount),
                    amount = item.amount,
                    currency = item.currency,
                    isError = item.checked && item.error == ReviewError.Amount,
                    onAmountChange = { onEvent(VoiceReviewEvent.OnAmountChange(item.index, it)) }
                )
                AccountDropdown(
                    label = stringResource(if (transfer) R.string.voice_review_from_account else R.string.account),
                    selected = item.account,
                    accounts = accounts,
                    isError = item.checked && item.error == ReviewError.Account,
                    onSelect = { onEvent(VoiceReviewEvent.OnAccountChange(item.index, it)) }
                )
                if (transfer) {
                    AccountDropdown(
                        label = stringResource(R.string.voice_review_to_account),
                        selected = item.toAccount,
                        accounts = accounts,
                        isError = item.checked &&
                            (item.error == ReviewError.ToAccount || item.error == ReviewError.SameAccounts),
                        onSelect = { onEvent(VoiceReviewEvent.OnToAccountChange(item.index, it)) }
                    )
                }
                item.toAmount?.let { toAmount ->
                    AmountField(
                        label = stringResource(R.string.voice_review_to_amount),
                        amount = toAmount,
                        currency = item.toAccount?.currency.orEmpty(),
                        isError = item.checked && item.error == ReviewError.ToAmount,
                        onAmountChange = { onEvent(VoiceReviewEvent.OnToAmountChange(item.index, it)) }
                    )
                }
                if (item.checked && item.error != null) {
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = stringResource(errorTextRes(item.error)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeChip(
    type: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(text = stringResource(typeLabelRes(type)), color = typeColor(type)) },
            leadingIcon = {
                Icon(
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                    painter = painterResource(typeIcon(type)),
                    contentDescription = null,
                    tint = typeColor(type)
                )
            }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TransactionType.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(typeLabelRes(option))) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(typeIcon(option)),
                            contentDescription = null,
                            tint = typeColor(option)
                        )
                    },
                    onClick = {
                        expanded = false
                        onTypeChange(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun DateChip(
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
) {
    var pickerVisible by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { pickerVisible = true },
            label = {
                Text(
                    text = relativeDayRes(date, today = LocalDate.now())?.let { stringResource(it) }
                        ?: dateFormatter.format(date)
                )
            },
            leadingIcon = {
                Icon(
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                    painter = painterResource(R.drawable.ic_calendar),
                    contentDescription = null
                )
            }
        )
        if (pickerVisible) {
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { pickerVisible = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDateChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        pickerVisible = false
                    }) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pickerVisible = false }) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                }
            ) {
                DatePicker(state = pickerState)
            }
        }
    }
}

@Composable
private fun AmountField(
    label: String,
    amount: String,
    currency: String,
    isError: Boolean,
    onAmountChange: (String) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = amount,
        onValueChange = onAmountChange,
        label = { Text(text = label) },
        suffix = { Text(text = currency) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun AccountDropdown(
    label: String,
    selected: ReviewAccount?,
    accounts: ImmutableList<ReviewAccount>,
    isError: Boolean,
    onSelect: (AccountId) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(text = label) },
            isError = isError,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(text = "${account.name} (${account.currency})") },
                    onClick = {
                        expanded = false
                        onSelect(account.id)
                    }
                )
            }
        }
    }
}

@StringRes
private fun typeLabelRes(type: TransactionType): Int = when (type) {
    TransactionType.INCOME -> R.string.income
    TransactionType.EXPENSE -> R.string.expense
    TransactionType.TRANSFER -> R.string.transfer
}

private fun typeIcon(type: TransactionType): Int = when (type) {
    TransactionType.INCOME -> R.drawable.ic_income
    TransactionType.EXPENSE -> R.drawable.ic_expense
    TransactionType.TRANSFER -> R.drawable.ic_transfer
}

private fun typeColor(type: TransactionType): Color = when (type) {
    TransactionType.INCOME -> IvyColors.Green.primary
    TransactionType.EXPENSE -> IvyColors.Red.primary
    TransactionType.TRANSFER -> IvyColors.Purple.primary
}

@StringRes
private fun relativeDayRes(date: LocalDate, today: LocalDate): Int? = when (date) {
    today -> R.string.today
    today.minusDays(1) -> R.string.yesterday
    else -> null
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@StringRes
private fun errorTextRes(error: ReviewError): Int = when (error) {
    ReviewError.Amount, ReviewError.ToAmount -> R.string.voice_review_error_amount
    ReviewError.Account -> R.string.voice_review_error_account
    ReviewError.ToAccount -> R.string.voice_review_error_to_account
    ReviewError.SameAccounts -> R.string.voice_review_error_same_accounts
}

private const val UncheckedAlpha = 0.6f

@Preview
@Composable
private fun PreviewVoiceReview(isDark: Boolean = false) {
    val cash = ReviewAccount(id = AccountId(UUID.randomUUID()), name = "Cash", currency = "EGP")
    val usd = ReviewAccount(id = AccountId(UUID.randomUUID()), name = "USD Savings", currency = "USD")
    val today = LocalDate.now()
    IvyPreview(isDark) {
        VoiceReviewUi(
            uiState = VoiceReviewState(
                items = persistentListOf(
                    VoiceReviewItem(
                        index = 0,
                        checked = true,
                        type = TransactionType.EXPENSE,
                        description = "Coffee",
                        amount = "45",
                        currency = "EGP",
                        account = cash,
                        toAccount = null,
                        toAmount = null,
                        date = today,
                        error = null,
                    ),
                    VoiceReviewItem(
                        index = 1,
                        checked = true,
                        type = TransactionType.TRANSFER,
                        description = null,
                        amount = "5000",
                        currency = "EGP",
                        account = cash,
                        toAccount = usd,
                        toAmount = "102.5",
                        date = today.minusDays(1),
                        error = null,
                    ),
                    VoiceReviewItem(
                        index = 2,
                        checked = false,
                        type = TransactionType.INCOME,
                        description = "Salary",
                        amount = "",
                        currency = "EGP",
                        account = null,
                        toAccount = null,
                        toAmount = null,
                        date = today,
                        error = ReviewError.Amount,
                    ),
                ),
                accounts = persistentListOf(cash, usd),
                checkedCount = 2,
                allChecked = false,
                canSave = true,
                saving = false,
                savedCount = null,
            ),
            onEvent = {}
        )
    }
}
