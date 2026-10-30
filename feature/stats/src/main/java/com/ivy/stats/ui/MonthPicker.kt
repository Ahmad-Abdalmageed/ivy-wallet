package com.ivy.stats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.ivy.ui.R
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle

@Composable
internal fun MonthPicker(
    selectedYear: Int,
    selectedMonth: Int,
    onMonthSelect: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var year by remember { mutableIntStateOf(selectedYear) }
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.stats_cancel))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.stats_previous_year),
                    )
                }
                Text(
                    modifier = Modifier.weight(1f),
                    text = year.toString(),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { year++ }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.stats_next_year),
                    )
                }
            }
        },
        text = {
            Column {
                Month.entries.chunked(MonthsPerRow).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        row.forEach { month ->
                            val label = month.getDisplayName(TextStyle.SHORT, locale)
                            val onClick = { onMonthSelect(YearMonth.of(year, month)) }
                            if (year == selectedYear && month.value == selectedMonth) {
                                Button(modifier = Modifier.weight(1f), onClick = onClick) {
                                    Text(text = label, maxLines = 1)
                                }
                            } else {
                                TextButton(modifier = Modifier.weight(1f), onClick = onClick) {
                                    Text(
                                        text = label,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

private const val MonthsPerRow = 3
