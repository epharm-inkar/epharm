using System;

namespace CustomerDisplay.Models.Posm
{
    /// <summary>
    /// «ePharm на технических работах» for this till (GET /api/posm/notice, device key). HQ switches it; a pilot till
    /// may stay live during the works. <see cref="ChangedAt"/> is when HQ last changed the switch.
    /// </summary>
    public sealed record PosmNoticeDto(bool Maintenance, string? Title, string? Message, DateTimeOffset? ChangedAt);
}
