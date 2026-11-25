using System;
using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// HTTP-клиент к backend POSM Rules Engine. Все вызовы fail-safe: при сети/таймауте/ошибке
    /// возвращают null/false и НЕ бросают — касса не должна тормозить или падать из-за backend.
    /// Аутентификация устройства — заголовок X-Posm-Key.
    /// </summary>
    /// <summary>Outcome of one attempt to deliver a queued sale.</summary>
    public enum SaleDeliveryResult
    {
        Delivered,
        /// <summary>Network, timeout or server error: try again later.</summary>
        Retry,
        /// <summary>The backend refused this sale for good: retrying the same payload cannot succeed.</summary>
        Rejected,
    }

    /// <summary>Outcome of a notice report (shown or closed).</summary>
    public enum MessageAckResult
    {
        Done,
        /// <summary>No network, a server error or a key problem: send again later.</summary>
        Retry,
        /// <summary>Refused for good: the notice is withdrawn, not for this person, or the person is unknown.</summary>
        Gone,
    }

    public static class MessageAckResults
    {
        /// <summary>400, 403, 404, 409 and 410 are final; 401 (key), 429 and 5xx are tried again.</summary>
        public static MessageAckResult ForStatus(int status) => status switch
        {
            >= 200 and < 300 => MessageAckResult.Done,
            400 or 403 or 404 or 409 or 410 or 422 => MessageAckResult.Gone,
            _ => MessageAckResult.Retry,
        };
    }

    public sealed class EpharmApiClient : IMerchTaskApi, IDisposable
    {
        private readonly HttpClient _http;
        private readonly BackendFailoverHandler _failover;
        private readonly Action<string>? _log;
        private readonly TimeSpan _recommendTimeout;
        // На Windows VM первый DNS/TLS connect к публичному домену может занимать
        // заметно дольше, чем на сервере/маке. Эти вызовы фоновые и не блокируют кассу, поэтому
        // лучше дать сети восстановиться, чем регулярно считать живой backend "таймаутом".
        private readonly TimeSpan _playlistTimeout = TimeSpan.FromSeconds(20);
        private readonly TimeSpan _heartbeatTimeout = TimeSpan.FromSeconds(20);
        private readonly TimeSpan _merchTaskTimeout = TimeSpan.FromSeconds(8);
        private DateTime _lastHeartbeatErrorLog = DateTime.MinValue;
        private string? _lastHeartbeatErrorKey;

        public string? LastPlaylistError { get; private set; }
        public string? LastHeartbeatError { get; private set; }
        public int? RewardEvidenceProtocol { get; private set; }
        public Uri ActiveBackendBaseUri => _failover.ActiveEndpoint;

        private static readonly JsonSerializerOptions JsonOpts = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
        };

        public EpharmApiClient(EpharmConfig cfg, Action<string>? log = null)
        {
            _log = log;
            // Рекомендации держим быстрыми (per-call CTS ниже), а ОБЩИЙ таймаут клиента — щедрый:
            // плейлист/heartbeat/sales/cdp при 700мс часто таймаутили на чуть медленной сети
            // (отсюда «видео не стягивается»). Теперь общий лимит не режет фоновые запросы раньше
            // их собственного timeout, а recommend ограничен отдельно.
            _recommendTimeout = TimeSpan.FromMilliseconds(Math.Max(200, cfg.RecommendTimeoutMs));
            var endpoints = cfg.GetBackendBaseUris();
            _failover = new BackendFailoverHandler(endpoints, _log);
            _http = new HttpClient(_failover)
            {
                BaseAddress = endpoints[0],
                Timeout = TimeSpan.FromSeconds(30),
            };
            _http.DefaultRequestHeaders.Add("X-Posm-Key", cfg.DeviceKey);
        }

        /// <summary>Человекочитаемая причина сбоя запроса для лога (таймаут / HTTP-код / сеть).</summary>
        private static string Describe(Exception ex) => ex switch
        {
            TaskCanceledException or OperationCanceledException => "таймаут (нет ответа вовремя)",
            HttpRequestException h when h.StatusCode is { } sc => $"HTTP {(int)sc} {sc}",
            HttpRequestException h when h.Message.Contains("SSL", StringComparison.OrdinalIgnoreCase) ||
                h.Message.Contains("TLS", StringComparison.OrdinalIgnoreCase) =>
                "временный TLS/SSL сбой соединения",
            HttpRequestException => "временно нет связи с backend",
            _ => ex.Message,
        };

        private static bool ShouldLog(ref DateTime lastAt, ref string? lastKey, string key, int everySec = 60)
        {
            var now = DateTime.Now;
            if (lastKey == key && (now - lastAt).TotalSeconds < everySec) return false;
            lastKey = key;
            lastAt = now;
            return true;
        }

        /// <summary>Корзина → рекомендации. null при любой ошибке/таймауте (popup не покажется).</summary>
        /// <summary>Personal ePharm window: the Standard-N user at this till, earnings and profile (no login).</summary>
        public Task<PosmSelfResult<PosmSelfDto>> GetSelfAsync(PosmSelfRequestDto req, CancellationToken ct = default) =>
            PostSelfAsync<PosmSelfDto>("/api/posm/self", req, ct);

        /// <summary>Saves the phone (no SMS) and/or password typed in the personal window.</summary>
        public Task<PosmSelfResult<PosmSelfDto>> SaveSelfContactAsync(PosmSelfRequestDto req, CancellationToken ct = default) =>
            PostSelfAsync<PosmSelfDto>("/api/posm/self/contact", req, ct);

        /// <summary>HQ tasks of the person at this till (training assignments with status), 1.0.96.</summary>
        public Task<PosmSelfResult<PosmSelfTasksDto>> GetSelfTasksAsync(PosmSelfRequestDto req, CancellationToken ct = default) =>
            PostSelfAsync<PosmSelfTasksDto>("/api/posm/self/tasks", req, ct);

        /// <summary>A fresh one-time link that opens the web cabinet signed in (for the «Мой кабинет» button).</summary>
        public Task<PosmSelfResult<PosmCabinetLinkDto>> GetCabinetLinkAsync(PosmSelfRequestDto req, CancellationToken ct = default) =>
            PostSelfAsync<PosmCabinetLinkDto>("/api/posm/self/cabinet-link", req, ct);

        /// <summary>Notices from HQ for the person at this till: open ones and the last 30 days (1.0.99).</summary>
        public Task<PosmSelfResult<PosmSelfMessagesDto>> GetSelfMessagesAsync(PosmSelfRequestDto req, CancellationToken ct = default) =>
            PostSelfAsync<PosmSelfMessagesDto>("/api/posm/self/messages", req with { Link = false }, ct);

        /// <summary>
        /// Reports that a notice appeared on screen or that the person closed it. Done: the server took it. Gone: it never
        /// will (the notice was withdrawn, is not for this person, or the person is unknown), stop sending. Retry: no
        /// network, a server error or a key problem, send again later.
        /// </summary>
        public async Task<MessageAckResult> AckSelfMessageAsync(PosmSelfRequestDto req, string messageId, bool closed, CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(10));
            var path = "/api/posm/self/messages/" + Uri.EscapeDataString(messageId) + (closed ? "/closed" : "/shown");
            try
            {
                using var resp = await _http.PostAsJsonAsync(path, req with { Link = false, Phone = null, Password = null }, JsonOpts, cts.Token).ConfigureAwait(false);
                if (resp.IsSuccessStatusCode) return MessageAckResult.Done;
                _log?.Invoke($"self messages ack: HTTP {(int)resp.StatusCode}");
                return MessageAckResults.ForStatus((int)resp.StatusCode);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _log?.Invoke($"self messages ack: {Describe(ex)}");
                return MessageAckResult.Retry;
            }
        }

        private DateTime _noticeLogAt;
        private string? _noticeLogKey;

        /// <summary>«ePharm на технических работах» for this till; null when the server did not answer (keep the last state).</summary>
        public async Task<PosmNoticeDto?> GetNoticeAsync(CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(10));
            try
            {
                using var resp = await _http.GetAsync("/api/posm/notice", cts.Token).ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode)
                {
                    if (ShouldLog(ref _noticeLogAt, ref _noticeLogKey, "http" + (int)resp.StatusCode, 900))
                        _log?.Invoke($"notice: HTTP {(int)resp.StatusCode}");
                    return null;
                }
                return await resp.Content.ReadFromJsonAsync<PosmNoticeDto>(JsonOpts, cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                if (ShouldLog(ref _noticeLogAt, ref _noticeLogKey, "error", 900)) _log?.Invoke($"notice: {Describe(ex)}");
                return null;
            }
        }

        private DateTime _commandLogAt;
        private string? _commandLogKey;

        /// <summary>The next command HQ gave this till (diagnostics or restart); null when there is none or no answer.</summary>
        public async Task<TillCommandDto?> NextCommandAsync(CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(15));
            try
            {
                using var resp = await _http.PostAsync("/api/posm/commands/next", null, cts.Token).ConfigureAwait(false);
                if (resp.StatusCode == HttpStatusCode.NoContent) return null;
                if (!resp.IsSuccessStatusCode)
                {
                    if (ShouldLog(ref _commandLogAt, ref _commandLogKey, "http" + (int)resp.StatusCode, 3600))
                        _log?.Invoke($"commands: HTTP {(int)resp.StatusCode}");
                    return null;
                }
                return await resp.Content.ReadFromJsonAsync<TillCommandDto>(JsonOpts, cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                if (ShouldLog(ref _commandLogAt, ref _commandLogKey, "error", 3600)) _log?.Invoke($"commands: {Describe(ex)}");
                return null;
            }
        }

        /// <summary>Answers a command: done or failed, a short message and, for diagnostics, the zip (at most 5 MB).</summary>
        public async Task<bool> SendCommandResultAsync(string id, string status, string message, byte[]? zip, CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(zip == null ? 15 : 120));
            try
            {
                using var form = new MultipartFormDataContent();
                form.Add(new StringContent(status), "status");
                form.Add(new StringContent(message.Length <= 2000 ? message : message[..2000]), "message");
                if (zip != null)
                {
                    var file = new ByteArrayContent(zip);
                    file.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/zip");
                    form.Add(file, "file", "diagnostics.zip");
                }
                using var resp = await _http.PostAsync("/api/posm/commands/" + Uri.EscapeDataString(id) + "/result", form, cts.Token).ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode) _log?.Invoke($"commands result: HTTP {(int)resp.StatusCode}");
                return resp.IsSuccessStatusCode;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _log?.Invoke($"commands result: {Describe(ex)}");
                return false;
            }
        }

        private async Task<PosmSelfResult<T>> PostSelfAsync<T>(string path, PosmSelfRequestDto req, CancellationToken ct) where T : class
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(10));
            try
            {
                using var resp = await _http.PostAsJsonAsync(path, req, JsonOpts, cts.Token).ConfigureAwait(false);
                if (resp.IsSuccessStatusCode)
                    return new(await resp.Content.ReadFromJsonAsync<T>(JsonOpts, cts.Token).ConfigureAwait(false), null);
                string? message = null;
                try
                {
                    using var body = await JsonDocument.ParseAsync(
                        await resp.Content.ReadAsStreamAsync(cts.Token).ConfigureAwait(false), cancellationToken: cts.Token).ConfigureAwait(false);
                    if (body.RootElement.TryGetProperty("message", out var m)) message = m.GetString();
                }
                catch { }
                _log?.Invoke($"self {path}: HTTP {(int)resp.StatusCode}");
                return new(null, string.IsNullOrWhiteSpace(message) ? "Сервер ePharm сейчас не ответил. Попробуйте через минуту." : message);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _log?.Invoke($"self {path}: {Describe(ex)}");
                return new(null, "Нет связи с сервером ePharm. Проверьте интернет и попробуйте ещё раз.");
            }
        }

        public async Task<RecommendResponse?> RecommendAsync(RecommendRequest req, CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(_recommendTimeout);
            try
            {
                using var resp = await _http.PostAsJsonAsync("/api/posm/recommend", req, JsonOpts, cts.Token)
                    .ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode)
                {
                    _log?.Invoke($"recommend: HTTP {(int)resp.StatusCode} {resp.StatusCode}");
                    return null;
                }
                return await resp.Content.ReadFromJsonAsync<RecommendResponse>(JsonOpts, cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _log?.Invoke($"recommend: {Describe(ex)}");
                return null;
            }
        }

        /// <summary>
        /// Фиксация результата рекомендации (accepted/rejected). false при ошибке —
        /// вызов уходит в OfflineOutbox для гарантированной доставки.
        /// </summary>
        public async Task<bool> RecordOutcomeAsync(string eventId, OutcomeRequest req, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http
                    .PostAsJsonAsync($"/api/posm/recommendations/{eventId}/outcome", req, JsonOpts, ct)
                    .ConfigureAwait(false);
                return resp.IsSuccessStatusCode;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// Активный плейлист 2-го монитора (Stage 3). null при ошибке/оффлайне → клиент крутит
        /// локальный promo.mp4. Не бросает.
        /// </summary>
        public async Task<ActivePlaylist?> GetActivePlaylistAsync(string? pharmacyId = null, CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(_playlistTimeout);
            try
            {
                var url = "/api/posm/playlists/active";
                if (!string.IsNullOrWhiteSpace(pharmacyId))
                    url += "?pharmacyId=" + Uri.EscapeDataString(pharmacyId);
                var playlist = await _http.GetFromJsonAsync<ActivePlaylist>(url, JsonOpts, cts.Token)
                    .ConfigureAwait(false);
                LastPlaylistError = null;
                return playlist;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                var desc = Describe(ex);
                LastPlaylistError = desc;
                return null;
            }
        }

        /// <summary>CDP (§5.6): поиск клиента лояльности по телефону. null при ошибке/оффлайне.</summary>
        public async Task<CdpLookupResult?> CdpLookupAsync(string phone, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http
                    .PostAsJsonAsync("/api/posm/cdp/lookup", new CdpLookupRequestDto { Phone = phone }, JsonOpts, ct)
                    .ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode) return null;
                return await resp.Content.ReadFromJsonAsync<CdpLookupResult>(JsonOpts, ct).ConfigureAwait(false);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>CDP (§5.6): регистрация нового клиента. Идемпотентно по телефону. null при ошибке.</summary>
        public async Task<CdpProfile?> CdpRegisterAsync(CdpRegisterRequestDto req, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http
                    .PostAsJsonAsync("/api/posm/cdp/register", req, JsonOpts, ct)
                    .ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode) return null;
                return await resp.Content.ReadFromJsonAsync<CdpProfile>(JsonOpts, ct).ConfigureAwait(false);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>
        /// Текущий релиз клиента для платформы (авто-обновление). null при ошибке/оффлайне —
        /// апдейт просто не произойдёт в этот раз. Не бросает.
        /// </summary>
        public async Task<AppVersionInfo?> GetAppVersionAsync(
            string platform = "win-x64",
            string? deviceId = null,
            string? currentVersion = null,
            CancellationToken ct = default)
        {
            try
            {
                var url = "/api/posm/app/version?platform=" + Uri.EscapeDataString(platform);
                if (!string.IsNullOrWhiteSpace(deviceId))
                    url += "&deviceId=" + Uri.EscapeDataString(deviceId);
                if (!string.IsNullOrWhiteSpace(currentVersion))
                    url += "&currentVersion=" + Uri.EscapeDataString(currentVersion);
                return await _http.GetFromJsonAsync<AppVersionInfo>(url, JsonOpts, ct).ConfigureAwait(false);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>
        /// Heartbeat живости кассы (T4): POST /api/posm/heartbeat?deviceId=&amp;pharmacyId=&amp;monitorCount=
        /// (без тела, X-Posm-Key уже в заголовках по умолчанию). Backend держит счётчик подключённых касс
        /// (Redis TTL). Fail-safe: при сети/таймауте/ошибке просто проглатываем — пропуск одного
        /// удара ничего не ломает, следующий тик досчитается. Не бросает.
        /// </summary>
        public async Task<bool> HeartbeatAsync(
            string deviceId,
            string? pharmacyId,
            int monitorCount,
            string appVersion,
            CancellationToken ct = default,
            string? staffAuthStatus = null,
            string? staffSourceStatus = null)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(_heartbeatTimeout);
            try
            {
                var url = "/api/posm/heartbeat?deviceId=" + Uri.EscapeDataString(deviceId);
                if (!string.IsNullOrWhiteSpace(pharmacyId))
                    url += "&pharmacyId=" + Uri.EscapeDataString(pharmacyId);
                url += "&monitorCount=" + Math.Max(1, monitorCount);
                if (!string.IsNullOrWhiteSpace(appVersion))
                    url += "&appVersion=" + Uri.EscapeDataString(appVersion);
                if (StaffAuthDiagnostics.IsKnown(staffAuthStatus))
                    url += "&staffAuthStatus=" + Uri.EscapeDataString(staffAuthStatus!);
                if (staffSourceStatus != null)
                    url += "&staffSourceStatus=" + Uri.EscapeDataString(StaffAuthDiagnostics.NormalizeSource(staffSourceStatus));
                using var resp = await _http.PostAsync(url, null, cts.Token).ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode)
                {
                    RewardEvidenceProtocol = null;
                    LastHeartbeatError = $"HTTP {(int)resp.StatusCode} {resp.StatusCode}";
                    _log?.Invoke($"heartbeat: HTTP {(int)resp.StatusCode} {resp.StatusCode}");
                    return false;
                }
                // Older/unknown/malformed capabilities never enable financial evidence.
                RewardEvidenceProtocol = null;
                try
                {
                    var capability = await resp.Content.ReadFromJsonAsync<HeartbeatResponse>(JsonOpts, cts.Token).ConfigureAwait(false);
                    if (capability?.Ok == true && capability.RewardEvidenceProtocol == 1) RewardEvidenceProtocol = 1;
                }
                catch (JsonException) { }
                LastHeartbeatError = null;
                LastHeartbeatAcceptedAt = DateTimeOffset.UtcNow;
                return true;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                RewardEvidenceProtocol = null;
                var desc = Describe(ex);
                LastHeartbeatError = desc;
                if (ShouldLog(ref _lastHeartbeatErrorLog, ref _lastHeartbeatErrorKey, desc))
                    _log?.Invoke($"heartbeat: временно не доставлен ({desc}); повторю по таймеру");
                return false;
            }
        }

        /// <summary>Current merchandising task for a pharmacy, proxied by ePharm.</summary>
        public async Task<MerchTaskDto?> GetActiveMerchTaskAsync(
            string pharmacyId,
            string deviceId,
            CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(_merchTaskTimeout);
            var url = "/api/posm/tasks?pharmacyId=" + Uri.EscapeDataString(pharmacyId);
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.TryAddWithoutValidation("X-Device-Id", deviceId);
            using var response = await _http.SendAsync(request, cts.Token).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            var envelope = await response.Content
                .ReadFromJsonAsync<MerchTaskEnvelope>(JsonOpts, cts.Token)
                .ConfigureAwait(false)
                ?? throw new HttpRequestException("Empty merchandising task response.");
            if (!envelope.Available) throw new MerchTaskUnavailableException();
            return envelope.Task;
        }

        /// <summary>Records that a QR task was actually displayed on a pharmacy device.</summary>
        public async Task<bool> AcknowledgeMerchTaskAsync(
            string dispatchId,
            string pharmacyId,
            string deviceId,
            string deliveryToken,
            CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(_merchTaskTimeout);
            try
            {
                using var response = await _http.PostAsJsonAsync(
                    "/api/posm/tasks/shown",
                    new { dispatchId, pharmacyId, deviceId, deliveryToken },
                    JsonOpts,
                    cts.Token).ConfigureAwait(false);
                return await MerchTaskDeliveryReply.ReadAsync(response, cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
        }

        /// <summary>
        /// Фиксация фактического показа рекомендации (V032). false при ошибке — вызов уходит в
        /// OfflineOutbox для гарантированной доставки. shownAt — client-время показа попапа.
        /// </summary>
        public Task<bool> MarkShownAsync(string eventId, DateTimeOffset shownAt, CancellationToken ct = default) =>
            MarkShownAsync(eventId, shownAt, null, ct);

        public async Task<bool> MarkShownAsync(string eventId, DateTimeOffset shownAt, RewardShownEvidence? rewardEvidence, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http
                    .PostAsJsonAsync(
                        $"/api/posm/recommendations/{eventId}/shown",
                        new MarkShownRequest { ShownAt = shownAt, RewardEvidence = rewardEvidence }, JsonOpts, ct)
                    .ConfigureAwait(false);
                return resp.IsSuccessStatusCode;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>Отправка завершённого чека (источник №1 сверки). false при ошибке → outbox.</summary>
        public async Task<bool> PostSaleAsync(SaleReport sale, CancellationToken ct = default) =>
            await SendSaleAsync(sale, ct).ConfigureAwait(false) == SaleDeliveryResult.Delivered;

        /// <summary>
        /// Like <see cref="PostSaleAsync"/>, but tells a permanent refusal from a transient failure.
        /// 409 is the backend's identity conflict (the same Standard-N document came back with another
        /// session or total) and never succeeds on a retry. 401/403 count as permanent only while the
        /// heartbeat with the same device key is accepted: then the key works and the queued sale
        /// itself names another pharmacy or device. Without a recent heartbeat it is a transient failure.
        /// </summary>
        public async Task<SaleDeliveryResult> SendSaleAsync(SaleReport sale, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http.PostAsJsonAsync("/api/posm/sales", sale, JsonOpts, ct)
                    .ConfigureAwait(false);
                if (resp.IsSuccessStatusCode) return SaleDeliveryResult.Delivered;
                if (resp.StatusCode == HttpStatusCode.Conflict) return SaleDeliveryResult.Rejected;
                if ((resp.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden) && HeartbeatRecentlyAccepted())
                    return SaleDeliveryResult.Rejected;
                return SaleDeliveryResult.Retry;
            }
            catch
            {
                return SaleDeliveryResult.Retry;
            }
        }

        private static readonly TimeSpan HeartbeatEvidenceWindow = TimeSpan.FromMinutes(10);

        /// <summary>Time of the last heartbeat the backend accepted (UTC), null before the first one.</summary>
        public DateTimeOffset? LastHeartbeatAcceptedAt { get; private set; }

        private bool HeartbeatRecentlyAccepted() =>
            LastHeartbeatAcceptedAt is { } at && DateTimeOffset.UtcNow - at <= HeartbeatEvidenceWindow;

        public void Dispose() => _http.Dispose();
    }
}
