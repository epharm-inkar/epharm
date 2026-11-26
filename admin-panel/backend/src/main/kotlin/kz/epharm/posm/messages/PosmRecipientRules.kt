package kz.epharm.posm.messages

import kz.epharm.posm.attill.AtTillPerson

/**
 * Who a notice goes to when the people are chosen at sending (owner, 26.11.2026). A course notice goes to the people
 * who have not finished the course; the switch «only at a till» narrows any audience to the people who sit at a till at
 * that moment. A notice with neither is judged live at every till check (PosmMessageRules.isFor) and has no list here.
 * Pure rules, no I/O, unit-tested; the SQL that reads the course assignments and the people at tills lives in
 * PosmMessageAudiences.
 *
 * Whether a person who finishes the course after the notice was sent still gets it is decided in the till query
 * (PosmMessageService.COURSE_STILL_OPEN): no, a finished, paused or cancelled assignment stops the notice for them.
 */
object PosmRecipientRules {
    /**
     * The people kept with the notice, sorted, or null when its audience is judged live.
     * [courseOpenIds] are the pharmacists with an open assignment of the course (empty unless the audience is a course);
     * [atTill] is everybody at a till now.
     */
    fun frozenIds(choice: PosmAudienceChoice, courseOpenIds: Set<String>, atTill: List<AtTillPerson>): List<String>? {
        if (!choice.frozen) return null
        val ids = if (choice.onlyAtTill) {
            atTill.filter { inAudience(choice, it, courseOpenIds) }.map { it.pharmacistId }
        } else {
            courseOpenIds.toList()
        }
        return ids.distinct().sorted()
    }

    /** How many people at a till now are in the audience: they see the notice within a minute or two. */
    fun atTillNow(choice: PosmAudienceChoice, courseOpenIds: Set<String>, atTill: List<AtTillPerson>): Int =
        atTill.count { inAudience(choice, it, courseOpenIds) }

    /**
     * Whether a person at a till is in the audience. «pharmacies» takes whoever sits at a till of a chosen pharmacy and
     * the staff of a chosen pharmacy at any till (the card's pharmacy), as PosmMessageRules.isFor does.
     */
    fun inAudience(choice: PosmAudienceChoice, person: AtTillPerson, courseOpenIds: Set<String>): Boolean =
        when (choice.audience) {
            PosmMessageAudience.ALL -> true
            PosmMessageAudience.PHARMACIES ->
                person.tillPharmacyId in choice.pharmacyIds || (person.homePharmacyId != null && person.homePharmacyId in choice.pharmacyIds)
            PosmMessageAudience.PHARMACISTS -> person.pharmacistId in choice.pharmacistIds
            PosmMessageAudience.COURSE -> person.pharmacistId in courseOpenIds
        }
}
