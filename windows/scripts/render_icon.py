"""Optional: render the code-native cloud mark to a multi-size Windows icon (Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw

size = 1024
image = Image.new("RGBA", (size, size))
draw = ImageDraw.Draw(image)
draw.rounded_rectangle((32, 32, 992, 992), radius=240, fill="#28599b")
points = [(70, 168)]
def curve(a, b, end):
    start = points[-1]
    for step in range(1, 41):
        t = step / 40
        points.append(tuple((1-t)**3*start[i]+3*(1-t)**2*t*a[i]+3*(1-t)*t*t*b[i]+t**3*end[i] for i in (0, 1)))
curve((42,168),(40,126),(66,120))
curve((64,83),(105,65),(132,85))
curve((157,64),(194,86),(190,115))
curve((224,117),(225,168),(190,168))
points.append((70,168))
draw.line([(int(x*4),int(y*4)) for x,y in points], fill="white", width=48, joint="curve")
for x, y in [(96,132),(128,112),(160,124)]:
    draw.line((x*4,y*4,x*4,146*4),fill="#b8deff",width=40)
    for cy in (y,146):draw.ellipse((x*4-20,cy*4-20,x*4+20,cy*4+20),fill="#b8deff")
destination=Path(__file__).resolve().parents[1]/"src/TrafficBoard.App/Assets/traffic-board.ico"
image.resize((256,256),Image.Resampling.LANCZOS).save(destination,sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
print(destination)
