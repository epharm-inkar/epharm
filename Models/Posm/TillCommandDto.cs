using System;

namespace CustomerDisplay.Models.Posm
{
    /// <summary>
    /// A command HQ gave this till from the admin (POST /api/posm/commands/next): «diagnostics» or «restart», nothing
    /// else. It expires 30 minutes after HQ created it.
    /// </summary>
    public sealed record TillCommandDto(string Id, string Action, DateTimeOffset? ExpiresAt);
}
