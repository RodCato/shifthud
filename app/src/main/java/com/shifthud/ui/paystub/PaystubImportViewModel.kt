package com.shifthud.ui.paystub

import android.net.Uri
import androidx.lifecycle.*
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.payroll.Paycheck
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PaystubImportViewModel(private val app:ShiftHudApplication,private val saved:SavedStateHandle):ViewModel() {
    val text=saved.getStateFlow("recognized","")
    val message=MutableStateFlow<String?>(null)
    private val working=MutableStateFlow(false)
    val busy=working.asStateFlow()
    fun read(uri:Uri) {
        if(working.value)return
        working.value=true;message.value=null;saved["recognized"]=""
        viewModelScope.launch {
            try {val result=PaystubOcr(app).read(uri);ensureActive();saved["recognized"]=result; if(result.isBlank())message.value="No text recognized. Choose a clearer, uncropped screenshot or enter values manually."}
            catch(e:CancellationException){throw e}
            catch(_:Exception){message.value="Could not read this image. Select a supported screenshot and try again."}
            finally{working.value=false}
        }
    }
    fun save(p:Paycheck,done:(String?)->Unit) {
        if(working.value)return
        working.value=true
        viewModelScope.launch {
            try {
                // Never merge OCR facts into an existing record. Repository also checks atomically.
                val duplicate=app.payroll.paychecks.first().any{it.periodStart==p.periodStart && it.periodEnd==p.periodEnd}
                require(!duplicate){"A paycheck already exists for this period. Cancel this import and open that paycheck to review/edit it. Nothing was overwritten."}
                app.payroll.save(p.copy(id=0,revision=0));saved["recognized"]="";done(null)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){done(e.message?:"Could not save paycheck.")}
            finally{working.value=false}
        }
    }
}
