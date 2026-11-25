using System.Collections.Generic;

namespace CustomerDisplay.Services;

/// <summary>
/// Only fixed operational codes leave the till in the heartbeat; never identifiers or responses. The sign-in codes
/// stay accepted because the server and the fleet table still read them from older tills.
/// </summary>
public static class StaffAuthDiagnostics
{
    private static readonly HashSet<string> SourceStatuses = new(System.StringComparer.Ordinal) {
        "initializing", "unknown", "local_disabled", "service_disabled", "resolved",
        "no_process", "process_unavailable", "ambiguous_process", "process_changing",
        "database_disabled", "database_unavailable", "no_session", "ambiguous_session", "unknown_workstation",
        "unsupported_session_time", "future_session_time", "stale_session", "invalid_session_time",
        "unsupported_process_time", "session_predates_process",
    };
    public static string NormalizeSource(string? status) => status != null && SourceStatuses.Contains(status) ? status : "unknown";

    private static readonly HashSet<string> Statuses = new(System.StringComparer.Ordinal) {
        "initializing", "local_disabled", "device_auth_required", "forbidden", "server_disabled",
        "unsupported_protocol", "endpoint_unavailable", "network_unavailable", "enabled",
        "no_process", "process_unavailable", "ambiguous_process", "process_changing",
        "database_disabled", "database_unavailable", "no_session", "ambiguous_session", "unknown_workstation",
        "unsupported_session_time", "future_session_time", "stale_session", "invalid_session_time",
        "unsupported_process_time", "session_predates_process", "login_required", "login_visible", "authenticated",
        "session_revalidating", "pending_approval",
        "staff_bypass_service_unavailable", "staff_bypass_sms_unavailable", "staff_bypass_sms_wait", "staff_bypass_identity_unavailable",
    };

    public static bool IsKnown(string? status) => status != null && Statuses.Contains(status);
}
