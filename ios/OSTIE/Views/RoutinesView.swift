import SwiftUI

struct RoutinesView: View {
    @EnvironmentObject var model: AppModel
    @State private var add = false
    var body: some View {
        NavigationStack {
            List {
                Section { Text("Receba um lembrete no horário. Ao tocar, o OSTIE abre e executa a tarefa com a sua IA.").font(.callout).foregroundStyle(.secondary) }
                ForEach(model.routines) { routine in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack { Text(routine.title).font(.headline); Spacer(); Text(String(format: "%02d:%02d", routine.hour, routine.minute)).monospacedDigit() }
                        if !routine.prompt.isEmpty { Text(routine.prompt).font(.subheadline).foregroundStyle(.secondary) }
                        Text(routine.weekdays.isEmpty ? "Próxima ocorrência" : routine.weekdays.map { Calendar.current.shortWeekdaySymbols[$0 - 1] }.joined(separator: ", ")).font(.caption)
                        HStack {
                            Button("Testar agora") { model.runRoutine(routine.id) }.disabled(model.busy)
                            Spacer()
                            Toggle("Ativa", isOn: Binding(get: { routine.enabled }, set: { enabled in
                                var updated = model.routines; if let index = updated.firstIndex(where: { $0.id == routine.id }) { updated[index].enabled = enabled }
                                Task { do { try await model.saveRoutines(updated) } catch { model.report(error.localizedDescription) } }
                            })).labelsHidden()
                        }
                    }.padding(.vertical, 6)
                }.onDelete { offsets in
                    let selected = Set(offsets.compactMap { model.routines.indices.contains($0) ? model.routines[$0].id : nil })
                    Task { if await model.confirm("Apagar as rotinas selecionadas?") { do { try await model.saveRoutines(model.routines.filter { !selected.contains($0.id) }) } catch { model.report(error.localizedDescription) } } }
                }
            }
            .navigationTitle("Rotinas").toolbar { Button("Nova rotina", systemImage: "plus") { add = true } }
            .sheet(isPresented: $add) { RoutineEditor() }
        }
    }
}
struct RoutineEditor: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    @State private var title = ""
    @State private var prompt = ""
    @State private var time = Date()
    @State private var days = Set<Int>()
    @State private var saving = false
    var body: some View {
        NavigationStack {
            Form {
                TextField("Nome do lembrete", text: $title)
                TextField("O que a IA deve fazer? (opcional)", text: $prompt, axis: .vertical).lineLimit(3...6)
                DatePicker("Horário", selection: $time, displayedComponents: .hourAndMinute)
                Section("Repetir nos dias") { ForEach(1...7, id: \.self) { day in Toggle(Calendar.current.weekdaySymbols[day - 1], isOn: Binding(get: { days.contains(day) }, set: { if $0 { days.insert(day) } else { days.remove(day) } })) } }
            }
            .navigationTitle("Nova rotina")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Salvar") {
                        saving = true
                        Task {
                            defer { saving = false }
                            let components = Calendar.current.dateComponents([.hour, .minute], from: time)
                            do { try await model.saveRoutines(model.routines + [Routine(title: title, prompt: prompt, hour: components.hour ?? 8, minute: components.minute ?? 0, weekdays: days.sorted())]); dismiss() }
                            catch { model.report(error.localizedDescription) }
                        }
                    }.disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || saving)
                }
            }
        }
    }
}
