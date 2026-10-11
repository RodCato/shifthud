package com.shifthud.domain.payroll

import java.math.BigDecimal
import java.math.RoundingMode

/** Display only; exact reconciliation status and differences remain unchanged. */
fun hoursDifferenceLabel(hours: BigDecimal): String {
    val seconds = hours.multiply(BigDecimal(3600)).setScale(3,RoundingMode.HALF_UP).stripTrailingZeros()
    return (if(seconds.signum()>0) "+" else "") + seconds.toPlainString() + " seconds"
}
fun reconciliationSummary(r: PayrollReconciliation): String {
    if(r.netDifference != 0L || r.depositDifference != 0L) return if(r.netDifference==null || r.depositDifference==null)
        "Payroll net/deposits not yet comparable" else "Material unresolved payroll net/deposit difference"
    if(r.hoursDifference==null || r.grossDifference==null) return "Payroll net/deposits balanced · Work comparison incomplete"
    if(r.hoursDifference!!.signum()==0 && r.grossDifference==0L) return "Payroll net/deposits balanced · Work matches"
    // Explicit presentation bands only, never a reconciliation tolerance.
    val minor = r.hoursDifference!!.abs() <= BigDecimal.ONE.divide(BigDecimal(60),12,RoundingMode.HALF_UP) && kotlin.math.abs(r.grossDifference!!) <= 100
    return "Payroll net/deposits balanced · " + if(minor) "Minor hours or gross difference" else "Material unresolved work difference"
}
