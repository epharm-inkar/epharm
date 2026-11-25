using System;
using System.Collections.Generic;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Order in which POSM tries Firebird logins for the Standard-N database (25.11.2026).
    /// Standard-N keeps its own encrypted form of the password in options.ini, and Firebird does not accept it,
    /// so on reading tills the login that works is the posm.json one (SYSDBA by default) on localhost. That
    /// reaches the database only when it lives on this computer. A second cash desk opens the database on the
    /// pharmacy's main computer (options.ini server MANAGER, KASSA, KASSA1, ...), and POSM never tried that
    /// server with the posm.json login: the till stayed database_unavailable, saw no receipts and could not tell
    /// who sells («Сотрудник аптеки» in the staff window). Measured 25.11: all 92 online tills without the
    /// database had it on another computer; all 39 sampled reading tills used the posm.json login on localhost,
    /// five of them on the very computers their neighbours could not open.
    /// </summary>
    public static class StandardNDbCandidates
    {
        public const string OptionsIni = "running Standard-N options.ini";
        public const string OptionsServerWithConfigLogin = "options.ini server, posm.json login";
        public const string ConfigFallback = "posm.json/environment fallback";

        public sealed record Candidate(string Source, string Host, int Port, string User, string Password, string Key)
        {
            // Records print every property by default; a candidate must never print its password.
            public override string ToString() => $"{Source} {Host}:{Port} {User}";
        }

        /// <summary>
        /// The running cashier's options.ini comes first (it names the server). When that server is another
        /// computer, the same server with the posm.json login follows, before the posm.json host (localhost by
        /// default), which on a second cash desk is not where the database is.
        /// </summary>
        public static List<Candidate> Build(
            bool resolvedFromOptionsIni,
            string? optionsHost,
            int? optionsPort,
            string? optionsUser,
            string? optionsPassword,
            string? configHost,
            int configPort,
            string? configUser,
            string? configPassword,
            string? machineName)
        {
            var candidates = new List<Candidate>();
            var seen = new HashSet<string>(StringComparer.Ordinal);

            void Add(string source, string? host, int? port, string? user, string? password)
            {
                var normalizedHost = string.IsNullOrWhiteSpace(host) ? "localhost" : host.Trim();
                var normalizedUser = string.IsNullOrWhiteSpace(user) ? "SYSDBA" : user.Trim();
                var normalizedPassword = password ?? "";
                var normalizedPort = port ?? configPort;
                var key = $"{normalizedHost.ToUpperInvariant()}|{normalizedPort}|{normalizedUser.ToUpperInvariant()}|{normalizedPassword}";
                if (!seen.Add(key)) return;
                candidates.Add(new Candidate(source, normalizedHost, normalizedPort, normalizedUser, normalizedPassword, key));
            }

            if (resolvedFromOptionsIni)
            {
                Add(OptionsIni, optionsHost, optionsPort, optionsUser, optionsPassword);
                if (!IsThisComputer(optionsHost, machineName))
                    Add(OptionsServerWithConfigLogin, optionsHost, optionsPort, configUser, configPassword);
            }

            Add(ConfigFallback, configHost, configPort, configUser, configPassword);
            return candidates;
        }

        /// <summary>True when the Firebird server named in options.ini is this computer itself.</summary>
        public static bool IsThisComputer(string? host, string? machineName)
        {
            var server = (host ?? "").Trim();
            if (server.Length == 0 || server == "." || server == "127.0.0.1" || server == "::1") return true;
            if (server.Equals("localhost", StringComparison.OrdinalIgnoreCase)) return true;
            var machine = (machineName ?? "").Trim();
            return machine.Length > 0 && server.Equals(machine, StringComparison.OrdinalIgnoreCase);
        }
    }
}
