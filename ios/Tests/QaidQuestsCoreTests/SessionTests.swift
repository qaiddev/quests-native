import XCTest
@testable import QaidQuestsCore

final class SessionTests: XCTestCase {
    private func session(_ quest: Questionnaire = Fixtures.quest, autoAdvance: Bool = false) -> QuestSession {
        QuestSession(quest: quest, text: QaidQuestText(), autoAdvance: autoAdvance)
    }

    private func saves(_ effects: [SessionEffect]) -> [String] {
        effects.compactMap {
            if case let .save(id, value, debounce) = $0 { return "\(id)=\(value.json.text)\(debounce ? "~" : "")" }
            return nil
        }
    }

    func testFirstStep() {
        var s = session()
        let effects = s.begin()
        XCTAssertEqual(effects, [.announce("Step 1 of 5"), .focusTitle])
        XCTAssertEqual(s.currentQuestion.id, "name")
        XCTAssertEqual(s.stepIndex, 0)
        XCTAssertEqual(s.stepCount, 5)
        XCTAssertEqual(s.counterText, "1 / 5")
        XCTAssertEqual(s.progressFraction, 0.2)
        XCTAssertEqual(s.progressValueText, "Question 1 of 5")
        XCTAssertTrue(s.showsProgress)
        XCTAssertFalse(s.canGoBack)
        XCTAssertFalse(s.isLastStep)
        XCTAssertEqual(s.primaryLabel, "Next")
        XCTAssertEqual(s.backLabel, "Back")
        XCTAssertEqual(s.title, "Tell us")
        XCTAssertEqual(s.description, "Two minutes")
        XCTAssertEqual(s.thankYouTitle, "Thank you!")
        XCTAssertNil(s.thankYouMessage)
        XCTAssertEqual(s.phase, .answering)
    }

