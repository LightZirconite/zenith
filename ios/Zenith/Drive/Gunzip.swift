import Foundation

/**
 * gzip files (road tiles) to bytes: the header is skipped by hand, then Foundation inflates the
 * raw DEFLATE stream (NSData's "zlib" algorithm is raw DEFLATE). No dependency.
 */
enum Gunzip {
    static func inflate(_ data: Data) -> Data? {
        let b = [UInt8](data)
        guard b.count > 18, b[0] == 0x1f, b[1] == 0x8b, b[2] == 8 else { return nil }
        let flags = b[3]
        var i = 10
        if flags & 0x04 != 0 { // FEXTRA
            guard i + 2 <= b.count else { return nil }
            i += 2 + (Int(b[i]) | (Int(b[i + 1]) << 8))
        }
        if flags & 0x08 != 0 { // FNAME
            while i < b.count && b[i] != 0 { i += 1 }
            i += 1
        }
        if flags & 0x10 != 0 { // FCOMMENT
            while i < b.count && b[i] != 0 { i += 1 }
            i += 1
        }
        if flags & 0x02 != 0 { i += 2 } // FHCRC
        guard i < b.count - 8 else { return nil }
        let deflated = Data(b[i..<(b.count - 8)])
        return try? (deflated as NSData).decompressed(using: .zlib) as Data
    }
}
