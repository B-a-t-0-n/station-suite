"""Generate the project's finite 25x16 bell glyph and clear frame. Requires Pillow."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path,
                        default=Path(__file__).resolve().parents[2] / 'apps/agent/assets')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    frames = []
    for intensity in (56, 18, 56, 18, 56, 18):
        image = Image.new('P', (25, 16), 0)
        image.putpalette([value for n in range(256) for value in (n, n, n)])
        pen = ImageDraw.Draw(image)
        pen.line([(7, 11), (9, 9), (9, 5), (10, 3), (14, 3), (15, 5), (15, 9),
                  (17, 11), (7, 11)], fill=intensity, width=1)
        pen.line([(11, 13), (13, 13)], fill=intensity)
        pen.point((12, 1), fill=intensity)
        frames.append(image)
    alert = args.output_dir / 'schedule-alert.gif'
    frames[0].save(alert, save_all=True, append_images=frames[1:], duration=500,
                   optimize=False, disposal=2)
    clear = Image.new('P', (25, 16), 0)
    clear.putpalette([value for n in range(256) for value in (n, n, n)])
    clear.save(args.output_dir / 'schedule-clear.gif', duration=100, optimize=False)
    with Image.open(alert) as gif:
        assert gif.size == (25, 16) and 'loop' not in gif.info and gif.n_frames == 6
    print(f'{alert.name}: {alert.stat().st_size} bytes, 6 finite frames')


if __name__ == '__main__':
    main()
