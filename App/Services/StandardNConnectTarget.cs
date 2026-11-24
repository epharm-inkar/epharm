using System;
using System.Globalization;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Firebird server and database from the Standard-N options.ini <c>[Connect]</c> base value.
    /// Standard-N often keeps the server inside that value (<c>MANAGER:C:\Standart-N\base\ztrade.fdb</c>,
    /// <c>MANAGER/3050:C:\...</c>, <c>MANAGER:ztrade</c>) without a separate <c>Server=</c> key. A second cash
    /// desk of a pharmacy then opens the database on the pharmacy's main computer over the network; dropping
    /// the server part made POSM look for the database on the cash desk itself (database_unavailable on about
    /// a hundred second cash desks since 3.11, when receipt capture started to require the database).
    /// </summary>
    public static class StandardNConnectTarget
    {
        public sealed record Target(string? Host, int? Port, string Database);

        public static Target? Parse(string? value)
        {
            var text = (value ?? "").Trim().Trim('"', '\'');
            if (text.Length == 0) return null;

            // Firebird 3 URL form: inet://host[:port]/path
            if (text.StartsWith("inet://", StringComparison.OrdinalIgnoreCase))
            {
                var rest = text.Substring("inet://".Length);
                var slash = rest.IndexOf('/');
                if (slash <= 0 || slash == rest.Length - 1) return new Target(null, null, text);
                var (urlHost, urlPort) = SplitServer(rest.Substring(0, slash), ':');
                return new Target(urlHost, urlPort, rest.Substring(slash + 1));
            }

            // host:C:\path or host/port:C:\path — everything before the drive letter is the server part.
            var drive = text.IndexOf(":\\", StringComparison.Ordinal);
            if (drive >= 1 && char.IsLetter(text[drive - 1]))
            {
                if (drive >= 3 && text[drive - 2] == ':')
                {
                    var (host, port) = SplitServer(text.Substring(0, drive - 2), '/');
                    return new Target(host, port, text.Substring(drive - 1));
                }
                return new Target(null, null, text.Substring(drive - 1));
            }

            // host:alias or host/port:alias on the server; a UNC or relative path keeps its own form.
            var colon = text.IndexOf(':');
            if (colon > 0 && colon < text.Length - 1 && text.IndexOf('\\') < 0)
            {
                var (host, port) = SplitServer(text.Substring(0, colon), '/');
                if (host != null) return new Target(host, port, text.Substring(colon + 1));
            }
            return new Target(null, null, text);
        }

        private static (string? Host, int? Port) SplitServer(string server, char portSeparator)
        {
            var value = server.Trim();
            if (value.Length == 0) return (null, null);
            int? port = null;
            var separator = value.LastIndexOf(portSeparator);
            if (separator > 0 && int.TryParse(value.Substring(separator + 1), NumberStyles.None, CultureInfo.InvariantCulture, out var parsed)
                && parsed > 0 && parsed < 65536)
            {
                port = parsed;
                value = value.Substring(0, separator).Trim();
            }
            return value.Length == 0 ? (null, null) : (value, port);
        }
    }
}
