import UserNotifications

@MainActor
final class RoutineScheduler: NSObject, UNUserNotificationCenterDelegate {
    var onOpen: ((UUID) -> Void)?
    override init() { super.init(); UNUserNotificationCenter.current().delegate = self }
    func schedule(_ routines: [Routine]) async throws {
        let center = UNUserNotificationCenter.current()
        let total = routines.filter(\.enabled).reduce(0) { $0 + max(1, $1.weekdays.count) }
        guard total <= 60 else { throw AppError.message("Limite de 60 notificações agendadas. Reduza os dias ou desative uma rotina.") }
        if total > 0 {
            let granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
            if !granted { throw AppError.message("Permita notificações para receber as rotinas.") }
        }
        let old = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("routine-") }
        // Stage only after validation. Restore previous requests if scheduling fails.
        center.removePendingNotificationRequests(withIdentifiers: old.map(\.identifier))
        var added: [String] = []
        do {
            for routine in routines where routine.enabled {
                for day in routine.weekdays.isEmpty ? [0] : routine.weekdays {
                    let content = UNMutableNotificationContent(); content.title = routine.title
                    content.body = routine.prompt.isEmpty ? "Seu lembrete do OSTIE." : "Toque para o OSTIE executar: \(routine.prompt.prefix(180))"
                    content.sound = .default; content.userInfo = ["routine": routine.id.uuidString]
                    var components = DateComponents(); components.hour = routine.hour; components.minute = routine.minute
                    if day > 0 { components.weekday = day }
                    let id = "routine-\(routine.id)-\(day)"
                    let request = UNNotificationRequest(identifier: id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: day > 0))
                    try await center.add(request); added.append(id)
                }
            }
        } catch {
            center.removePendingNotificationRequests(withIdentifiers: added)
            for request in old { try? await center.add(request) }; throw error
        }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let raw = response.notification.request.content.userInfo["routine"] as? String
        Task { @MainActor in if let raw, let id = UUID(uuidString: raw) { self.onOpen?(id) }; completionHandler() }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) { completionHandler([.banner, .sound]) }
}