    func testValidationBlocksNextAndClearsOnEdit() {
        var s = session()
        _ = s.begin()
        XCTAssertEqual(s.next(), [.alert("Please enter at least 2 characters."), .focusControl])
        XCTAssertEqual(s.error, "Please enter at least 2 characters.")
        XCTAssertEqual(s.currentQuestion.id, "name")
        XCTAssertEqual(saves(s.setText("name", "J")), [#"name="J"~"#])
        XCTAssertNil(s.error)
        _ = s.next()
        XCTAssertNotNil(s.error)
        _ = s.setText("name", "Jo")
        let effects = s.next()
        XCTAssertEqual(effects.first, .flushSaves)
        XCTAssertEqual(saves(effects), [#"name="Jo""#])
        XCTAssertTrue(effects.contains(.announce("Step 2 of 5")))
        XCTAssertEqual(s.currentQuestion.id, "budget")
        XCTAssertNil(s.error)
        XCTAssertTrue(s.canGoBack)
    }

    func testMaxLengthCapsTyping() {
        var s = session()
        _ = s.begin()
        _ = s.setText("name", "Abcdefghijklmnop")
        XCTAssertEqual(s.answer("name"), .string("Abcdefghij"))
    }

    func testEditsToTheWrongTypeOrUnknownIdAreIgnored() {
        var s = session()
        XCTAssertEqual(s.setText("budget", "x"), [])
        XCTAssertEqual(s.setAmount("name", 1), [])
        XCTAssertEqual(s.setRange("name", 1), [])
        XCTAssertEqual(s.setDate("name", "2026-01-01"), [])
        XCTAssertEqual(s.select("name", option: "x"), [])
        XCTAssertEqual(s.select("color", option: "green"), [])
        XCTAssertEqual(s.setText("ghost", "x"), [])
        XCTAssertTrue(s.answers.isEmpty)
    }

    func testUntouchedOptionalQuestionsAreSavedOnNext() {
        var s = session()
        _ = s.begin()
        _ = s.setText("name", "Jo")
        _ = s.next()
        // Currency left empty: null, as the web embed's control holds.
        let budget = s.next()
        XCTAssertEqual(saves(budget), ["budget=null", "score=5~"])
        XCTAssertEqual(s.currentQuestion.id, "score")
        // The range's starting value was pushed when its step was drawn.
        XCTAssertEqual(s.answer("score"), .number(5))
        XCTAssertEqual(saves(s.next()), ["score=5"])
        XCTAssertEqual(saves(s.next()), ["when=null"])
        XCTAssertEqual(s.currentQuestion.id, "color")
    }

    func testEveryKindOfAnswer() {
        var s = session()
        XCTAssertEqual(saves(s.setAmount("budget", 12.5)), ["budget=12.5~"])
        XCTAssertEqual(saves(s.setAmount("budget", nil)), ["budget=null~"])
        XCTAssertEqual(saves(s.setRange("score", 7.4)), ["score=7~"])
        XCTAssertEqual(saves(s.setDate("when", "2026-05-01")), [#"when="2026-05-01""#])
        XCTAssertEqual(saves(s.setDate("when", nil)), ["when=null"])
        XCTAssertEqual(saves(s.select("color", option: "blue")), [#"color="blue""#])
        XCTAssertEqual(saves(s.select("color", option: "blue")), [#"color="blue""#])
        XCTAssertEqual(s.selected("color"), ["blue"])
        XCTAssertEqual(s.selected("nothing"), [])
    }

    func testMultipleChoiceTogglesInClickOrder() {
        var s = session(Fixtures.quest([Fixtures.choice("m", ["a", "b", "c"], multiple: true)]))
        _ = s.select("m", option: "c")
        _ = s.select("m", option: "a")
        XCTAssertEqual(s.answer("m"), .strings(["c", "a"]))
        XCTAssertEqual(s.selected("m"), ["c", "a"])
        _ = s.select("m", option: "c")
        XCTAssertEqual(s.answer("m"), .strings(["a"]))
        _ = s.select("m", option: "a")
        XCTAssertEqual(s.answer("m"), .strings([]))
    }

    func testFollowUpRelabelsAndExtendsTheFlow() {
        var s = session()
        _ = s.setText("name", "Jo")
        for _ in 0..<4 { _ = s.next() }
        XCTAssertEqual(s.currentQuestion.id, "color")
        XCTAssertTrue(s.isLastStep)
        XCTAssertEqual(s.primaryLabel, "Submit")
        XCTAssertEqual(s.next(), [.alert("Please select an option."), .focusControl])
        _ = s.select("color", option: "blue")
        XCTAssertFalse(s.isLastStep)
        XCTAssertEqual(s.primaryLabel, "Next")
        XCTAssertEqual(s.stepCount, 6)
        XCTAssertEqual(s.counterText, "5 / 6")
        _ = s.select("color", option: "red")
        XCTAssertEqual(s.primaryLabel, "Submit")
        XCTAssertEqual(s.stepCount, 5)
    }

    func testSubmitSendsVisibleAnswersAndClearsHiddenOnes() {
        var s = session()
        _ = s.setText("name", "Jo")
        for _ in 0..<4 { _ = s.next() }
        _ = s.select("color", option: "blue")
        _ = s.next()
        XCTAssertEqual(s.currentQuestion.id, "why")
        _ = s.setText("why", "calm")
        _ = s.back()
        _ = s.select("color", option: "red")
        let effects = s.next()
        XCTAssertEqual(s.phase, .submitting)
        guard case let .submit(answers, clearing)? = effects.last else { return XCTFail("\(effects)") }
        XCTAssertEqual(answers, ["name": .string("Jo"), "budget": .null, "score": .number(5), "when": .null,
                                 "color": .string("red")])
        XCTAssertEqual(clearing, ["why"])
        // Nothing changes while sending.
        XCTAssertEqual(s.next(), [])
        XCTAssertEqual(s.back(), [])
        XCTAssertEqual(s.setText("name", "x"), [])
        XCTAssertEqual(s.retrySubmit(), [])
    }

    func testSubmitOutcomes() {
        var s = session(Fixtures.quest([Fixtures.text("a")]))
        XCTAssertFalse(s.showsProgress)
        XCTAssertEqual(s.begin(), [.focusTitle])
        XCTAssertEqual(s.submitFinished(.sent), [])
        let effects = s.next()
        XCTAssertEqual(effects.last, .submit(answers: ["a": .string("")], clearing: []))
        XCTAssertEqual(s.submitFinished(.failed(.network("x"))),
                       [.focusTitle, .alert("Couldn't send your answers. Your answers are still here. Check your connection and try again.")])
        XCTAssertEqual(s.phase, .submitFailed)
        XCTAssertEqual(s.next(), [])
        XCTAssertEqual(s.retrySubmit(), [.submit(answers: ["a": .string("")], clearing: [])])
        XCTAssertEqual(s.phase, .submitting)
        XCTAssertTrue(s.retrying)
        XCTAssertEqual(s.submitFinished(.sent), [.focusTitle, .announce("Thank you!"), .complete(["a": .string("")])])
        XCTAssertFalse(s.retrying)
        XCTAssertEqual(s.phase, .done(savedOffline: false))
    }

    func testSavedOfflineThankYou() {
        var quest = Fixtures.quest([Fixtures.text("a")])
        quest.thankYouTitle = "Ta"
        quest.thankYouMessage = "See you"
        var s = session(quest)
        _ = s.next()
        let effects = s.submitFinished(.savedOffline)
        XCTAssertEqual(effects[1], .announce("Ta. See you. Your answers are saved on this device and will be sent when you're back online."))
        XCTAssertEqual(s.phase, .done(savedOffline: true))
    }

    func testBackFlushesAndNeverValidates() {
        var s = session()
        _ = s.setText("name", "Jo")
        _ = s.next()
        _ = s.setAmount("budget", -5)
        let effects = s.back()
        XCTAssertEqual(effects.first, .flushSaves)
        XCTAssertEqual(s.currentQuestion.id, "name")
        XCTAssertEqual(s.back(), [])
    }

    func testRangeKeepsAnEarlierAnswerWhenRevisited() {
        var s = session()
        _ = s.setText("name", "Jo")
        _ = s.next()
        _ = s.next()
        _ = s.setRange("score", 9)
        _ = s.back()
        let effects = s.next()
        XCTAssertEqual(saves(effects), ["budget=null"])
        XCTAssertEqual(s.answer("score"), .number(9))
    }

    func testAutoAdvance() {
        var s = session(Fixtures.quest([Fixtures.choice("a", ["x", "y"]), Fixtures.choice("m", ["x"], multiple: true)]),
                        autoAdvance: true)
        let effects = s.select("a", option: "x")
        XCTAssertEqual(effects.last, .autoAdvance(questionId: "a"))
        XCTAssertEqual(s.autoAdvanceFired("m"), [])
        _ = s.autoAdvanceFired("a")
        XCTAssertEqual(s.currentQuestion.id, "m")
        XCTAssertFalse(s.select("m", option: "x").contains(.autoAdvance(questionId: "m")))
        XCTAssertFalse(session().autoAdvance)
    }

    func testUnknownQuestionsShowAsACardAndAreNeverRequired() {
        let unknown = QuestQuestion(id: "sig", label: "Sign", required: true, kind: .unknown("signature"))
        var s = session(Fixtures.quest([Fixtures.text("a"), unknown, Fixtures.text("b")]))
        XCTAssertEqual(handling(for: unknown.kind), .unsupported)
        XCTAssertEqual(handling(for: .text(TextOptions())), .native)
        let entered = s.next()
        XCTAssertEqual(s.currentQuestion.id, "sig")
        XCTAssertTrue(entered.contains(.logUnsupported(questionId: "sig", type: "signature")))
        let left = s.next()
        XCTAssertEqual(saves(left), [])
        XCTAssertEqual(s.currentQuestion.id, "b")
        XCTAssertNil(s.answer("sig"))
        XCTAssertFalse(s.back().contains(.logUnsupported(questionId: "sig", type: "signature")))
        XCTAssertEqual(s.currentQuestion.id, "sig")
    }

    func testProgressQuirksMatchTheWeb() {
        // Two questions in all, the second hidden: progress shows, at "1 / 1".
        var s = session(Fixtures.quest([
            Fixtures.text("a"), Fixtures.text("b", visibleIf: .answered("a", true)),
        ]))
        XCTAssertTrue(s.showsProgress)
        XCTAssertEqual(s.counterText, "1 / 1")
        XCTAssertEqual(s.progressFraction, 1)
        XCTAssertEqual(s.begin(), [.focusTitle])
        var hidden = Fixtures.quest([Fixtures.text("a"), Fixtures.text("b")])
        hidden.hideProgress = true
        XCTAssertFalse(session(hidden).showsProgress)
    }

    func testQuestLabelsAndHiddenTitle() {
        var quest = Fixtures.quest([Fixtures.text("a"), Fixtures.text("b")], title: "  ")
        quest.nextLabel = "Onward"
        quest.submitLabel = "Done"
        quest.backLabel = "Prev"
        quest.description = "D"
        var s = QuestSession(quest: quest, text: QaidQuestText(next: "Weiter"), autoAdvance: false)
        XCTAssertNil(s.title)
        XCTAssertEqual(s.description, "D")
        XCTAssertEqual(s.primaryLabel, "Onward")
        XCTAssertEqual(s.backLabel, "Prev")
        _ = s.next()
        XCTAssertEqual(s.primaryLabel, "Done")
        quest.hideTitle = true
        quest.title = "T"
        XCTAssertNil(session(quest).title)
        XCTAssertNil(session(quest).description)
        XCTAssertEqual(QuestSession(quest: Fixtures.quest([Fixtures.text("a")]), text: QaidQuestText(submit: "Senden"),
                                    autoAdvance: false).primaryLabel, "Senden")
    }

    func testAQuestWhoseEveryQuestionIsHiddenStillShowsTheFirst() {
        let s = session(Fixtures.quest([Fixtures.text("a", visibleIf: .answered("z", true))]))
        XCTAssertEqual(s.visibleQuestions.map(\.id), ["a"])
        XCTAssertEqual(s.currentQuestion.id, "a")
    }

    func testFirstVisibleQuestionStarts() {
        let s = session(Fixtures.quest([Fixtures.text("a", visibleIf: .answered("z", true)), Fixtures.text("b")]))
        XCTAssertEqual(s.currentQuestion.id, "b")
    }

    func testEmptyValues() {
        XCTAssertEqual(QuestSession.emptyValue(.text(TextOptions())), .string(""))
        XCTAssertEqual(QuestSession.emptyValue(.multipleChoice(ChoiceOptions(options: [], multiple: true))), .strings([]))
        XCTAssertEqual(QuestSession.emptyValue(.multipleChoice(ChoiceOptions(options: []))), .null)
        XCTAssertEqual(QuestSession.emptyValue(.date(DateOptions())), .null)
    }
}
