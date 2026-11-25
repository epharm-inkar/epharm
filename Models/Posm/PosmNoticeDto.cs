using System;

namespace CustomerDisplay.Models.Posm
{
    /// <summary>
    /// «ePharm на технических работах» for this till (GET /api/posm/notice, device key). HQ switches it; a pilot till
    /// may stay live during the works. <see cref="ChangedAt"/> is when HQ last changed the switch. <see cref="MessagesAt"/>
    /// (servers from the 1.0.99 release) is when HQ last sent or withdrew a notice to pharmacists: a newer time makes
    /// the till ask for its person's notices at once.
    /// </summary>
    public sealed record PosmNoticeDto(bool Maintenance, string? Title, string? Message, DateTimeOffset? ChangedAt, DateTimeOffset? MessagesAt = null);
}
