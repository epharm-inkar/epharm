package kz.epharm.posm.staff

/** Raw observations are diagnostic; financial binding additionally requires authenticated password and source scope. */
internal object StaffIinObservation {
    private val statuses=setOf("available","missing","invalid","unavailable","ambiguous")
    private val digits=Regex("[0-9]{12}")

    fun valid(context: StaffContext): Boolean = context.standardNIinStatus in statuses &&
        (if(context.standardNIinStatus=="available") context.standardNIin?.let(digits::matches)==true
        else context.standardNIin==null) && StaffIdentityObservation.validNameContext(context) &&
        (context.sourceNamespace==null || context.sourceNamespace.matches(Regex("[a-f0-9]{64}")))

    fun match(iin: String, context: StaffContext): String = when {
        context.standardNIinStatus!="available" || !valid(context) -> "UNKNOWN"
        context.standardNIin==iin -> "MATCH"
        else -> "MISMATCH"
    }
}
