using System;
using System.IO;
using System.Linq;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace CustomerDisplay.Services
{
    public sealed partial class ReceiptArtifactStore
    {
        private const int MaximumStaleArchives = 2048;
        private const long MaximumStaleArchiveBytes = 64 * 1024 * 1024;
        private const string StaleGroupsFolder = "stale-observed-groups";

        /// <summary>
        /// Frees the hot slot of an observed group that cannot resolve (see <see cref="ObservedGroupCapacity"/>).
        /// The exact original group is copied to a private local archive first; no sale, ACK or reward is
        /// created and outbox/draft evidence is untouched. A later observation of the same Standard-N
        /// document starts a fresh group; the backend deduplicates sales by DOCS.ID.
        /// Returns false when the group is gone or has changed since <paramref name="expected"/> was read.
        /// </summary>
        public bool ReleaseStaleObservedGroup(StandardNObservedReceiptGroup expected, string reason)
        {
            ValidateObservedGroup(expected);
            if (reason != ObservedGroupCapacity.StaleReason && reason != ObservedGroupCapacity.CapacityReason)
                throw new ArgumentException("Unknown observed group release reason.", nameof(reason));
            lock (_gate)
            {
                using var mutation = LockObservedMutations();
                var expectedJson = JsonSerializer.Serialize(expected, EpharmJson.Options);
                var groups = ReadObservedGroupsLocked(includeArchived: true);
                var key = ObservedStorageKey(expected);
                var original = groups.SingleOrDefault(group => ObservedStorageKey(group) == key);
                if (original == null || JsonSerializer.Serialize(original, EpharmJson.Options) != expectedJson) return false;
                WriteStaleArchiveLocked(key, new StaleGroupArchive
                {
                    Reason = reason, ReleasedAt = _utcNow(), OriginalGroupJson = expectedJson,
                });
                WriteObservedGroupsLocked(groups.Where(group => ObservedStorageKey(group) != key).ToArray());
                return true;
            }
        }

        private string StaleGroupsRoot => Path.Combine(_receiptRoot, StaleGroupsFolder);

        private void WriteStaleArchiveLocked(string storageKey, StaleGroupArchive archive)
        {
            RejectLink(_receiptRoot);
            var root = StaleGroupsRoot;
            var created = !Directory.Exists(root);
            Directory.CreateDirectory(root); RejectLink(root);
            if (created) MakePrivateDirectory(root);
            var payload = JsonSerializer.Serialize(archive, EpharmJson.Options);
            var bytes = JsonSerializer.SerializeToUtf8Bytes(new ObservedGroupEnvelope
            { SchemaVersion = 1, PayloadJson = payload, Sha256 = HashBytes(Encoding.UTF8.GetBytes(payload)) }, EpharmJson.Options);
            if (bytes.Length > MaximumUnresolvedEntryBytes)
                throw new InvalidDataException("Stale observed group exceeds the archive entry bound; hot evidence retained.");
            PruneStaleArchivesLocked(root, bytes.Length);
            // Names sort by release time, so pruning removes the oldest local copies first.
            var name = archive.ReleasedAt.UtcDateTime.ToString("yyyyMMdd'T'HHmmssfff'Z'") + "-" +
                       HashBytes(Encoding.UTF8.GetBytes(storageKey))[..16] + "-" + Guid.NewGuid().ToString("N")[..8] + ".json";
            var target = Path.Combine(root, name);
            var temporary = target + ".tmp-" + Guid.NewGuid().ToString("N");
            try
            {
                using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                {
                    if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                    stream.Write(bytes); stream.Flush(flushToDisk: true);
                }
                File.Move(temporary, target);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
        }

        /// <summary>
        /// The stale archive is a local diagnostic copy, never payment proof, so it is bounded by
        /// dropping its oldest entries instead of blocking capture again.
        /// </summary>
        private static void PruneStaleArchivesLocked(string root, long incomingBytes)
        {
            var files = Directory.EnumerateFiles(root)
                .Select(path => new FileInfo(path))
                .Where(file => (file.Attributes & FileAttributes.ReparsePoint) == 0)
                .OrderBy(file => file.Name, StringComparer.Ordinal).ToList();
            var total = files.Sum(file => file.Length);
            var index = 0;
            while (index < files.Count && (files.Count - index >= MaximumStaleArchives || total + incomingBytes > MaximumStaleArchiveBytes))
            {
                total -= files[index].Length;
                files[index].Delete();
                index++;
            }
        }

        private static void MakePrivateDirectory(string path)
        {
            if (OperatingSystem.IsWindows())
            {
                var acl = new DirectorySecurity(); acl.SetAccessRuleProtection(true, false);
                foreach (var sid in new[] { WindowsIdentity.GetCurrent().User!, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null) })
                    acl.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                        InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
                new DirectoryInfo(path).SetAccessControl(acl);
            }
            else File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }

        private sealed class StaleGroupArchive
        {
            public int SchemaVersion { get; set; } = 1;
            public string Reason { get; set; } = "";
            public DateTimeOffset ReleasedAt { get; set; }
            public string OriginalGroupJson { get; set; } = "";
        }
    }
}
