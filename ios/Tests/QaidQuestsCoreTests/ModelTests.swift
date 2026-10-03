import XCTest
@testable import QaidQuestsCore

final class QuestJSONTests: XCTestCase {
    func testParsesEveryKind() throws {
        let value = try QuestJSON.parse(Data(#"{"a":[1,2.5,true,false,null,"x"],"b":{"c":"d"}}"#.utf8))
        XCTAssertEqual(value["a"], [1, 2.5, true, false, nil, "x"])
        XCTAssertEqual(value["b"]?["c"]?.stringValue, "d")
        XCTAssertEqual(try QuestJSON.parse(Data("3".utf8)), .number(3))
        XCTAssertThrowsError(try QuestJSON.parse(Data("{".utf8)))
    }

    func testAnythingElseIsNull() {
        XCTAssertEqual(QuestJSON(any: Date()), .null)
    }

    func testAccessorsReadOnlyTheirOwnKind() {
        let value: QuestJSON = ["s": "x", "n": 2, "b": true, "l": [1], "o": ["k": "v"]]
        XCTAssertEqual(value["s"]?.stringValue, "x")
        XCTAssertNil(value["n"]?.stringValue)
        XCTAssertEqual(value["n"]?.numberValue, 2)
        XCTAssertNil(value["s"]?.numberValue)
        XCTAssertNil(QuestJSON.number(.infinity).numberValue)
        XCTAssertEqual(value["b"]?.boolValue, true)
        XCTAssertNil(value["s"]?.boolValue)
        XCTAssertEqual(value["l"]?.arrayValue, [1])
        XCTAssertNil(value["s"]?.arrayValue)
        XCTAssertEqual(value["o"]?.objectValue, ["k": "v"])
        XCTAssertNil(value["s"]?.objectValue)
        XCTAssertNil(QuestJSON.string("x")["k"])
    }

    func testTruthinessIsJavaScripts() {
        XCTAssertFalse(QuestJSON.null.isTruthy)
        XCTAssertTrue(QuestJSON.bool(true).isTruthy)
        XCTAssertFalse(QuestJSON.bool(false).isTruthy)
        XCTAssertTrue(QuestJSON.number(2).isTruthy)
        XCTAssertFalse(QuestJSON.number(0).isTruthy)
        XCTAssertFalse(QuestJSON.number(.nan).isTruthy)
        XCTAssertTrue(QuestJSON.string("a").isTruthy)
        XCTAssertFalse(QuestJSON.string("").isTruthy)
        XCTAssertTrue(QuestJSON.array([]).isTruthy)
        XCTAssertTrue(QuestJSON.object([:]).isTruthy)
    }

    func testEncodesCompactSortedAndStable() {
        let value: QuestJSON = ["b": [1, 12.5, 0.1, -3], "a": "x/y", "c": nil, "d": false, "e": true]
        XCTAssertEqual(value.text, #"{"a":"x/y","b":[1,12.5,0.1,-3],"c":null,"d":false,"e":true}"#)
        XCTAssertEqual(QuestJSON.number(.nan).text, "null")
        XCTAssertEqual(QuestJSON.number(1e20).text, "1e+20")
        XCTAssertEqual(QuestJSON.number(12.0).text, "12")
        XCTAssertEqual(String(decoding: QuestJSON.string("a").data, as: UTF8.self), #""a""#)
    }

    func testEscapesStrings() {
        XCTAssertEqual(QuestJSON.string("q\"b\\n\nr\rt\tb\u{08}f\u{0C}z\u{01}é").text,
                       #""q\"b\\n\nr\rt\tb\bf\fz\u0001é""#)
    }

    func testRoundTripsThroughFoundation() throws {
        let value: QuestJSON = ["text": "line\n\"quoted\" ✓", "n": 0.30000000000000004, "list": ["a", "b"]]
        XCTAssertEqual(try QuestJSON.parse(value.data), value)
    }

    func testDictionaryLiteralKeepsTheLastDuplicate() {
        let value: QuestJSON = ["a": 1, "a": 2]
        XCTAssertEqual(value["a"], 2)
    }
}

final class AnswerTests: XCTestCase {
    func testWireShapes() {
        XCTAssertEqual(QuestAnswer.string("x").json, "x")
        XCTAssertEqual(QuestAnswer.number(12.5).json, 12.5)
        XCTAssertEqual(QuestAnswer.strings(["a", "b"]).json, ["a", "b"])
        XCTAssertEqual(QuestAnswer.null.json, .null)
    }

    func testReadsBackWhatItWrites() {
        for answer in [QuestAnswer.string("x"), .number(3), .strings(["a"]), .strings([]), .null] {
            XCTAssertEqual(QuestAnswer(json: answer.json), answer)
        }
        XCTAssertNil(QuestAnswer(json: [1, "a"]))
        XCTAssertNil(QuestAnswer(json: true))
        XCTAssertNil(QuestAnswer(json: ["k": "v"]))
    }
}

final class DecodingTests: XCTestCase {
    func testDecodesTheFixture() {
        let quest = Fixtures.quest
        XCTAssertEqual(quest.id, "quest_1")
        XCTAssertEqual(quest.slug, "onboarding")
        XCTAssertEqual(quest.versionId, "v1")
        XCTAssertEqual(quest.versionNumber, 3)
        XCTAssertEqual(quest.title, "Tell us")
        XCTAssertEqual(quest.description, "Two minutes")
        XCTAssertFalse(quest.hideTitle)
        XCTAssertFalse(quest.hideProgress)
        XCTAssertEqual(quest.progressPosition, .top)
        XCTAssertNil(quest.thankYouTitle)
        XCTAssertEqual(quest.questions.map(\.id), ["name", "budget", "score", "when", "color", "why"])
        XCTAssertEqual(quest.questions.map(\.kind.typeName),
                       ["text", "currency", "range", "date", "multiple-choice", "text"])
        XCTAssertEqual(quest.questions[0].kind, .text(TextOptions(maxLength: 10, minLength: 2)))
        XCTAssertTrue(quest.questions[0].required)
        XCTAssertEqual(quest.questions[1].kind, .currency(CurrencyOptions(currency: "EUR", min: 0, max: 100)))
        XCTAssertEqual(quest.questions[2].kind,
                       .range(RangeOptions(min: 0, max: 10, step: 1, defaultValue: 5, unit: "/10")))
        XCTAssertEqual(quest.questions[3].kind, .date(DateOptions(min: "2026-01-01", max: "2026-12-31")))
        XCTAssertEqual(quest.questions[5].visibleIf, .equals("color", .string("blue")))
        XCTAssertEqual(quest.question("budget")?.label, "Budget")
        XCTAssertNil(quest.question("nope"))
    }

    func testTopLevelOptions() throws {
        let data = Data(#"""
        {"title":"  ","hideTitle":true,"hideProgress":true,"progressPosition":"bottom","thankYouTitle":"Ta",
         "thankYouMessage":"Bye","submitLabel":"Send","nextLabel":"On","backLabel":" ","versionNumber":1e300,
         "questions":[{"id":"a","type":"text","label":"A"}]}
        """#.utf8)
        let quest = try Questionnaire.decode(data, fallbackId: "asked").get()
        XCTAssertEqual(quest.id, "asked")
        XCTAssertTrue(quest.hideTitle)
        XCTAssertTrue(quest.hideProgress)
        XCTAssertEqual(quest.progressPosition, .bottom)
        XCTAssertEqual(quest.thankYouTitle, "Ta")
        XCTAssertEqual(quest.thankYouMessage, "Bye")
        XCTAssertEqual(quest.submitLabel, "Send")
        XCTAssertEqual(quest.nextLabel, "On")
        XCTAssertNil(quest.backLabel)
        XCTAssertNil(quest.versionNumber)
    }

    func testFailures() {
        XCTAssertEqual(Questionnaire.decode(Data("nope".utf8), fallbackId: "q"), .failure(.invalid))
        XCTAssertEqual(Questionnaire.decode(Data("[1]".utf8), fallbackId: "q"), .failure(.invalid))
        XCTAssertEqual(Questionnaire.decode(Data("{}".utf8), fallbackId: "q"), .failure(.empty))
        XCTAssertEqual(Questionnaire.decode(Data(#"{"questions":[]}"#.utf8), fallbackId: "q"), .failure(.empty))
        XCTAssertEqual(Questionnaire.decode(Data(#"{"questions":[{"label":"no id"},5]}"#.utf8), fallbackId: "q"),
                       .failure(.empty))
    }

    func testDropsRepeatedIdsAndKeepsUnknownTypes() throws {
        let data = Data(#"""
        {"questions":[{"id":"a","type":"text","label":"A"},{"id":"a","type":"date","label":"again"},
          {"id":"b","type":"signature","label":"Sign"},{"id":"c","label":"untyped"}]}
        """#.utf8)
        let quest = try Questionnaire.decode(data, fallbackId: "q").get()
        XCTAssertEqual(quest.questions.map(\.id), ["a", "b", "c"])
        XCTAssertEqual(quest.questions[1].kind, .unknown("signature"))
        XCTAssertEqual(quest.questions[1].kind.typeName, "signature")
        XCTAssertEqual(quest.questions[2].kind, .unknown(""))
        XCTAssertFalse(quest.questions[1].debouncesSaves)
    }

    func testQuestionFields() {
        let question = QuestQuestion.decode([
            "id": "a", "type": "text", "label": "A", "description": "help", "required": true, "visibleIf": nil,
        ])
        XCTAssertEqual(question?.description, "help")
        XCTAssertEqual(question?.required, true)
        XCTAssertNil(question?.visibleIf)
        XCTAssertEqual(QuestQuestion.decode(["id": "b"])?.label, "")
        XCTAssertNil(QuestQuestion.decode(["id": " "]))
        XCTAssertNil(QuestQuestion.decode("text"))
    }

    func testTextOptions() {
        let kind = QuestionKind.decode([
            "type": "text", "placeholder": "Hi", "multiline": true, "maxLength": 0, "minLength": -1, "inputType": "email",
        ])
        XCTAssertEqual(kind, .text(TextOptions(placeholder: "Hi", multiline: true, inputType: .email)))
        XCTAssertEqual(QuestionKind.decode(["type": "text", "inputType": "password", "maxLength": 1e300]),
                       .text(TextOptions()))
        XCTAssertEqual(QuestionKind.decode(["type": "text", "minLength": 3.7]), .text(TextOptions(minLength: 3)))
        for type in ["text", "currency", "range"] {
            XCTAssertTrue(QuestQuestion(id: "x", label: "", kind: QuestionKind.decode([
                "type": .string(type), "min": 0, "max": 1,
            ])).debouncesSaves)
        }
    }

    func testCurrencyOptions() {
        XCTAssertEqual(QuestionKind.decode(["type": "currency", "currency": "eur", "locale": " ", "placeholder": "0"]),
                       .currency(CurrencyOptions(currency: "USD", placeholder: "0")))
        XCTAssertEqual(QuestionKind.decode(["type": "currency", "currency": "GBP", "locale": "en-GB"]),
                       .currency(CurrencyOptions(currency: "GBP", locale: "en-GB")))
        XCTAssertEqual(QuestionKind.decode(["type": "currency", "currency": "EURO"]), .currency(CurrencyOptions()))
    }

    func testRangeOptionsNeedBounds() {
        XCTAssertEqual(QuestionKind.decode(["type": "range", "min": 1]), .unknown("range"))
        XCTAssertEqual(QuestionKind.decode(["type": "range", "min": 5, "max": 5]), .unknown("range"))
        XCTAssertEqual(QuestionKind.decode(["type": "range", "max": 5]), .unknown("range"))
        XCTAssertEqual(QuestionKind.decode(["type": "range", "min": 0, "max": 5, "step": -1]),
                       .range(RangeOptions(min: 0, max: 5)))
    }

    func testDateOptionsDropBadBounds() {
        XCTAssertEqual(QuestionKind.decode(["type": "date", "min": "2026-1-1", "max": "2026-02-30"]),
                       .date(DateOptions(max: "2026-02-30")))
    }

    func testChoiceOptions() {
        let kind = QuestionKind.decode([
            "type": "multiple-choice", "multiple": true, "imageAlignment": "vertical",
            "options": [
                ["value": "a", "label": "A", "image": "https://x.test/a.png", "description": "first"],
                ["value": "a", "label": "dup"],
                ["value": " ", "label": "blank"],
                ["value": "b", "image": " "],
                "junk",
            ],
        ])
        guard case .multipleChoice(let options) = kind else { return XCTFail("\(kind)") }
        XCTAssertTrue(options.multiple)
        XCTAssertEqual(options.imageAlignment, .vertical)
        XCTAssertEqual(options.options, [
            ChoiceOption(value: "a", label: "A", description: "first", image: URL(string: "https://x.test/a.png")),
            ChoiceOption(value: "b", label: "b"),
        ])
        XCTAssertTrue(options.hasImages)
        XCTAssertFalse(ChoiceOptions(options: [ChoiceOption(value: "x", label: "x")]).hasImages)
        XCTAssertEqual(QuestionKind.decode(["type": "multiple-choice", "options": []]), .unknown("multiple-choice"))
        XCTAssertEqual(QuestionKind.decode(["type": "multiple-choice", "options": [["value": "x"]], "imageAlignment": "diagonal"]),
                       .multipleChoice(ChoiceOptions(options: [ChoiceOption(value: "x", label: "x")])))
    }

    func testRulesDecodeInTheWebOrder() {
        XCTAssertEqual(VisibilityRule.decode(["allOf": [["questionId": "a", "answered": true]], "questionId": "b"]),
                       .allOf([.answered("a", true)]))
        XCTAssertEqual(VisibilityRule.decode(["anyOf": [["questionId": "a", "notEquals": 3]]]),
                       .anyOf([.notEquals("a", .number(3))]))
        XCTAssertEqual(VisibilityRule.decode(["allOf": "x"]), .invalid)
        XCTAssertEqual(VisibilityRule.decode(["anyOf": 1]), .invalid)
        XCTAssertEqual(VisibilityRule.decode(["questionId": "a", "answered": "yes"]), .answered("a", true))
        XCTAssertEqual(VisibilityRule.decode(["questionId": "a", "answered": 0]), .answered("a", false))
        XCTAssertEqual(VisibilityRule.decode(["questionId": "a", "equals": true]), .equals("a", .other))
        XCTAssertEqual(VisibilityRule.decode(["questionId": "a", "in": ["x", 2]]),
                       .oneOf("a", [.string("x"), .number(2)]))
        XCTAssertEqual(VisibilityRule.decode(["questionId": "a", "in": "x"]), .invalid)
        XCTAssertEqual(VisibilityRule.decode(["equals": "x"]), .equals("", .string("x")))
        XCTAssertEqual(VisibilityRule.decode(["questionId": "a"]), .invalid)
        XCTAssertEqual(VisibilityRule.decode("a"), .invalid)
    }

    func testWholeNumber() {
        XCTAssertEqual(wholeNumber(3.9), 3)
        XCTAssertEqual(wholeNumber(-2.5), -3)
        XCTAssertNil(wholeNumber(1e16))
    }
}
