from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parent.parent
image = Image.new('RGBA', (160, 180), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)
draw.polygon([(80, 7), (145, 32), (140, 105), (117, 141), (80, 169), (43, 141), (20, 105), (15, 32)], fill='#19896d')
draw.polygon([(80, 18), (133, 39), (128, 101), (108, 132), (80, 155), (52, 132), (32, 101), (27, 39)], fill='#e1b955')
draw.polygon([(42, 52), (58, 52), (80, 82), (102, 52), (118, 52), (118, 111), (101, 111), (101, 78), (80, 107), (59, 78), (59, 111), (42, 111)], fill='#ffffff')
path = root / 'frontend' / 'public' / 'brand.png'
path.parent.mkdir(parents=True, exist_ok=True)
image.save(path)
