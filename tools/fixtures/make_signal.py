"""Generate the project's offline alarm chime: deterministic PCM, no remote audio."""
import argparse
import math
from pathlib import Path
import struct
import wave


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path,
                        default=Path(__file__).resolve().parents[2] / 'apps/agent/assets/schedule-signal.wav')
    args = parser.parse_args()
    sample_rate = 16000
    samples = []
    for index in range(sample_rate * 3):
        t = index / sample_rate
        amplitude = 0.0
        for start, frequency in ((0.0, 523.251), (0.38, 659.255), (0.76, 783.991)):
            dt = t - start
            if 0 <= dt < 1.15:
                envelope = min(1.0, dt / 0.018) * math.exp(-dt * 4.2)
                amplitude += 0.18 * envelope * (math.sin(2 * math.pi * frequency * dt)
                                               + 0.18 * math.sin(2 * math.pi * 2 * frequency * dt))
        samples.append(struct.pack('<h', round(max(-.55, min(.55, amplitude)) * 32767)))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(args.output), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(b''.join(samples))
    print(f'{args.output.name}: {args.output.stat().st_size} bytes, 3s PCM mono 16000Hz')


if __name__ == '__main__':
    main()
