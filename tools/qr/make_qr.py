"""상태관리 화면의 용품 QR 2개를 만든다(앱에는 그림만 들어가고 QR 라이브러리는 넣지 않는다).

쓰는 법: uv run --with "qrcode[pil]" --with opencv-python-headless python tools/qr/make_qr.py
상품을 바꾸려면 아래 PRODUCTS 의 검색어만 고친다. 쿠팡은 상품 번호가 자주 바뀌어 상품명 검색 주소를 쓴다.
"""
from pathlib import Path
from urllib.parse import quote

import cv2
import qrcode

OUT = Path(__file__).resolve().parents[2] / "app/app/src/main/res/drawable-nodpi"
PRODUCTS = {
    "qr_washer_fluid.png": "불스원 레인OK 에탄올 3in1 코팅워셔액 1.8L",
    "qr_tire_inflator.png": "샤오미 미지아 무선 공기주입기 2",
}

for name, keyword in PRODUCTS.items():
    url = "https://www.coupang.com/np/search?q=" + quote(keyword)
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=12, border=4)
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB").resize((492, 492), 0)
    img.save(OUT / name, optimize=True)
    decoded, _, _ = cv2.QRCodeDetector().detectAndDecode(cv2.imread(str(OUT / name)))
    assert decoded == url, (name, decoded)
    print(name, "→", keyword, "| 읽기 확인 OK")
