import XCTest
@testable import QaidQuestsCore

/// The web embed's `visibility.test.ts` cases, plus the edges protocol §4 lists.
final class VisibilityTests: XCTestCase {
    private func visible(_ questions: [QuestQuestion], _ answers: [String: QuestAnswer]) -> [String] {
        QuestVisibility.visibleQuestions(questions, answers: answers).map(\.id)
    }

    private func check(_ rule: VisibilityRule, _ answers: [String: QuestAnswer], _ expected: Bool,
                       file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(QuestVisibility.evaluate(rule, answers: answers), expected, file: file, line: line)
    }

    func testNoRuleIsAlwaysVisible() {
        XCTAssertEqual(visible([Fixtures.text("a"), Fixtures.text("b")], [:]), ["a", "b"])
    }

    func testAnswered() {
        check(.answered("a", true), ["a": .string("x")], true)
        check(.answered("a", true), ["a": .string("  \n")], false)
        check(.answered("a", true), ["a": .string("")], false)
        check(.answered("a", true), ["a": .null], false)
        check(.answered("a", true), [:], false)
        check(.answered("a", true), ["a": .strings([])], false)
        check(.answered("a", true), ["a": .strings(["x"])], true)
        check(.answered("a", true), ["a": .number(0)], true)
        check(.answered("a", false), [:], true)
        check(.answered("a", false), ["a": .string("x")], false)
    }

    func testEqualsIsStrict() {
        check(.equals("a", .string("x")), ["a": .string("x")], true)
        check(.equals("a", .string("x")), ["a": .string("x ")], false)
        check(.equals("a", .string("X")), ["a": .string("x")], false)
        check(.equals("a", .string("5")), ["a": .number(5)], false)
        check(.equals("a", .number(5)), ["a": .number(5)], true)
        check(.equals("a", .number(5)), ["a": .string("5")], false)
        check(.equals("a", .other), ["a": .string("true")], false)
        check(.equals("a", .number(.nan)), ["a": .number(.nan)], false)
    }

    func testMultiSelectMatchesAnySelected() {
        check(.equals("a", .string("y")), ["a": .strings(["x", "y"])], true)
        check(.equals("a", .string("z")), ["a": .strings(["x", "y"])], false)
        check(.equals("a", .number(1)), ["a": .strings(["1"])], false)
        check(.notEquals("a", .string("y")), ["a": .strings(["x", "y"])], false)
        check(.oneOf("a", [.string("q"), .string("x")]), ["a": .strings(["x"])], true)
    }

    func testComparisonsAgainstUnansweredAreFalseNotEqualsToo() {
        for answers: [String: QuestAnswer] in [[:], ["a": .null], ["a": .string(" ")], ["a": .strings([])]] {
            check(.equals("a", .string("x")), answers, false)
            check(.notEquals("a", .string("x")), answers, false)
            check(.oneOf("a", [.string("x")]), answers, false)
        }
        check(.notEquals("a", .string("x")), ["a": .string("y")], true)
        check(.notEquals("a", .string("x")), ["a": .string("x")], false)
    }

    func testIn() {
        check(.oneOf("a", [.string("x"), .string("y")]), ["a": .string("y")], true)
        check(.oneOf("a", [.string("x")]), ["a": .string("y")], false)
        check(.oneOf("a", []), ["a": .string("y")], false)
        check(.oneOf("a", [.number(3)]), ["a": .number(3)], true)
    }

    func testCombinators() {
        let yes = VisibilityRule.answered("a", true), no = VisibilityRule.answered("a", false)
        let answers: [String: QuestAnswer] = ["a": .string("x")]
        check(.allOf([]), [:], true)
        check(.anyOf([]), [:], false)
        check(.allOf([yes, yes]), answers, true)
        check(.allOf([yes, no]), answers, false)
        check(.anyOf([no, yes]), answers, true)
        check(.anyOf([no, no]), answers, false)
        check(.allOf([.anyOf([no, yes]), .notEquals("a", .string("z"))]), answers, true)
    }

    func testInvalidAndUnknownIdsNeverThrow() {
        check(.invalid, ["a": .string("x")], false)
        check(.equals("ghost", .string("x")), ["a": .string("x")], false)
        check(.answered("ghost", false), [:], true)
    }

    func testHiddenQuestionsReadAsUnansweredAndChainsCollapse() {
        let questions = [
            Fixtures.choice("color", ["red", "blue"]),
            Fixtures.text("why", visibleIf: .equals("color", .string("blue"))),
            Fixtures.text("more", visibleIf: .answered("why", true)),
            Fixtures.text("last"),
        ]
        XCTAssertEqual(visible(questions, ["color": .string("blue"), "why": .string("calm")]),
                       ["color", "why", "more", "last"])
        // Back to red: "why" hides, so its typed answer no longer counts and "more" goes too.
        XCTAssertEqual(visible(questions, ["color": .string("red"), "why": .string("calm")]), ["color", "last"])
        XCTAssertEqual(QuestVisibility.visibleAnswers(questions, answers: [
            "color": .string("red"), "why": .string("calm"), "last": .string("x"), "stray": .string("y"),
        ]), ["color": .string("red"), "last": .string("x")])
    }

    func testRulesSeeOnlyEarlierQuestions() {
        let questions = [
            Fixtures.text("a", visibleIf: .answered("b", true)),
            Fixtures.text("b"),
            Fixtures.text("c", visibleIf: .answered("c", true)),
        ]
        XCTAssertEqual(visible(questions, ["b": .string("x"), "c": .string("y")]), ["b"])
    }

    func testIsAnsweredPerType() {
        XCTAssertFalse(QuestVisibility.isAnswered(nil))
        XCTAssertFalse(QuestVisibility.isAnswered(.null))
        XCTAssertTrue(QuestVisibility.isAnswered(.number(-1)))
        XCTAssertTrue(QuestVisibility.isAnswered(.string("2026-01-01")))
    }
}
