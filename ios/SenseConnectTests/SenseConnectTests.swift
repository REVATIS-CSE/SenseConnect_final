import XCTest
@testable import SenseConnect

final class EmergencyMessageTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!
    private let uk = Locale(identifier: "en_GB")
    private var date: Date {
        var components = DateComponents(year: 2026, month: 10, day: 2, hour: 14, minute: 32)
        components.timeZone = utc
        return Calendar(identifier: .gregorian).date(from: components)!
    }
    private let fix = LocationFix(latitude: 12.9716, longitude: 77.5946, accuracy: 14.6, timestamp: Date(), isFresh: true)

    func testMapsURLUsesSixDecimalsAndDotSeparator() {
        XCTAssertEqual(fix.mapsURL, "https://maps.google.com/?q=12.971600,77.594600")
    }

    func testMessageContainsTemplateLocationAccuracyAndTime() {
        let message = EmergencyMessage.build(template: "Help me", location: fix, date: date, locale: uk, timeZone: utc)
        XCTAssertTrue(message.hasPrefix("Help me\n"))
        XCTAssertTrue(message.contains(fix.mapsURL))
        XCTAssertTrue(message.contains("±15 m"))
        XCTAssertTrue(message.contains("2 Oct 2026, 14:32"))
        XCTAssertFalse(message.contains("last known"))
    }

    func testStaleLocationIsFlagged() {
        let stale = LocationFix(latitude: 1, longitude: 2, accuracy: nil, timestamp: Date(), isFresh: false)
        let message = EmergencyMessage.build(template: "Help", location: stale, date: date, locale: uk, timeZone: utc)
        XCTAssertTrue(message.contains("[last known location]"))
    }

    func testMissingLocationIsStatedHonestly() {
        let message = EmergencyMessage.build(template: "Help", location: nil, date: date, locale: uk, timeZone: utc)
        XCTAssertTrue(message.contains("My location could not be determined."))
        XCTAssertFalse(message.contains("maps.google.com"))
    }

    func testBlankTemplateFallsBackToDefaultText() {
        let message = EmergencyMessage.build(template: "   ", location: nil, date: date, locale: uk, timeZone: utc)
        XCTAssertTrue(message.hasPrefix("EMERGENCY: I need help."))
    }
}

final class RemoteConfigTests: XCTestCase {
    func testEmergencyNumberResolvesByRegionWithFallback() {
        let config = RemoteConfig.fallback
        XCTAssertEqual(config.emergencyNumber(for: "in"), "112")
        XCTAssertEqual(config.emergencyNumber(for: "US"), "911")
        XCTAssertEqual(config.emergencyNumber(for: "GB"), "999")
        XCTAssertEqual(config.emergencyNumber(for: "ZZ"), "112")
        XCTAssertEqual(config.emergencyNumber(for: nil), "112")
    }

    func testProductionBackendIsHTTPSRender() {
        XCTAssertEqual(AppConfig.productionAPIBaseURL.absoluteString, "https://senseconnect-api.onrender.com/")
        XCTAssertEqual(AppConfig.productionAPIBaseURL.scheme, "https")
    }
}

final class TimeTextTests: XCTestCase {
    func testRelativeTimes() {
        let now = Date()
        XCTAssertEqual(TimeText.relative(now.addingTimeInterval(-20), now: now), "Just now")
        XCTAssertEqual(TimeText.relative(now.addingTimeInterval(-300), now: now), "5 min ago")
        XCTAssertEqual(TimeText.relative(now.addingTimeInterval(60), now: now), "Just now")
    }
}
