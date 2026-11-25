using System;
using System.Collections.Generic;
using System.Linq;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// The red dot on the ePharm icon by the clock and the icon's tooltip (1.0.99): something waits for the person at
    /// this till. Pure, unit-tested; MainWindow feeds it what the server said.
    /// </summary>
    public static class EpharmAttention
    {
        /// <summary>
        /// Required courses not finished. The server counts them (paused ones excluded); a server before 1.0.99 sends no
        /// count, then the list itself decides.
        /// </summary>
        public static int RequiredUnfinished(PosmSelfTasksDto? feed)
        {
            if (feed == null) return 0;
            if (feed.RequiredOpenCount is { } count) return Math.Max(0, count);
            return (feed.Tasks ?? new List<PosmSelfTaskDto>()).Count(t => t.Required && t.Status != "done");
        }

        /// <summary>Tasks the person has not opened yet and overdue ones (the 1.0.96 rule).</summary>
        public static int NewOrOverdue(PosmSelfTasksDto? feed) =>
            feed == null ? 0 : Math.Max(0, feed.NewCount) + (feed.Tasks ?? new List<PosmSelfTaskDto>()).Count(t => t.Status == "overdue");

        /// <summary>
        /// Red dot while the profile is not filled, a pharmacy task waits, a course task is new or overdue, a required
        /// course is not finished or a notice from HQ is not closed yet.
        /// </summary>
        public static bool NeedsDot(bool? profileComplete, int merchTasks, int newOrOverdueTasks, int requiredUnfinished, int pendingMessages) =>
            profileComplete == false || merchTasks > 0 || newOrOverdueTasks > 0 || requiredUnfinished > 0 || pendingMessages > 0;

        /// <summary>The icon's tooltip; Windows shows at most 63 characters.</summary>
        public static string TrayText(bool paused, bool? profileComplete, string shortName, int merchTasks, int requiredUnfinished, int pendingMessages)
        {
            if (paused) return "ePharm: технические работы";
            var text = profileComplete == false ? "ePharm: заполните профиль"
                : shortName.Length > 0 ? "ePharm: " + shortName : "ePharm: мой кабинет";
            if (pendingMessages > 0) text += " · уведомлений: " + pendingMessages;
            if (requiredUnfinished > 0) text += " · курсов: " + requiredUnfinished;
            if (merchTasks > 0) text += " · заданий: " + merchTasks;
            return text.Length <= 63 ? text : text[..63];
        }
    }
}
