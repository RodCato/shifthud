package com.shifthud.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.shifthud.domain.pay.*
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

private val Context.payRateStore by preferencesDataStore(name = "pay_rates")

// A small atomic date→rate history, separate from session data and reminder preferences.
// Invalid/unreadable data is surfaced to the UI, never replaced with a misleading default rate.
class PayRatePreferences(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.payRateStore)
    private val key = stringPreferencesKey("history_v1")
    val rates = store.data.map { decode(it[key]) }

    suspend fun save(rate: PayRate) {
        require(rate.effectiveFrom != LocalDate.MIN)
        store.edit { values ->
            val history = (decode(values[key]).filterNot { it.effectiveFrom == rate.effectiveFrom } + rate).sortedBy { it.effectiveFrom }
            values[key] = JSONArray().apply {
                history.forEach { put(JSONObject().put("from", it.effectiveFrom.toString()).put("cents", it.centsPerHour)) }
            }.toString()
        }
    }

    private fun decode(raw: String?): List<PayRate> {
        if (raw == null) return DEFAULT_PAY_RATES
        val array = JSONArray(raw)
        val rates = (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val cents = row.get("cents").toString().toLong() // Reject fractional/corrupt amounts.
            PayRate(LocalDate.parse(row.getString("from")), cents)
        }.sortedBy { it.effectiveFrom }
        check(rates.firstOrNull() == DEFAULT_PAY_RATES.single() && rates.map { it.effectiveFrom }.distinct().size == rates.size) {
            "Invalid pay-rate history."
        }
        return rates
    }
}
