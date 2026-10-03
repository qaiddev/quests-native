#if os(iOS)
import SwiftUI
import UIKit

/// The native control for one question. Every value goes straight to the controller;
/// the session holds it, so these views keep no answer of their own.
struct QuestInput: View {
    let controller: QuestController
    let session: QuestSession
    let question: QuestQuestion
    let look: QuestLook

    var body: some View {
        switch question.kind {
        case .text(let options):
            QuestTextInput(controller: controller, session: session, question: question, options: options, look: look)
        case .currency(let options):
            QuestCurrencyInput(controller: controller, session: session, question: question, options: options, look: look)
        case .range(let options):
            QuestRangeInput(controller: controller, session: session, question: question, options: options, look: look)
        case .date(let options):
            QuestDateInput(controller: controller, session: session, question: question, options: options, look: look)
        case .multipleChoice(let options):
            QuestChoiceInput(controller: controller, session: session, question: question, options: options, look: look)
        case .unknown:
            Text(controller.text.unsupportedQuestion)
                .font(look.font(0.875))
                .foregroundColor(look.color(look.palette.muted))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(look.insets(look.metrics.inputPadding))
                .background(fieldShape(look).strokeBorder(look.color(look.palette.inputBorder), lineWidth: 1))
        }
    }
}

func fieldShape(_ look: QuestLook) -> RoundedRectangle {
    RoundedRectangle(cornerRadius: look.metrics.inputRadius, style: .continuous)
}

/// The input box: theme background, 1-point border, the error colour while invalid.
struct QuestField: ViewModifier {
    let look: QuestLook
    let invalid: Bool
    var focused = false
    var minHeight: CGFloat = 44

    func body(content: Content) -> some View {
        let border = invalid ? look.palette.error : focused ? look.palette.focus : look.palette.inputBorder
        content
            .font(look.font(1))
            .foregroundColor(look.color(look.palette.inputForeground))
            .padding(look.insets(look.metrics.inputPadding))
            .frame(minHeight: minHeight)
            .background(fieldShape(look).fill(look.color(look.palette.inputBackground)))
            .overlay(fieldShape(look).strokeBorder(look.color(border), lineWidth: focused ? 2 : 1))
    }
}

struct QuestTextInput: View {
    let controller: QuestController
    let session: QuestSession
    let question: QuestQuestion
    let options: TextOptions
    let look: QuestLook
    @FocusState private var focused: Bool

    var body: some View {
        let value = Binding<String>(
            get: {
                if case .string(let typed)? = session.answer(question.id) { return typed }
                return ""
            },
            set: { controller.setText(question.id, $0) }
        )
        Group {
            if options.multiline {
                ZStack(alignment: .topLeading) {
                    if value.wrappedValue.isEmpty, let placeholder = options.placeholder {
                        Text(placeholder)
                            .foregroundColor(look.color(look.palette.inputPlaceholder))
                            .padding(.top, 8)
                            .padding(.leading, 5)
                            .accessibilityHidden(true)
                    }
                    TextEditor(text: value)
                        .focused($focused)
                        .frame(minHeight: 112 * look.scale)
                }
                .modifier(QuestField(look: look, invalid: session.error != nil, focused: focused))
            } else {
                TextField("", text: value, prompt: options.placeholder.map {
                    Text($0).foregroundColor(look.color(look.palette.inputPlaceholder))
                })
                .focused($focused)
                .keyboardType(keyboard)
                .textContentType(contentType)
                .textInputAutocapitalization(options.inputType == .text ? .sentences : .never)
                .disableAutocorrection(options.inputType != .text)
                .submitLabel(session.isLastStep ? .done : .next)
                .onSubmit { controller.next() }
                .modifier(QuestField(look: look, invalid: session.error != nil, focused: focused))
            }
        }
        .accessibilityLabel(question.label)
        .onChange(of: controller.controlFocus) { _ in focused = true }
    }

    private var keyboard: UIKeyboardType {
        switch options.inputType {
        case .text: return .default
        case .email: return .emailAddress
        case .tel: return .phonePad
        case .url: return .URL
        }
    }

    private var contentType: UITextContentType? {
        switch options.inputType {
        case .text: return nil
        case .email: return .emailAddress
        case .tel: return .telephoneNumber
        case .url: return .URL
        }
    }
}

