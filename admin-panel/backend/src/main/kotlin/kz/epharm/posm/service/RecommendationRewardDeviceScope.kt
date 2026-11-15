package kz.epharm.posm.service

/** Empty is a closed rollout. Fleet activation requires an explicit standalone '*'. */
class RecommendationRewardDeviceScope(raw: String) {
    private val devices = raw.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    init {
        require(devices.size <= 1500 && devices.all { it.length <= 128 && it.none(Char::isISOControl) }) {
            "Invalid recommendation reward device allowlist"
        }
        require("*" !in devices || devices.size == 1) { "Fleet wildcard must be explicit and standalone" }
    }
    fun allows(device: PosmDeviceIdentity): Boolean = !device.legacy && allowsId(device.deviceId)
    fun allowsId(deviceId: String?): Boolean = !deviceId.isNullOrBlank() && ("*" in devices || deviceId in devices)
}
