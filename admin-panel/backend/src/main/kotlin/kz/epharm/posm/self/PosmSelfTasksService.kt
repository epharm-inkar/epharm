package kz.epharm.posm.self

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
) {
    fun tasks(device: PosmDeviceIdentity, req: PosmSelfRequest): PharmacistTaskFeedDto = feed.feed(people.person(device, req).id)
}
