import AVFoundation
import Foundation

/** Synthesized sounds (no audio files): PCM samples to a WAV in memory, played by AVAudioPlayer. */
enum Synth {
    static let rate = 44_100

    /** 16-bit mono WAV of [samples] (-1…1, clipped). */
    static func wav(_ samples: [Float], rate: Int = Synth.rate) -> Data {
        var data = Data(capacity: 44 + samples.count * 2)
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
        let bytes = UInt32(samples.count * 2)
        data.append(contentsOf: Array("RIFF".utf8)); u32(36 + bytes)
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        data.append(contentsOf: Array("data".utf8)); u32(bytes)
        var pcm = [Int16](repeating: 0, count: samples.count)
        for i in samples.indices {
            pcm[i] = Int16(max(-1, min(1, samples[i])) * Float(Int16.max))
        }
        pcm.withUnsafeBytes { raw in
            for v in raw.bindMemory(to: Int16.self) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
        }
        return data
    }

    /** A player for [samples], ready to play; nil if iOS refuses the data. */
    static func player(_ samples: [Float], rate: Int = Synth.rate) -> AVAudioPlayer? {
        let p = try? AVAudioPlayer(data: wav(samples, rate: rate), fileTypeHint: AVFileType.wav.rawValue)
        p?.prepareToPlay()
        if let p { AudioFocus.shared.track(p) }
        return p
    }
}
