import XCTest
@testable import QaidQuestsCore

final class ValidationTests: XCTestCase {
    let text = QaidQuestText()

    private func message(_ question: QuestQuestion, _ value: QuestAnswer?) -> String? {
        QuestValidation.message(for: question, value: value, text: text)
    }

    func testTextOnlyWhenRequired() {
        XCTAssertNil(message(Fixtures.text("a"), nil))
        XCTAssertNil(message(Fixtures.text("a", minLength: 5), .string("ab")))
        XCTAssertEqual(message(Fixtures.text("a", required: true), nil), "This field is required.")
        XCTAssertEqual(message(Fixtures.text("a", required: true), .string("   ")), "This field is required.")
        XCTAssertEqual(message(Fixtures.text("a", required: true), .null), "This field is required.")
        XCTAssertNil(message(Fixtures.text("a", required: true), .string("x")))
    }

    func testMinLengthMessageWinsEvenWhenEmpty() {
        let question = Fixtures.text("a", required: true, minLength: 3)
        XCTAssertEqual(message(question, .string("")), "Please enter at least 3 characters.")
        XCTAssertEqual(message(question, .string(" ab ")), "Please enter at least 3 characters.")
        XCTAssertNil(message(question, .string("abc")))
        XCTAssertEqual(message(Fixtures.text("a", required: true, minLength: 0), .string("")), "This field is required.")
    }

    func testCurrency() {
        func currency(required: Bool = false, min: Double? = nil, max: Double? = nil) -> QuestQuestion {
            QuestQuestion(id: "c", label: "C", required: required, kind: .currency(CurrencyOptions(min: min, max: max)))
        }
        XCTAssertNil(message(currency(), nil))
        XCTAssertNil(message(currency(), .null))
        XCTAssertEqual(message(currency(required: true), .null), "Please enter a valid amount.")
        XCTAssertEqual(message(currency(min: 10), .number(9.99)), "Please enter a valid amount.")
        XCTAssertEqual(message(currency(max: 10), .number(10.01)), "Please enter a valid amount.")
        XCTAssertNil(message(currency(min: 10, max: 10), .number(10)))
        XCTAssertNil(message(currency(required: true), .number(1.234)))
    }

    func testDateAndChoice() {
        let date = QuestQuestion(id: "d", label: "D", required: true, kind: .date(DateOptions(min: "2030-01-01")))
        XCTAssertEqual(message(date, nil), "Please pick a date.")
        XCTAssertNil(message(date, .string("2020-01-01")))
        var optional = date
        optional.required = false
        XCTAssertNil(message(optional, nil))
        XCTAssertEqual(message(Fixtures.choice("m", ["a"], required: true), nil), "Please select an option.")
        XCTAssertEqual(message(Fixtures.choice("m", ["a"], multiple: true, required: true), .strings([])),
                       "Please select at least one option.")
        XCTAssertNil(message(Fixtures.choice("m", ["a"], required: true), .string("a")))
        XCTAssertNil(message(Fixtures.choice("m", ["a"]), nil))
    }

    func testRangeAndUnknownNeverFail() {
        XCTAssertNil(message(QuestQuestion(id: "r", label: "R", required: true, kind: .range(RangeOptions(min: 0, max: 1))), nil))
        XCTAssertNil(message(QuestQuestion(id: "u", label: "U", required: true, kind: .unknown("x")), nil))
    }

    func testOverriddenMessages() {
        let german = QaidQuestText(validationRequired: "Pflicht", validationMinLength: "Mindestens {count}")
        XCTAssertEqual(QuestValidation.message(for: Fixtures.text("a", required: true), value: nil, text: german), "Pflicht")
        XCTAssertEqual(QuestValidation.message(for: Fixtures.text("a", required: true, minLength: 4), value: nil, text: german),
                       "Mindestens 4")
    }
}

final class RangeMathTests: XCTestCase {
    func testSnapsLikeAnHTMLSlider() {
        let options = RangeOptions(min: 0, max: 10, step: 3)
        XCTAssertEqual(RangeMath.snap(5, options: options), 6)
        XCTAssertEqual(RangeMath.snap(4.4, options: options), 3)
        XCTAssertEqual(RangeMath.snap(10, options: options), 9)
        XCTAssertEqual(RangeMath.snap(-4, options: options), 0)
        XCTAssertEqual(RangeMath.snap(0.30000000000000004, options: RangeOptions(min: 0, max: 1, step: 0.1)), 0.3)
        XCTAssertEqual(RangeMath.snap(7, options: RangeOptions(min: 1, max: 5)), 5)
    }

    func testInitialValue() {
        XCTAssertEqual(RangeMath.initialValue(RangeOptions(min: 2, max: 9), answer: nil), 2)
        XCTAssertEqual(RangeMath.initialValue(RangeOptions(min: 0, max: 9, defaultValue: 4), answer: nil), 4)
        XCTAssertEqual(RangeMath.initialValue(RangeOptions(min: 0, max: 9, defaultValue: 4), answer: .number(7)), 7)
        XCTAssertEqual(RangeMath.initialValue(RangeOptions(min: 0, max: 9, defaultValue: 4), answer: .string("x")), 4)
    }

    func testLabels() {
        XCTAssertEqual(RangeMath.label(5, unit: "/10"), "5/10")
        XCTAssertEqual(RangeMath.label(2.5, unit: nil), "2.5")
    }
}

final class CurrencyFormatTests: XCTestCase {
    func testNarrowSymbols() {
        XCTAssertEqual(CurrencyFormat.symbol(code: "USD", locale: Locale(identifier: "en_US")), "$")
        XCTAssertEqual(CurrencyFormat.symbol(code: "USD", locale: Locale(identifier: "en_CA")), "$")
        XCTAssertEqual(CurrencyFormat.symbol(code: "EUR", locale: Locale(identifier: "de_DE")), "€")
        XCTAssertEqual(CurrencyFormat.symbol(code: "GBP", locale: Locale(identifier: "en_GB")), "£")
    }

