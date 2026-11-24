using System;
using System.Net.NetworkInformation;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Фоновый досыл очереди outbox — гарантированная доставка «при любых условиях». Три триггера
    /// досыла (используем ВСЕ доступные методы), чтобы данные доезжали максимально быстро и надёжно:
    ///   1) опрос по таймеру каждые N секунд (базовый ритм);
    ///   2) ранний flush почти сразу после старта (через 2с) — добивает backlog, накопленный за оффлайн
    ///      или после краша/перезапуска кассы;
    ///   3) событие восстановления сети (NetworkAddressChanged / NetworkAvailabilityChanged) —
    ///      мгновенный flush, как только интернет вернулся (не ждём следующего тика таймера).
    /// Успех → удаляет запись; ошибка → reschedule с экспоненциальным backoff. Так sale/outcome не
    /// теряются при пропадании интернета и доезжают, когда связь восстановится. Фон не падает.
    /// </summary>
    public sealed class OutboxFlusher : IDisposable
    {
        private readonly OfflineOutbox _outbox;
        private readonly EpharmApiClient _api;
        private readonly Timer _timer;
        private readonly NetworkAddressChangedEventHandler _onNetAddr;
        private readonly NetworkAvailabilityChangedEventHandler _onNetAvail;
        private readonly Action<OutboxItem>? _onDelivered;
        private int _busy;
        private int _disposed;

        public OutboxFlusher(
            OfflineOutbox outbox,
            EpharmApiClient api,
            int periodSec = 5,
            Action<OutboxItem>? onDelivered = null,
            bool startBackground = true)
        {
            _outbox = outbox;
            _api = api;
            _onDelivered = onDelivered;
            var period = TimeSpan.FromSeconds(Math.Max(1, periodSec));
            // dueTime=2с — ранний первый flush на старте (drain backlog после оффлайна/перезапуска),
            // дальше — обычный период.
            _timer = new Timer(_ => _ = FlushAsync(), null,
                startBackground ? TimeSpan.FromSeconds(2) : Timeout.InfiniteTimeSpan, period);

            // Событие «сеть восстановилась» → мгновенный досыл со сбросом backoff (не ждём тик таймера
            // и не ждём окна экспоненциального backoff, накопленного за офлайн).
            _onNetAddr = (_, _) => _ = FlushNowAsync();
            _onNetAvail = (_, e) => { if (e.IsAvailable) _ = FlushNowAsync(); };
            // Controlled replay tests drive only explicit flushes, independent of host network changes.
            if (startBackground)
            {
                NetworkChange.NetworkAddressChanged += _onNetAddr;
                NetworkChange.NetworkAvailabilityChanged += _onNetAvail;
            }
        }

        /// <summary>
        /// Досыл сразу после возврата сети: сбрасываем расписание (всё «к отправке сейчас») и шлём.
        /// Так sale/shown, накопленные в офлайне, доезжают мгновенно при реконнекте, а не ждут
        /// окна backoff (которое могло вырасти до 30 минут).
        /// </summary>
        public async Task FlushNowAsync()
        {
            try { _outbox.MarkAllDue(); } catch { /* фон не должен падать */ }
            await FlushAsync().ConfigureAwait(false);
        }

        public async Task FlushAsync()
        {
            if (Interlocked.Exchange(ref _busy, 1) == 1) return; // не наслаиваем циклы
            try
            {
                foreach (var item in _outbox.DequeueReady())
                {
                    var result = await SendAsync(item).ConfigureAwait(false);
                    if (result == SaleDeliveryResult.Delivered)
                    {
                        _outbox.Remove(item.Id);
                        try { _onDelivered?.Invoke(item); }
                        catch { /* ACK уже зафиксирован; cleanup не должен ломать flusher */ }
                    }
                    else if (result == SaleDeliveryResult.Rejected) _outbox.RecordRejection(item.Id);
                    else _outbox.Reschedule(item.Id, item.Attempts);
                }
            }
            catch
            {
                // фон не должен падать
            }
            finally
            {
                Interlocked.Exchange(ref _busy, 0);
            }
        }

        private async Task<SaleDeliveryResult> SendAsync(OutboxItem item)
        {
            if (item.Kind is "sale" or "fiscal-sale") return await SendSaleAsync(item).ConfigureAwait(false);
            return await SendOtherAsync(item).ConfigureAwait(false) ? SaleDeliveryResult.Delivered : SaleDeliveryResult.Retry;
        }

        private async Task<SaleDeliveryResult> SendSaleAsync(OutboxItem item)
        {
            try
            {
                var sale = JsonSerializer.Deserialize<SaleReport>(item.Payload, EpharmJson.Options);
                if (sale == null) return SaleDeliveryResult.Retry;
                if (item.Kind == "sale" && ReceiptFinalizationRules.IsKnownUnconfirmedLegacySource(sale.CaptureSource))
                    return SaleDeliveryResult.Retry;
                return await _api.SendSaleAsync(sale).ConfigureAwait(false);
            }
            catch
            {
                return SaleDeliveryResult.Retry;
            }
        }

        private async Task<bool> SendOtherAsync(OutboxItem item)
        {
            try
            {
                switch (item.Kind)
                {
                    case "outcome":
                        var o = JsonSerializer.Deserialize<OutboxOutcomePayload>(item.Payload, EpharmJson.Options);
                        return o != null && await _api
                            .RecordOutcomeAsync(o.EventId, new OutcomeRequest { Outcome = o.Outcome, FinalSku = o.FinalSku })
                            .ConfigureAwait(false);
                    case "shown":
                        var sh = JsonSerializer.Deserialize<OutboxShownPayload>(item.Payload, EpharmJson.Options);
                        return sh != null && await _api
                            .MarkShownAsync(sh.EventId, sh.ShownAt, sh.RewardEvidence)
                            .ConfigureAwait(false);
                    default:
                        // A rollback can reopen an outbox written by a newer client. Unsupported
                        // evidence is preserved by DequeueReady, never acknowledged or discarded.
                        return false;
                }
            }
            catch
            {
                return false;
            }
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 1) return;
            NetworkChange.NetworkAddressChanged -= _onNetAddr;
            NetworkChange.NetworkAvailabilityChanged -= _onNetAvail;
            _timer.Dispose();
        }
    }
}
