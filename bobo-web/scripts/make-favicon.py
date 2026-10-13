"""
生成 favicon.ico 与 apple-touch-icon.png。

用法：python scripts/make-favicon.py
依赖：Pillow（环境里已自带）。

为什么单独用 Python 画而不是复用 public/logo.svg：
ico 与 apple-touch-icon 需要真实的位图，浏览器对 SVG 图标的支持仍不一致。
这里用同一套配色与图形重画一遍，保证和 logo.svg 视觉一致。
"""
from pathlib import Path

from PIL import Image, ImageDraw

PUBLIC = Path(__file__).resolve().parent.parent / "public"
PRIMARY = (255, 209, 0)
PRIMARY_DEEP = (255, 180, 0)
TEXT = (31, 33, 41)
ACCENT = (255, 77, 79)

CANVAS = 512
RADIUS = 116


def render(size: int) -> Image.Image:
    """画一张方形图标：圆角黄底 + 白色 bobo 字形 + 右上角红点。"""
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # 竖向渐变底：逐行插值，避免引入额外依赖
    for y in range(size):
        ratio = y / max(size - 1, 1)
        color = tuple(
            int(PRIMARY[i] + (PRIMARY_DEEP[i] - PRIMARY[i]) * ratio) for i in range(3)
        )
        draw.line([(0, y), (size, y)], fill=color)

    # 圆角遮罩
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(RADIUS * size / CANVAS), fill=255
    )
    image.putalpha(mask)

    draw = ImageDraw.Draw(image)
    stroke = max(2, int(size * 0.07))
    # 主体：两个错位的圆，象征「两个人之间的闲置流转」
    left = size * 0.30
    top = size * 0.32
    box = size * 0.20
    draw.ellipse([left, top, left + box, top + box], outline=TEXT, width=stroke)
    draw.ellipse(
        [left + box * 0.55, top + box * 0.55, left + box * 1.55, top + box * 1.55],
        outline=TEXT,
        width=stroke,
    )
    # 右上角红点：闲鱼式的「想要」强调色
    dot = size * 0.10
    draw.ellipse([size * 0.70, size * 0.16, size * 0.70 + dot, size * 0.16 + dot], fill=ACCENT)
    return image


def main() -> None:
    PUBLIC.mkdir(parents=True, exist_ok=True)

    icon = render(CANVAS)
    # ico 里塞多档尺寸，任务栏/标签页/收藏夹都能取到合适的那一档
    icon.save(PUBLIC / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    icon.resize((180, 180), Image.LANCZOS).save(PUBLIC / "apple-touch-icon.png")
    icon.resize((192, 192), Image.LANCZOS).save(PUBLIC / "logo-192.png")

    print(f"已生成 favicon.ico / apple-touch-icon.png / logo-192.png 到 {PUBLIC}")


if __name__ == "__main__":
    main()