    func testParsesWhatWasTyped() {
        let us = Locale(identifier: "en_US"), de = Locale(identifier: "de_DE")
        XCTAssertEqual(CurrencyFormat.parse("12.5", locale: us), 12.5)
        XCTAssertEqual(CurrencyFormat.parse(" 12 ", locale: us), 12)
        XCTAssertEqual(CurrencyFormat.parse("12,5", locale: de), 12.5)
        XCTAssertEqual(CurrencyFormat.parse("12.5", locale: de), 12.5)
        XCTAssertNil(CurrencyFormat.parse("", locale: us))
        XCTAssertNil(CurrencyFormat.parse("abc", locale: us))
        XCTAssertNil(CurrencyFormat.parse("1,000", locale: us))
        XCTAssertNil(CurrencyFormat.parse("inf", locale: us))
    }

    func testDisplay() {
        XCTAssertEqual(CurrencyFormat.display(.number(12)), "12")
        XCTAssertEqual(CurrencyFormat.display(.number(12.5)), "12.5")
        XCTAssertEqual(CurrencyFormat.display(.null), "")
        XCTAssertEqual(CurrencyFormat.display(nil), "")
    }
}

final class QuestDateTests: XCTestCase {
    let utc = QuestDate.calendar(timeZone: TimeZone(identifier: "UTC")!)

    func testWireFormat() {
        XCTAssertTrue(QuestDate.isWire("2026-10-03"))
        for bad in ["2026-1-03", "2026-10-3", "26-10-03", "2026/10/03", "2026-13-01", "2026-00-01", "2026-01-32",
                    "2026-01-00", "２０２６-01-01", "2026-01-01-01", "abcd-ef-gh"] {
            XCTAssertFalse(QuestDate.isWire(bad), bad)
        }
    }

    func testDateRoundTrip() {
        let date = QuestDate.date("2026-10-03", calendar: utc)
        XCTAssertEqual(date, Date(timeIntervalSince1970: 1_790_985_600))
        XCTAssertEqual(QuestDate.wire(date!, calendar: utc), "2026-10-03")
        XCTAssertNil(QuestDate.date("2026-02-30", calendar: utc))
        XCTAssertNil(QuestDate.date("nope", calendar: utc))
        let tokyo = QuestDate.calendar(timeZone: TimeZone(identifier: "Asia/Tokyo")!)
        XCTAssertEqual(QuestDate.wire(QuestDate.date("2026-01-01", calendar: tokyo)!, calendar: tokyo), "2026-01-01")
        XCTAssertEqual(QuestDate.wire(Date(timeIntervalSince1970: 0), calendar: tokyo), "1970-01-01")
    }

    func testInitialPickIsTodayInsideTheBounds() {
        let today = QuestDate.date("2026-10-03", calendar: utc)!.addingTimeInterval(3600 * 15)
        func pick(_ min: String?, _ max: String?) -> String {
            QuestDate.wire(QuestDate.initial(today: today, options: DateOptions(min: min, max: max), calendar: utc),
                           calendar: utc)
        }
        XCTAssertEqual(pick(nil, nil), "2026-10-03")
        XCTAssertEqual(pick("2027-01-01", nil), "2027-01-01")
        XCTAssertEqual(pick(nil, "2026-05-01"), "2026-05-01")
        XCTAssertEqual(pick("2026-01-01", "2026-12-31"), "2026-10-03")
    }
}

final class TextTests: XCTestCase {
    func testDefaultsAreTheWebEmbeds() {
        let text = QaidQuestText()
        XCTAssertEqual([text.next, text.back, text.submit, text.thankYouTitle], ["Next", "Back", "Submit", "Thank you!"])
        XCTAssertEqual(text.loadErrorTitle, "Couldn't load form")
        XCTAssertEqual(text.submitErrorTitle, "Couldn't send your answers")
        XCTAssertEqual(text.submitErrorMessage, "Your answers are still here. Check your connection and try again.")
        XCTAssertEqual(text.tryAgain, "Try again")
        XCTAssertEqual(text.sending, "Sending…")
        XCTAssertEqual(text.saving, "Saving…")
        XCTAssertEqual(text.loading, "Loading…")
        XCTAssertEqual(text.closeForm, "Close form")
        XCTAssertEqual(text.required, "required")
        XCTAssertEqual(text.progressLabel, "Progress")
        XCTAssertEqual(text.close, "Close")
        XCTAssertFalse(text.unsupportedQuestion.isEmpty)
        XCTAssertFalse(text.notConfigured.isEmpty)
        XCTAssertFalse(text.datePlaceholder.isEmpty)
        XCTAssertFalse(text.clearDate.isEmpty)
        XCTAssertFalse(text.retry.isEmpty)
        XCTAssertFalse(text.savedOffline.isEmpty)
    }

    func testTemplates() {
        let text = QaidQuestText()
        XCTAssertEqual(text.minLengthMessage(4), "Please enter at least 4 characters.")
        XCTAssertEqual(text.progressValue(step: 2, total: 5), "Question 2 of 5")
        XCTAssertEqual(text.stepAnnouncement(step: 1, total: 3), "Step 1 of 3")
        XCTAssertEqual(text.loadError(.status(404)), "Failed to load questionnaire (404)")
        XCTAssertEqual(text.loadError(.empty), "Questionnaire is empty")
        XCTAssertEqual(text.loadError(.invalid), text.loadErrorInvalid)
        XCTAssertEqual(text.loadError(.network), text.loadErrorOffline)
    }
}
