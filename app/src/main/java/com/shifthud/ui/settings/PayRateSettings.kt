package com.shifthud.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import com.shifthud.domain.pay.*
import com.shifthud.ui.*
import com.shifthud.ui.schedule.*
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun PayRateSettings(data: ShiftUiState, vm: ShiftViewModel, busy: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val today = data.now.atZone(zone).toLocalDate()
    var dateText by rememberSaveable { mutableStateOf(today.toString()) }
    val date = LocalDate.parse(dateText)
    val selectedRate = data.pay.rates.filter { it.effectiveFrom <= today }.maxByOrNull { it.effectiveFrom }
    var input by rememberSaveable(data.loaded) {
        mutableStateOf(BigDecimal.valueOf(selectedRate?.centsPerHour ?: 1600, 2).toPlainString())
    }
    var picking by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    Text("Hourly rate", style = MaterialTheme.typography.titleLarge)
    data.pay.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val enabled = data.loaded && !busy && data.pay.rates.isNotEmpty() && data.pay.error == null
    OutlinedTextField(input, { input = it; message = null }, label = { Text("USD per hour") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    PickerField("Effective from", date.format(format), enabled) { picking = true }
    if (picking) ScheduleDatePicker(date, { picking = false }) { dateText = it.toString(); message = null; picking = false }
    Text("Each shift uses the rate on its clock-in date. Saving replaces a rate on this date and affects shifts starting then until the next rate. Earlier dates keep their rates.")
    Button(enabled = enabled, onClick = {
        val cents = runCatching { parseRateCents(input) }
        cents.fold(onSuccess = { vm.saveRate(PayRate(date, it)) { message = "Rate saved" } },
            onFailure = { message = it.message })
    }) { Text(if (data.pay.rates.any { it.effectiveFrom == date }) "Replace rate for date" else "Save rate for date") }
    message?.let { Text(it) }
    Text("Rate history", style = MaterialTheme.typography.titleMedium)
    data.pay.rates.asReversed().forEach { rate ->
        Text("${if (rate.effectiveFrom == LocalDate.MIN) "Before first dated rate" else rate.effectiveFrom.format(format)} · ${money(rate.centsPerHour, locale)} / hour")
    }
    Text("Base-rate estimates only. No overtime premiums, taxes, withholding, bonuses, differentials, or payroll rounding.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
}
