package kz.epharm.posm.self

import kz.epharm.posm.attill.TillPersonPresence
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.training.tasks.PharmacistTaskFeedDto
import kz.epharm.training.tasks.PharmacistTaskFeedService
import org.springframework.stereotype.Service

/**
 * The till's task list for the person signed in to Standard-N at it: their training assignments as «задания».
 * The person is found exactly as the personal window finds them (PosmSelfPersonResolver). Nothing typed at the
 * till is saved here; the request may carry the window's other fields, they are ignored.
 */
@Service
class PosmSelfTasksService(
    private val people: PosmSelfPersonResolver,
    private val feed: PharmacistTaskFeedService,
    private val presence: TillPersonPresence,
) {
    fun tasks(device: PosmDeviceIdentity, req: PosmSelfRequest): PharmacistTaskFeedDto {
        val person = people.person(device, req)
        // The till names the person signed in to Standard-N: that is who is at the till now (AtTillService).
        presence.record(device, person.id)
        return feed.feed(person.id)
    }
}