struct QuestCurrencyInput: View {
    let controller: QuestController
    let session: QuestSession
    let question: QuestQuestion
    let options: CurrencyOptions
    let look: QuestLook
    @State private var typed: String?
    @FocusState private var focused: Bool

    var body: some View {
        let locale = options.locale.map(Locale.init(identifier:)) ?? .current
        let value = Binding<String>(
            get: { typed ?? CurrencyFormat.display(session.answer(question.id)) },
            set: { text in
                typed = text
                controller.setAmount(question.id, CurrencyFormat.parse(text, locale: .current))
            }
        )
        HStack(spacing: 6) {
            Text(CurrencyFormat.symbol(code: options.currency, locale: locale))
                .foregroundColor(look.color(look.palette.muted))
                .accessibilityHidden(true)
            TextField("", text: value, prompt: options.placeholder.map {
                Text($0).foregroundColor(look.color(look.palette.inputPlaceholder))
            })
            .keyboardType(.decimalPad)
            .focused($focused)
        }
        .modifier(QuestField(look: look, invalid: session.error != nil, focused: focused))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(question.label), \(options.currency)")
        .onChange(of: controller.controlFocus) { _ in focused = true }
    }
}

struct QuestRangeInput: View {
    let controller: QuestController
    let session: QuestSession
    let question: QuestQuestion
    let options: RangeOptions
    let look: QuestLook

    var body: some View {
        let current = RangeMath.initialValue(options, answer: session.answer(question.id))
        let value = Binding<Double>(get: { current }, set: { controller.setRange(question.id, $0) })
        VStack(spacing: 8) {
            (Text(QuestJSON.number(current)).font(look.font(2, weight: .bold))
                .foregroundColor(look.color(look.palette.accent))
                + Text(options.unit ?? "").font(look.font(1)).foregroundColor(look.color(look.palette.muted)))
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
            Slider(value: value, in: options.min...options.max, step: options.step)
                .tint(look.color(look.palette.accent))
                .frame(minHeight: 44)
                .accessibilityLabel(question.label)
                .accessibilityValue(RangeMath.label(current, unit: options.unit))
            HStack {
                Text(RangeMath.label(options.min, unit: options.unit))
                Spacer()
                Text(RangeMath.label(options.max, unit: options.unit))
            }
            .font(look.font(0.8125))
            .foregroundColor(look.color(look.palette.muted))
            .accessibilityHidden(true)
        }
    }
}

struct QuestDateInput: View {
    let controller: QuestController
    let session: QuestSession
    let question: QuestQuestion
    let options: DateOptions
    let look: QuestLook

    var body: some View {
        let calendar = QuestDate.calendar()
        let text = controller.text
        if case .string(let wire)? = session.answer(question.id), let picked = QuestDate.date(wire, calendar: calendar) {
            let selection = Binding<Date>(get: { picked },
                                          set: { controller.setDate(question.id, QuestDate.wire($0, calendar: calendar)) })
            HStack {
                picker(selection, calendar: calendar)
                    .labelsHidden()
                    .accessibilityLabel(question.label)
                    .tint(look.color(look.palette.accent))
                Spacer()
                Button(text.clearDate) { controller.setDate(question.id, nil) }
                    .font(look.font(0.875))
                    .foregroundColor(look.color(look.palette.muted))
                    .frame(minHeight: 44)
            }
        } else {
            Button {
                let day = QuestDate.initial(today: Date(), options: options, calendar: calendar)
                controller.setDate(question.id, QuestDate.wire(day, calendar: calendar))
            } label: {
                HStack {
                    Text(text.datePlaceholder).foregroundColor(look.color(look.palette.inputPlaceholder))
                    Spacer()
                    Image(systemName: "calendar").foregroundColor(look.color(look.palette.muted))
                }
                .modifier(QuestField(look: look, invalid: session.error != nil))
                .contentShape(Rectangle())
            }
            .accessibilityLabel(question.label)
            .accessibilityValue(text.datePlaceholder)
        }
    }

