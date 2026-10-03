// swift-tools-version: 5.9
//
// QaidQuests — qaid.dev quests drawn with native SwiftUI controls.
//
// The manifest sits at the repo root so Swift Package Manager can take the repo
// straight from its git URL; the sources stay under ios/, and the explicit paths
// below keep SwiftPM away from android/.
//
// Two targets, split so the logic is testable with plain `swift test` on a Mac:
//   QaidQuestsCore  Foundation only: the quest model and its decoding, visibleIf,
//                   validation, the step-by-step session, the response protocol and
//                   its ordering, the offline queue, theme mapping, text, metadata.
//   QaidQuests      SwiftUI + UIKit: the views, the sheet, the network monitor.
//                   Compiles to nothing off iOS.
import PackageDescription

let package = Package(
    name: "QaidQuests",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "QaidQuests", targets: ["QaidQuests"]),
    ],
    targets: [
        .target(name: "QaidQuestsCore", path: "ios/Sources/QaidQuestsCore"),
        .target(name: "QaidQuests", dependencies: ["QaidQuestsCore"], path: "ios/Sources/QaidQuests"),
        .testTarget(
            name: "QaidQuestsCoreTests",
            dependencies: ["QaidQuestsCore"],
            path: "ios/Tests/QaidQuestsCoreTests"
        ),
    ]
)
