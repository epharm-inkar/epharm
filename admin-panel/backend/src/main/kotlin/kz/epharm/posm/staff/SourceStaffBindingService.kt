package kz.epharm.posm.staff

import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kz.epharm.posm.service.PosmDeviceIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Password-authenticated RPS proof is scoped to the actual device/database/native account. */
@Service
class SourceStaffBindingService(private val jdbc: JdbcTemplate) {
    data class Scope(val pharmacy: String, val device: String, val namespace: String, val user: String)
    data class Recipient(val id: String, val name: String)
    data class Resolution(val exists: Boolean, val recipient: Recipient? = null, val blocksHq: Boolean = false)
    private data class Profile(val id: String, val name: String, val iin: String?, val passwordHash: String?)
    private data class Proof(val method: String, val hash: String)
    private val namespace = Regex("[a-f0-9]{64}")
    companion object {
        /** Shared with HQ mapping writers; callers lock their selected recipient first. */
        fun lockAccount(jdbc: JdbcTemplate, pharmacy: String, nativeUser: String) {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,729182))",
                "${pharmacy.length}:$pharmacy${nativeUser.length}:$nativeUser")
        }
    }


    /** Called only after password proof or a successful check of that same authenticated grant. */
    @Transactional
    fun observe(device: PosmDeviceIdentity, personId: String, source: StaffContext?, grantHash: String) {
        if (source == null || device.legacy) return
        val scope = scope(device.pharmacyId, device.deviceId, source.sourceNamespace, source.standardNUserId) ?: return
        if (source.standardNSessionId.toLongOrNull()?.takeIf { it > 0 } == null || source.workstationId.isBlank()) return
        val person = profile(personId, scope.pharmacy) ?: return
        val match = StaffIdentityObservation.match(person.iin.orEmpty(), person.name, source)
        val proof = proof(person, match.method)?.takeIf { match.status == "MATCH" }
            ?.takeIf { match.method != "NAME" || uniqueName(person, scope.pharmacy) }
        lock(scope)
        val existing = binding(scope)
        val hq = jdbc.queryForList("SELECT pharmacist_id,active FROM posm_pharmacist_mappings WHERE pharmacy_id=? AND external_user_id=?",
            scope.pharmacy, scope.user).singleOrNull()
        val reason = when {
            match.status == "MISMATCH" -> "source_identity_mismatch"
            proof == null -> if (match.method == "NAME" && match.status == "MATCH") "source_name_ambiguous" else null
            hq != null && hq["active"] != true -> "hq_mapping_revoked"
            hq != null && hq["pharmacist_id"] != person.id -> "hq_mapping_conflict"
            existing != null && existing["pharmacist_id"] != person.id -> "source_binding_conflict"
            else -> null
        }
        if (existing == null && proof == null) return
        if (existing == null) {
            val id = UUID.randomUUID()
            jdbc.update("""INSERT INTO posm_source_staff_bindings(id,pharmacy_id,device_id,source_namespace,native_user_id,
                pharmacist_id,proof_method,proof_hash,state,reason_code,conflict_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                id, scope.pharmacy, scope.device, scope.namespace, scope.user, person.id, proof!!.method, proof.hash,
                if (reason == null) "active" else "conflict", reason ?: "verified_${proof.method.lowercase()}",
                if (reason == null) null else Timestamp.from(Instant.now()))
            audit(id, person.id, if (reason == null) "created" else "conflict", reason ?: "verified_${proof.method.lowercase()}", proof.hash)
        } else if (reason != null) {
            val observedHash = proof?.hash ?: observedProofHash(source)
            val previous = jdbc.queryForList("""SELECT claimant_id,reason_code,proof_hash FROM posm_source_staff_binding_audit
                WHERE binding_id=? AND action='conflict' ORDER BY transition_order DESC LIMIT 1""", existing["id"]).singleOrNull()
            if (existing["state"] != "conflict" || previous?.get("claimant_id") != person.id ||
                previous?.get("reason_code") != reason || previous?.get("proof_hash") != observedHash) {
                // A new contradiction invalidates a previous HQ exception; repeated checks do not.
                jdbc.update("""UPDATE posm_source_staff_bindings SET state='conflict',reason_code=?,
                    conflict_at=GREATEST(clock_timestamp(),COALESCE(conflict_at,'epoch')+interval '1 microsecond',
                        COALESCE((SELECT updated_at FROM posm_pharmacist_mappings WHERE pharmacy_id=? AND external_user_id=?),'epoch')+interval '1 microsecond')
                    WHERE id=?""", reason,scope.pharmacy,scope.user,existing["id"])
                audit(existing["id"] as UUID, person.id, "conflict", reason, observedHash)
            }
        } else if (proof != null && existing["state"] == "active") {
            if (existing["proof_method"] != proof.method || existing["proof_hash"] != proof.hash) {
                jdbc.update("UPDATE posm_source_staff_bindings SET proof_method=?,proof_hash=?,confirmed_at=now(),reason_code=? WHERE id=?",
                    proof.method, proof.hash, "verified_${proof.method.lowercase()}", existing["id"])
                audit(existing["id"] as UUID, person.id, "reconfirmed", "verified_${proof.method.lowercase()}", proof.hash)
            }
        }
        // First admitted proof time is immutable for this grant; later source availability is not retroactive.
        if (proof != null && reason == null && (existing == null || existing["state"] == "active")) {
            jdbc.update("""UPDATE posm_device_staff_sessions SET source_verified_at=COALESCE(source_verified_at,clock_timestamp()),
                source_proof_method=COALESCE(source_proof_method,?),source_proof_hash=COALESCE(source_proof_hash,?)
                WHERE token_hash=? AND pharmacist_id=? AND source_namespace=? AND standard_n_user_id=?
                AND standard_n_session_id=? AND workstation_id=? AND revoked_at IS NULL AND expires_at>now()""",
                proof.method, proof.hash, grantHash, person.id, scope.namespace, scope.user, source.standardNSessionId, source.workstationId)
        }
    }

    @Transactional(readOnly = true)
    fun resolve(pharmacy: String, device: String?, sourceNamespace: String?, nativeUser: String?,
        at: Instant = Instant.now(), nativeSession: String? = null, workstation: String? = null): Resolution {
        val scope = scope(pharmacy, device, sourceNamespace, nativeUser) ?: return Resolution(false)
        return resolve(scope, at, nativeSession, workstation)
    }

    /** Locks the recipient before the source binding, matching staff/password writers' lock order. */
    @Transactional
    fun authorizes(personId: String, pharmacy: String, device: String?, sourceNamespace: String?, nativeUser: String?,
        at: Instant, nativeSession: String, workstation: String): Boolean {
        val scope = scope(pharmacy, device, sourceNamespace, nativeUser) ?: return false
        jdbc.queryForList("SELECT id FROM pharmacists WHERE id=? FOR UPDATE", personId)
        lock(scope)
        return resolve(scope, at, nativeSession, workstation).recipient?.id == personId
    }

    private fun resolve(scope: Scope, at: Instant, nativeSession: String?, workstation: String?): Resolution {
        val row = binding(scope) ?: return Resolution(false)
        val conflictAt = (row["conflict_at"] as? Timestamp)?.toInstant()
        val hq = jdbc.queryForList("SELECT pharmacist_id,updated_at,active FROM posm_pharmacist_mappings WHERE pharmacy_id=? AND external_user_id=?",
            scope.pharmacy, scope.user).singleOrNull()
        val reviewedOverride = conflictAt != null && hq?.get("active") == true &&
            (hq["updated_at"] as? Timestamp)?.toInstant()?.isAfter(conflictAt) == true
        if (row["state"] != "active" || hq != null && (hq["active"] != true || hq["pharmacist_id"] != row["pharmacist_id"]))
            return Resolution(true, blocksHq = !reviewedOverride)
        val person = profile(row["pharmacist_id"].toString(), scope.pharmacy) ?: return Resolution(true)
        if (proof(person, row["proof_method"].toString())?.hash != row["proof_hash"] ||
            row["proof_method"] == "NAME" && !uniqueName(person, scope.pharmacy) ||
            (row["created_at"] as Timestamp).toInstant().isAfter(at)) return Resolution(true)
        val instant = Timestamp.from(at)
        val grants = jdbc.queryForList("""SELECT source_proof_method,source_proof_hash FROM posm_device_staff_sessions
            WHERE pharmacist_id=? AND pharmacy_id=? AND device_id=? AND source_namespace=? AND standard_n_user_id=?
            AND created_at<=? AND source_verified_at<=? AND expires_at>? AND (revoked_at IS NULL OR revoked_at>?)
            AND password_fingerprint=? AND (?::text IS NULL OR standard_n_session_id=?)
            AND (?::text IS NULL OR workstation_id=?)""", person.id, scope.pharmacy, scope.device, scope.namespace, scope.user,
            instant, instant, instant, instant, person.passwordHash?.let(::digest), nativeSession, nativeSession, workstation, workstation)
        val proven = grants.any { proof(person, it["source_proof_method"].toString())?.hash == it["source_proof_hash"] &&
            (it["source_proof_method"] != "NAME" || uniqueName(person, scope.pharmacy)) }
        return Resolution(true, if (proven) Recipient(person.id, person.name) else null)
    }

    private fun observedProofHash(source: StaffContext): String = if (source.standardNIinStatus == "available")
        digest("IIN:${source.standardNIin.orEmpty()}") else digest("NAME:${source.standardNFullName?.let(StaffIdentityObservation::normalizedName).orEmpty()}")

    private fun profile(id: String, pharmacy: String): Profile? = jdbc.query("""SELECT id,name,iin,password_hash FROM pharmacists
        WHERE id=? AND pharmacy_id=? AND status='active'""", { rs, _ -> Profile(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)) }, id, pharmacy).singleOrNull()
    private fun uniqueName(person: Profile, pharmacy: String): Boolean {
        val normalized = StaffIdentityObservation.normalizedName(person.name) ?: return false
        return jdbc.query("SELECT name FROM pharmacists WHERE pharmacy_id=? AND status='active'", { rs, _ -> rs.getString(1) }, pharmacy)
            .count { StaffIdentityObservation.normalizedName(it) == normalized } == 1
    }
    private fun proof(person: Profile, method: String): Proof? = when (method) {
        "IIN" -> person.iin?.takeIf { it.matches(Regex("[0-9]{12}")) }?.let { Proof(method, digest("IIN:$it")) }
        "NAME" -> StaffIdentityObservation.normalizedName(person.name)?.let { Proof(method, digest("NAME:$it")) }
        else -> null
    }
    private fun scope(pharmacy: String?, device: String?, ns: String?, user: String?): Scope? =
        if (pharmacy.isNullOrBlank() || device.isNullOrBlank() || user.isNullOrBlank() || ns?.matches(namespace) != true ||
            listOf(pharmacy,device,user).any { it.length>128 || it.any(Char::isISOControl) }) null else Scope(pharmacy,device,ns,user)
    private fun binding(scope: Scope) = jdbc.queryForList("SELECT * FROM posm_source_staff_bindings WHERE pharmacy_id=? AND device_id=? AND source_namespace=? AND native_user_id=?",
        scope.pharmacy,scope.device,scope.namespace,scope.user).singleOrNull()
    private fun lock(scope: Scope) {
        lockAccount(jdbc,scope.pharmacy,scope.user)
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,729183))",
            listOf(scope.pharmacy,scope.device,scope.namespace,scope.user).joinToString("") { "${it.length}:$it" })
    }
    private fun audit(id: UUID, person: String, action: String, reason: String, proofHash: String) {
        jdbc.update("""INSERT INTO posm_source_staff_binding_audit(id,binding_id,claimant_id,action,reason_code,proof_hash,created_at)
            VALUES (?,?,?,?,?,?,clock_timestamp())""", UUID.randomUUID(), id, person, action, reason, proofHash)
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
