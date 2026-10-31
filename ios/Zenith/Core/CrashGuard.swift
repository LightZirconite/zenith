import Darwin
import Foundation
import Observation
import UIKit

/**
 * After a crash, home offers "Zenith s'est fermé la dernière fois · toucher pour envoyer le
 * rapport" (as on Android): an uncaught Objective-C exception or a fatal signal (Swift trap,
 * Kotlin/Native abort, bad memory access) writes a short report (device, iOS, version, reason,
 * call stack) to Application Support/crash.txt. Shared only if the driver taps it.
 */
@Observable
final class CrashGuard {
    static let shared = CrashGuard()

    /** The last crash's report, kept until shared or put away. */
    private(set) var report: String?

    @ObservationIgnored private let file: URL

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        file = dir.appendingPathComponent("crash.txt")
        report = (try? String(contentsOf: file, encoding: .utf8)).flatMap { $0.isEmpty ? nil : $0 }
    }

    /** Installs the handlers; called once at launch. */
    func install() {
        let header = """
            Zenith \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?") \
            (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"))
            \(Self.machine()) · iOS \(UIDevice.current.systemVersion)
            \(ISO8601DateFormatter().string(from: Date()))

            """
        CrashFile.prepare(path: file.path, header: header)
        NSSetUncaughtExceptionHandler { e in
            let text = "Exception \(e.name.rawValue): \(e.reason ?? "")\n" + e.callStackSymbols.joined(separator: "\n") + "\n"
            CrashFile.append(text)
        }
        for sig in [SIGABRT, SIGILL, SIGSEGV, SIGBUS, SIGFPE, SIGTRAP] {
            signal(sig) { s in
                CrashFile.appendSignal(s)
                signal(s, SIG_DFL)
                raise(s)
            }
        }
    }

    /** Put away (shared, or dismissed). */
    func clear() {
        report = nil
        try? FileManager.default.removeItem(at: file)
    }

    private static func machine() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}

/**
 * The crash file, written from a signal handler: only async-signal-safe calls there (open, write
 * of bytes prepared beforehand), the header and the path built at launch.
 */
private enum CrashFile {
    nonisolated(unsafe) static var path: [CChar] = []
    nonisolated(unsafe) static var header: [UInt8] = []

    static func prepare(path p: String, header h: String) {
        path = Array(p.utf8CString)
        header = Array(h.utf8)
    }

    static func append(_ text: String) {
        write(header + Array(text.utf8))
    }

    static func appendSignal(_ s: Int32) {
        var bytes = header
        bytes.append(contentsOf: Array("Signal ".utf8))
        bytes.append(contentsOf: Array(String(s).utf8))
        bytes.append(10)
        write(bytes)
    }

    private static func write(_ bytes: [UInt8]) {
        guard !path.isEmpty else { return }
        let fd = path.withUnsafeBufferPointer { open($0.baseAddress!, O_WRONLY | O_CREAT | O_TRUNC, 0o644) }
        guard fd >= 0 else { return }
        bytes.withUnsafeBufferPointer { _ = Darwin.write(fd, $0.baseAddress, $0.count) }
        close(fd)
    }
}