    /// The bounds only limit the picker, as on the web.
    @ViewBuilder
    private func picker(_ selection: Binding<Date>, calendar: Calendar) -> some View {
        let min = options.min.flatMap { QuestDate.date($0, calendar: calendar) }
        let max = options.max.flatMap { QuestDate.date($0, calendar: calendar) }
        switch (min, max) {
        case let (min?, max?) where min <= max:
            DatePicker("", selection: selection, in: min...max, displayedComponents: .date)
        case let (min?, nil):
            DatePicker("", selection: selection, in: min..., displayedComponents: .date)
        case let (nil, max?):
            DatePicker("", selection: selection, in: ...max, displayedComponents: .date)
        default:
            DatePicker("", selection: selection, displayedComponents: .date)
        }
    }
}

struct QuestChoiceInput: View {
    let controller: QuestController
    let session: QuestSession
    let question: QuestQuestion
    let options: ChoiceOptions
    let look: QuestLook

    var body: some View {
        let selected = session.selected(question.id)
        let vertical = options.hasImages && options.imageAlignment == .vertical
        Group {
            if vertical {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 8)], spacing: 8) {
                    ForEach(options.options, id: \.value) { option in
                        row(option, selected: selected.contains(option.value), vertical: true)
                    }
                }
            } else {
                VStack(spacing: 8) {
                    ForEach(options.options, id: \.value) { option in
                        row(option, selected: selected.contains(option.value), vertical: false)
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(question.label)
    }

    private func row(_ option: ChoiceOption, selected: Bool, vertical: Bool) -> some View {
        let palette = look.palette
        let shape = RoundedRectangle(cornerRadius: look.metrics.optionRadius, style: .continuous)
        let thumb = options.hasImages && !vertical
        return Button {
            controller.select(question.id, option: option.value)
        } label: {
            Group {
                if vertical {
                    VStack(spacing: 6) {
                        if let image = option.image {
                            QuestRemoteImage(url: image, placeholder: look.color(palette.progressTrack))
                                .aspectRatio(1, contentMode: .fit)
                                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                        }
                        labels(option, center: true)
                    }
                    .overlay(alignment: .topTrailing) { marker(selected) }
                } else {
                    HStack(spacing: 10) {
                        if thumb {
                            Group {
                                if let image = option.image {
                                    QuestRemoteImage(url: image, placeholder: look.color(palette.progressTrack))
                                } else {
                                    look.color(palette.progressTrack)
                                }
                            }
                            .frame(width: 48, height: 48)
                            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                        }
                        labels(option, center: false)
                        Spacer(minLength: 0)
                        marker(selected)
                    }
                }
            }
            .padding(look.insets(look.metrics.optionPadding))
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(shape.fill(look.color(palette.optionBackground)))
            .background(shape.fill(look.color(selected ? palette.optionSelectedBackground : .clear)))
            .overlay(shape.strokeBorder(look.color(selected ? palette.optionSelectedBorder : palette.optionBorder),
                                        lineWidth: selected ? 2 : 1))
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(option.label)
        .accessibilityHint(option.description ?? "")
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    private func labels(_ option: ChoiceOption, center: Bool) -> some View {
        VStack(alignment: center ? .center : .leading, spacing: 2) {
            Text(option.label)
                .font(look.font(1, weight: .medium))
                .foregroundColor(look.color(look.palette.optionForeground))
            if let description = option.description, !description.isEmpty {
                Text(description).font(look.font(0.8125)).foregroundColor(look.color(look.palette.muted))
            }
        }
        .multilineTextAlignment(center ? .center : .leading)
    }

    /// A radio dot or a checkbox tick, 1.25em, as the web marker.
    private func marker(_ selected: Bool) -> some View {
        let palette = look.palette
        let size = 20 * look.scale
        let radius = options.multiple ? 4 : look.metrics.markerRadius * look.scale
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        return ZStack {
            shape.fill(look.color(selected ? palette.markerSelectedBackground : palette.markerBackground))
            shape.strokeBorder(look.color(selected ? palette.markerSelectedBackground : palette.markerBorder), lineWidth: 2)
            if selected {
                if options.multiple {
                    Image(systemName: "checkmark")
                        .font(.system(size: size * 0.6, weight: .bold))
                        .foregroundColor(look.color(palette.markerIcon))
                } else {
                    Circle().fill(look.color(palette.markerIcon)).frame(width: size * 0.4, height: size * 0.4)
                }
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
#endif
