using System;

namespace CustomerDisplay.Services;

/// <summary>
/// How often POSM asks the backend for things that rarely change. On 23.11.2026 the fleet made
/// 3.9 million such requests a day (heartbeat 1.05 million, merchandising tasks 1.05 million,
/// staff capabilities 510 thousand, fulfillment capabilities 424 thousand while online orders
/// were switched off). The owner's rule: data that is not needed in real time is asked for at
/// most once an hour. Recommendations, receipts and sales are not throttled here.
/// </summary>
public static class PosmPollIntervals
{
    /// <summary>Heartbeat for the "devices online" counter; the backend keeps a device online for 150 s.</summary>
    public static readonly TimeSpan Heartbeat = TimeSpan.FromSeconds(60);

    /// <summary>A switched-off feature is asked again once an hour.</summary>
    public static readonly TimeSpan DisabledFeatureRecheck = TimeSpan.FromHours(1);

    /// <summary>Who sells at the till is read this often, for the heartbeat only (no sign-in at the till since 1.0.97).</summary>
    public static readonly TimeSpan StaffSourceDiagnostic = TimeSpan.FromMinutes(5);

    /// <summary>
    /// Online-order queue: poll at the configured rate only while the queue is shown; when the
    /// backend says orders are off (or the device may not use them) ask again in an hour.
    /// </summary>
    public static TimeSpan NextFulfillmentPoll(bool queueAvailable, int configuredPollSec) =>
        queueAvailable ? TimeSpan.FromSeconds(Math.Clamp(configuredPollSec, 5, 60)) : DisabledFeatureRecheck;
}
